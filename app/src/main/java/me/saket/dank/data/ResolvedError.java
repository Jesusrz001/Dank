package me.saket.dank.data;

import android.support.annotation.StringRes;

import com.google.auto.value.AutoValue;

/**
 * Details of an error resolved by {@link ErrorManager}.
 */
@AutoValue
public abstract class ResolvedError {

    public enum Type {
        UNKNOWN,
        NETWORK_ERROR,
        REDDIT_IS_DOWN,
    }

    public abstract Type type();

    @StringRes
    public abstract int errorEmoji();

    @StringRes
    public abstract int errorMessage();

    public boolean isUnknown() {
        return type() == Type.UNKNOWN;
    }

    public static ResolvedError create(Type type, @StringRes int errorEmoji, @StringRes int errorMessage) {
        return new AutoValue_ResolvedError(type, errorEmoji, errorMessage);
    }

}