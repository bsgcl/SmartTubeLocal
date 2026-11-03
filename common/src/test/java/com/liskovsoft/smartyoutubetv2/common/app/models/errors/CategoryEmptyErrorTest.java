package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

import android.content.Context;

import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveSettings;
import com.liskovsoft.youtubeapi.service.internal.LocalProfileManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class CategoryEmptyErrorTest {
    private Context context;
    private LocalProfileManager profiles;
    private String originalProfileId;
    private String testProfileId;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        GlobalPreferences.instance(context);
        profiles = LocalProfileManager.instance();
        originalProfileId = profiles.getActiveId();
        testProfileId = profiles.create("stage2-offline-message-test").getId();
        profiles.select(testProfileId);
    }

    @After
    public void tearDown() {
        if (profiles != null) {
            if (originalProfileId != null) {
                profiles.select(originalProfileId);
            }
            if (testProfileId != null) {
                profiles.delete(testProfileId);
            }
        }
    }

    @Test
    public void friendlyMessageAndActionForPermittedProfile() {
        new LocalArchiveSettings(context).setLibraryEnabledForActiveProfile(true);
        CategoryEmptyError error = new CategoryEmptyError(context, new IllegalStateException(
                new UnknownHostException("Unable to resolve host \"www.youtube.com\"")));
        assertEquals("YouTube is unavailable.\nYour local videos and saved playlists are still available.",
                error.getMessage());
        assertEquals("Open Local Library", error.getActionText());
    }

    @Test
    public void doesNotExposeLocalButtonWithoutArchivePermission() {
        new LocalArchiveSettings(context).setLibraryEnabledForActiveProfile(false);
        CategoryEmptyError error = new CategoryEmptyError(context,
                new UnknownHostException("www.youtube.com"));
        assertNull(error.getActionText());
        error.onAction();
    }

    @Test
    public void unrelatedFailuresRetainDiagnosticMessage() {
        new LocalArchiveSettings(context).setLibraryEnabledForActiveProfile(true);
        CategoryEmptyError error = new CategoryEmptyError(context,
                new IllegalStateException("SMB archive unavailable"));
        assertFalse(error.getMessage().contains("YouTube is unavailable."));
        assertNull(error.getActionText());
    }
}
