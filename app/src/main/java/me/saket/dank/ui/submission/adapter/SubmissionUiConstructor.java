package me.saket.dank.ui.submission.adapter;

import static io.reactivex.schedulers.Schedulers.io;
import static me.saket.dank.utils.Arrays2.immutable;

import android.content.Context;
import android.support.annotation.CheckResult;
import android.support.annotation.ColorInt;
import android.support.annotation.ColorRes;
import android.support.v4.content.ContextCompat;
import android.text.Html;
import android.text.style.ForegroundColorSpan;

import net.dean.jraw.models.CommentNode;
import net.dean.jraw.models.Submission;
import net.dean.jraw.models.VoteDirection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;

import dagger.Lazy;
import io.reactivex.Observable;
import me.saket.dank.R;
import me.saket.dank.data.ResolvedError;
import me.saket.dank.data.VotingManager;
import me.saket.dank.data.links.Link;
import me.saket.dank.ui.submission.BookmarksRepository;
import me.saket.dank.ui.submission.SubmissionCommentTreeUiConstructor;
import me.saket.dank.ui.submission.ParentThread;
import me.saket.dank.ui.submission.ReplyRepository;
import me.saket.dank.ui.submission.SubmissionContentLoadError;
import me.saket.dank.ui.user.UserSessionRepository;
import me.saket.dank.utils.Commons;
import me.saket.dank.utils.DankSubmissionRequest;
import me.saket.dank.utils.Dates;
import me.saket.dank.utils.JrawUtils;
import me.saket.dank.utils.Markdown;
import me.saket.dank.utils.Optional;
import me.saket.dank.utils.Pair;
import me.saket.dank.utils.Strings;
import me.saket.dank.utils.Truss;
import timber.log.Timber;

// TODO: Build a subcomponent for SubredditActivity?
public class SubmissionUiConstructor {

  private final SubmissionContentLinkUiConstructor contentLinkUiModelConstructor;
  private final ReplyRepository replyRepository;
  private final VotingManager votingManager;
  private final Markdown markdown;
  private final UserSessionRepository userSessionRepository;
  private final Lazy<BookmarksRepository> bookmarksRepository;

  @Inject
  public SubmissionUiConstructor(
      SubmissionContentLinkUiConstructor contentLinkUiModelConstructor,
      ReplyRepository replyRepository,
      VotingManager votingManager,
      Markdown markdown,
      UserSessionRepository userSessionRepository,
      Lazy<BookmarksRepository> bookmarksRepository)
  {
    this.contentLinkUiModelConstructor = contentLinkUiModelConstructor;
    this.replyRepository = replyRepository;
    this.votingManager = votingManager;
    this.markdown = markdown;
    this.userSessionRepository = userSessionRepository;
    this.bookmarksRepository = bookmarksRepository;
  }

  /**
   * @param optionalSubmissions Can emit twice. Once w/o comments and once with comments.
   */
  @CheckResult
  public Observable<List<SubmissionScreenUiModel>> stream(
      Context context,
      SubmissionCommentTreeUiConstructor submissionCommentTreeUiConstructor,
      Observable<Optional<Submission>> optionalSubmissions,
      Observable<DankSubmissionRequest> submissionRequests,
      Observable<Optional<Link>> contentLinks,
      Observable<Optional<SubmissionContentLoadError>> mediaContentLoadErrors,
      Observable<Optional<ResolvedError>> commentsLoadErrors)
  {
    return optionalSubmissions
        .distinctUntilChanged()
        .switchMap(optional -> {
          if (!optional.isPresent()) {
            return commentsLoadErrors.map(optionalError -> optionalError.isPresent()
                ? Collections.singletonList(SubmissionCommentsLoadError.UiModel.create(optionalError.get()))
                : Collections.emptyList());
          }

          Observable<Submission> submissions = optionalSubmissions
              // Not sure why, but the parent switchMap() on submission change gets triggered
              // after this chain receives an empty submission, so adding this extra takeWhile().
              .takeWhile(optionalSub -> optionalSub.isPresent())
              .map(submissionOptional -> submissionOptional.get());

          Observable<Optional<SubmissionContentLinkUiModel>> contentLinkUiModels = Observable
              .combineLatest(contentLinks, submissions.observeOn(io()), Pair::create)
              .distinctUntilChanged()
              .switchMap(pair -> {
                Optional<Link> contentLink = pair.first();
                if (!contentLink.isPresent()) {
                  return Observable.just(Optional.empty());
                }
                Submission submission = pair.second();
                return contentLinkUiModelConstructor
                    .streamLoad(context, contentLink.get(), ImageWithMultipleVariants.of(submission.getThumbnails()))
                    .doOnError(e -> Timber.e(e, "Error while creating content link ui model"))
                    .map(Optional::of);
              });

          Observable<Integer> submissionPendingSyncReplyCounts = submissions
              .observeOn(io())
              .take(1)  // replace flatMap -> switchMap if we expect more than 1 emissions.
              .flatMap(submission -> replyRepository.streamPendingSyncReplies(ParentThread.of(submission)))
              .map(pendingSyncReplies -> pendingSyncReplies.size());

          Observable<Object> externalChanges = Observable.merge(votingManager.streamChanges(), bookmarksRepository.get().streamChanges());

          Observable<SubmissionCommentsHeader.UiModel> headerUiModels = Observable.combineLatest(
              externalChanges.observeOn(io()).map(o -> context),
              submissions.observeOn(io()),
              submissionPendingSyncReplyCounts,
              contentLinkUiModels,
              this::headerUiModel
          );

          // FIXME: The error message says "Reddit" even for imgur, and other services.
          Observable<Optional<SubmissionMediaContentLoadError.UiModel>> contentLoadErrorUiModels = mediaContentLoadErrors
              .map(optionalError -> optionalError.map(error -> error.uiModel(context)));

          Observable<Optional<SubmissionCommentsViewFullThread.UiModel>> viewFullThreadUiModels = submissionRequests
              .map(request -> request.focusCommentId() == null
                  ? Optional.<SubmissionCommentsViewFullThread.UiModel>empty()
                  : Optional.of(SubmissionCommentsViewFullThread.UiModel.create(request)));

          Observable<List<SubmissionScreenUiModel>> commentRowUiModels = submissionCommentTreeUiConstructor.stream(
              context,
              submissions.observeOn(io()),
              submissionRequests,
              io()
          );

          Observable<Optional<SubmissionCommentsLoadProgress.UiModel>> commentsLoadProgressUiModels = Observable
              .combineLatest(submissions.observeOn(io()), commentsLoadErrors, Pair::create)
              .map(pair -> {
                CommentNode comments = pair.first().getComments();
                boolean commentsLoadingFailed = pair.second().isPresent();
                return comments == null && !commentsLoadingFailed
                    ? Optional.of(SubmissionCommentsLoadProgress.UiModel.create())
                    : Optional.<SubmissionCommentsLoadProgress.UiModel>empty();
              })
              .startWith(Optional.of(SubmissionCommentsLoadProgress.UiModel.create()));

          Observable<Optional<SubmissionCommentsLoadError.UiModel>> commentsLoadErrorUiModels = commentsLoadErrors
              .map(optionalError -> optionalError.map(error -> SubmissionCommentsLoadError.UiModel.create(error)));

          return Observable.combineLatest(
              headerUiModels,
              contentLoadErrorUiModels,
              viewFullThreadUiModels,
              commentsLoadProgressUiModels,
              commentsLoadErrorUiModels,
              commentRowUiModels,
              (header, optionalContentError, viewFullThread, optionalCommentsLoadProgress, optionalCommentsLoadError, commentRowModels) -> {
                List<SubmissionScreenUiModel> allItems = new ArrayList<>(4 + commentRowModels.size());
                allItems.add(header);
                optionalContentError.ifPresent(allItems::add);
                viewFullThread.ifPresent(allItems::add);
                allItems.addAll(commentRowModels);

                // Comments progress and error go after comment rows
                // so that inline reply for submission appears above them.
                optionalCommentsLoadProgress.ifPresent(allItems::add);
                optionalCommentsLoadError.ifPresent(allItems::add);
                return allItems;
              })
              .as(immutable());
        });
  }

  /**
   * Header contains submission details, content link and self-text post.
   */
  private SubmissionCommentsHeader.UiModel headerUiModel(
      Context context,
      Submission submission,
      int pendingSyncReplyCount,
      Optional<SubmissionContentLinkUiModel> contentLinkUiModel)
  {
    VoteDirection pendingOrDefaultVote = votingManager.getPendingOrDefaultVote(submission, submission.getVote());
    int voteDirectionColor = Commons.voteColor(pendingOrDefaultVote);
    long adapterId = submission.getFullName().hashCode();

    Optional<CharSequence> selfTextOptional = submission.isSelfPost() && !submission.getSelftext().isEmpty()
        ? Optional.of(markdown.parseSelfText(submission))
        : Optional.empty();

    int vote = votingManager.getScoreAfterAdjustingPendingVote(submission);
    int postedAndPendingCommentCount = submission.getCommentCount() + pendingSyncReplyCount;

    Truss titleBuilder = new Truss();
    titleBuilder.pushSpan(new ForegroundColorSpan(color(context, voteDirectionColor)));
    titleBuilder.append(Strings.abbreviateScore(vote));
    titleBuilder.popSpan();
    titleBuilder.append("  ");
    //noinspection deprecation
    titleBuilder.append(Html.fromHtml(submission.getTitle()));
    CharSequence title = titleBuilder.build();
    String byline = context.getString(
        R.string.submission_byline,
        submission.getSubredditName(),
        submission.getAuthor(),
        Dates.createTimestamp(context.getResources(), JrawUtils.createdTimeUtc(submission)),
        Strings.abbreviateScore(postedAndPendingCommentCount)
    );

    return SubmissionCommentsHeader.UiModel.builder()
        .adapterId(adapterId)
        .title(title, Pair.create(vote, pendingOrDefaultVote))
        .byline(byline, postedAndPendingCommentCount)
        .selfText(selfTextOptional)
        .optionalContentLinkModel(contentLinkUiModel)
        .submission(submission)
        .isSaved(bookmarksRepository.get().isSaved(submission))
        .build();
  }

  private List<SubmissionScreenUiModel> commentRowUiModels(List<SubmissionScreenUiModel> rows) {
    return rows;
  }

  @ColorInt
  private static int color(Context context, @ColorRes int colorRes) {
    return ContextCompat.getColor(context, colorRes);
  }
}