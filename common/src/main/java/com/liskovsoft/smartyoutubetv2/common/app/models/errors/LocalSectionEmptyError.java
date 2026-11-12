package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

/** A non-playable local section empty state with an optional contextual action. */
public final class LocalSectionEmptyError implements ErrorFragmentData {
    private final String mMessage;
    private final String mActionText;
    private final Runnable mAction;

    public LocalSectionEmptyError(String message, String actionText, Runnable action) {
        mMessage = message;
        mActionText = actionText;
        mAction = action;
    }

    @Override
    public void onAction() {
        if (mAction != null) mAction.run();
    }

    @Override
    public String getMessage() {
        return mMessage;
    }

    @Override
    public String getActionText() {
        return mActionText;
    }
}
