package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

/** Non-interactive error state for sections without suitable local offline content. */
public final class AutoOfflineUnavailableError implements ErrorFragmentData {
    private final String mMessage;
    private final String mActionText;
    private final Runnable mAction;

    public AutoOfflineUnavailableError(String message, String actionText, Runnable action) {
        mMessage = message;
        mActionText = actionText;
        mAction = action;
    }

    public static AutoOfflineUnavailableError forLocalOnly(String actionText, Runnable openLocalLibrary) {
        return new AutoOfflineUnavailableError(
                "YouTube is unavailable in LOCAL ONLY mode.", actionText, openLocalLibrary);
    }

    @Override public void onAction() {
        if (mAction != null) mAction.run();
    }

    @Override
    public String getMessage() {
        return mMessage;
    }

    @Override public String getActionText() { return mActionText; }
}
