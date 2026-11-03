package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveConstants;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveSettings;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class CategoryEmptyError implements ErrorFragmentData {
    private final Context mContext;
    private final Throwable mError;

    public CategoryEmptyError(Context context, @Nullable Throwable error) {
        mContext = context;
        mError = error;
    }

    @Override
    public void onAction() {
        if (YouTubeNetworkError.isYouTubeDnsFailure(mError) && hasLocalArchiveAccess()) {
            BrowsePresenter.instance(mContext).selectSection(LocalArchiveConstants.SECTION_ID);
        }
    }

    @Override
    public String getMessage() {
        if (YouTubeNetworkError.isYouTubeDnsFailure(mError)) {
            return mContext.getString(R.string.youtube_unavailable_offline);
        }

        String result = mContext.getString(R.string.msg_cant_load_content);
        if (mError != null && !Helpers.containsAny(mError.getMessage(), "fromNullable result is null")) {
            String className = mError.getClass().getSimpleName();
            result = String.format("%s: %s", className, Utils.getStackTraceAsString(mError));
        }
        return result;
    }

    @Override
    public String getActionText() {
        return YouTubeNetworkError.isYouTubeDnsFailure(mError) && hasLocalArchiveAccess()
                ? mContext.getString(R.string.open_local_library) : null;
    }

    private boolean hasLocalArchiveAccess() {
        return new LocalArchiveSettings(mContext).isLibraryEnabledForActiveProfile();
    }
}
