package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.locale.LocaleUtility;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.CategoryEmptyError;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.AutoOfflineUnavailableError;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.ErrorFragmentData;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.LocalSectionEmptyError;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.PasswordError;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.SignInError;
import com.liskovsoft.smartyoutubetv2.common.app.models.errors.YouTubeNetworkError;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.ChannelUploadsMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.SectionMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter.VideoMenuCallback;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.providers.channelgroup.ChannelGroupServiceWrapper;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.SectionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.interfaces.VideoGroupPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.local.DiagnosticTiming;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.BrowseProcessorManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.AccountChangeListener;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveConstants;
import com.liskovsoft.smartyoutubetv2.common.local.AutoOfflineState;
import com.liskovsoft.youtubeapi.service.internal.LocalModeSettings;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveChannelRows;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveChannelIdentity;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchivePlaylistIdentity;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveRepository;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveSectionContent;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveSettings;
import com.liskovsoft.youtubeapi.service.internal.LocalProfileManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.AccountsData;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

public class BrowsePresenter extends BasePresenter<BrowseView> implements SectionPresenter, VideoGroupPresenter, AccountChangeListener, AppPrefs.ProfileChangeListener {
    private static final String TAG = BrowsePresenter.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    private static BrowsePresenter sInstance;
    private final List<BrowseSection> mSections;
    private final List<BrowseSection> mErrorSections;
    private final Map<Integer, Observable<MediaGroup>> mGridMapping;
    private final Map<Integer, Observable<List<MediaGroup>>> mRowMapping;
    private final Map<Integer, Callable<List<SettingsItem>>> mSettingsGridMapping;
    private final Map<Integer, Callable<List<Video>>> mLocalGridMappings;
    private final Map<Integer, BrowseSection> mSectionsMapping;
    private final AppDataSourceManager mDataSourcePresenter;
    private final BrowseProcessorManager mBrowseProcessor;
    private final List<Disposable> mActions;
    private final Runnable mRefreshSection = this::refresh;
    private final Runnable mRefreshFocusedSection = this::updateCurrentSection;
    private final AutoOfflineState mAutoOfflineState = new AutoOfflineState();
    private final Runnable mNetworkProbe = this::probeYouTube;
    private final AtomicBoolean mProbeInFlight = new AtomicBoolean();
    private volatile int mProfileGeneration;
    private BrowseSection mCurrentSection;
    private Video mCurrentVideo;
    private long mLastUpdateTimeMs = -1;
    private int mBootSectionIndex;
    private int mBootstrapSectionId = -1;
    private static final long NETWORK_PROBE_INTERVAL_MS = 30_000;
    private static final long SECTION_RETRY_INTERVAL_MS = 30_000;

    private BrowsePresenter(Context context) {
        super(context);
        mDataSourcePresenter = AppDataSourceManager.instance();
        mSections = new ArrayList<>();
        mErrorSections = new ArrayList<>();
        mGridMapping = new HashMap<>();
        mRowMapping = new HashMap<>();
        mSettingsGridMapping = new HashMap<>();
        mLocalGridMappings = new HashMap<>();
        mSectionsMapping = new HashMap<>();
        MediaServiceManager.instance().addAccountListener(this);
        AppPrefs.instance(context).addListener(this);

        mBrowseProcessor = new BrowseProcessorManager(getContext(), this::syncItem);
        mActions = new ArrayList<>();

        initSectionMappings();
        updateChannelSorting();
        updatePlaylistsStyle();
    }

    public static BrowsePresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new BrowsePresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public static void unhold() {
        sInstance = null;
    }

    @Override
    public void onViewInitialized() {
        super.onViewInitialized();

        if (getView() == null) {
            return;
        }

        updateSections();

        // Move default focus
        int selectedSectionIndex = findSectionIndex(mCurrentSection != null ? mCurrentSection.getId() : mBootstrapSectionId);
        mBootstrapSectionId = -1;
        getView().selectSection(selectedSectionIndex != -1 ? selectedSectionIndex : mBootSectionIndex, true);
    }

    @Override
    public void onViewPaused() {
        super.onViewPaused();

        saveSelectedItems();
        Utils.removeCallbacks(mNetworkProbe);
    }

    @Override
    public void onViewResumed() {
        super.onViewResumed();

        refreshIfNeeded();
        updateAutoNetworkState();
    }

    private void refreshIfNeeded() {
        if (LocalModeSettings.isLocalOnly() || getView() == null || !isHomeSection() || mLastUpdateTimeMs == -1 || System.currentTimeMillis() - mLastUpdateTimeMs < 3 * 60 * 60 * 1_000) return;
        Utils.testUrl("https://www.youtube.com", () -> refresh(false));
    }

    private void updateAutoNetworkState() {
        if (LocalModeSettings.isLocalOnly() || getView() == null) {
            Utils.removeCallbacks(mNetworkProbe);
            return;
        }
        long now = System.currentTimeMillis();
        if (hasValidatedInternet()) {
            if (mAutoOfflineState.onProbeSuccess()) updateCurrentSectionForAvailabilityChange();
        } else {
            boolean changed = mAutoOfflineState.onConnectivityUnvalidated(now);
            changed |= mAutoOfflineState.expireGrace(now);
            if (changed) updateCurrentSectionForAvailabilityChange();
        }
        probeYouTube();
        scheduleNetworkProbe();
    }

    private boolean hasValidatedInternet() {
        ConnectivityManager manager = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return true; // Fall back to actual YouTube request results.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network != null ? manager.getNetworkCapabilities(network) : null;
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        NetworkInfo info = manager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void probeYouTube() {
        if (LocalModeSettings.isLocalOnly() || getView() == null || !mProbeInFlight.compareAndSet(false, true)) return;
        int profileGeneration = mProfileGeneration;
        Utils.testUrlGet("https://www.youtube.com/generate_204", () -> {
            if (profileGeneration != mProfileGeneration) return;
            mProbeInFlight.set(false);
            if (LocalModeSettings.isLocalOnly()) return;
            boolean restored = mAutoOfflineState.onProbeSuccess();
            if (restored && mCurrentSection != null && inForeground() && !LocalModeSettings.isLocalOnly()) {
                refresh(false);
            }
            if (inForeground() && !LocalModeSettings.isLocalOnly()) {
                scheduleNetworkProbe();
            }
        }, () -> {
            if (profileGeneration != mProfileGeneration) return;
            mProbeInFlight.set(false);
            if (LocalModeSettings.isLocalOnly()) return;
            if (mAutoOfflineState.onRequestFailure()) {
                updateCurrentSectionForAvailabilityChange();
            }
            if (inForeground() && !LocalModeSettings.isLocalOnly()) {
                scheduleNetworkProbe();
            }
        });
    }

    private void scheduleNetworkProbe() {
        Utils.removeCallbacks(mNetworkProbe);
        Utils.postDelayed(mNetworkProbe, NETWORK_PROBE_INTERVAL_MS);
    }

    private void updateCurrentSectionForAvailabilityChange() {
        if (mCurrentSection == null || getView() == null) return;
        Utils.removeCallbacks(mRefreshSection);
        updateSection(mCurrentSection);
    }

    private void saveSelectedItems() {
        // Fix position reset when jumping between sections
        if (mCurrentVideo != null && mCurrentVideo.getPositionInsideGroup() == 0 && (System.currentTimeMillis() - mCurrentVideo.timestamp) < 10_000) {
            return;
        }

        if ((isSubscriptionsSection() && getGeneralData().isRememberSubscriptionsPositionEnabled()) ||
                (isPinnedSection() && getGeneralData().isRememberPinnedPositionEnabled())) {
            getGeneralData().setSelectedItem(mCurrentSection.getId(), mCurrentVideo);
        }
    }

    private void restoreSelectedItems() {
        if (getView() == null) {
            return;
        }

        if ((isSubscriptionsSection() && getGeneralData().isRememberSubscriptionsPositionEnabled()) ||
                (isPinnedSection() && getGeneralData().isRememberPinnedPositionEnabled())) {
            getView().selectSectionItem(getGeneralData().getSelectedItem(mCurrentSection.getId()));
        }
    }

    private void initSectionMappings() {
        initSectionMapping();

        initRowAndGridMapping();

        initSettingsGridMapping();
        initLocalGridMapping();
    }

    private void initSectionMapping() {
        String country = LocaleUtility.getCurrentLocale(getContext()).getCountry();
        int uploadsType = getMainUIData().isUploadsOldLookEnabled() ? BrowseSection.TYPE_GRID : BrowseSection.TYPE_MULTI_GRID;

        mSectionsMapping.put(MediaGroup.TYPE_HOME, new BrowseSection(MediaGroup.TYPE_HOME, getContext().getString(R.string.header_home), BrowseSection.TYPE_ROW, R.drawable.icon_home, false));
        mSectionsMapping.put(MediaGroup.TYPE_SHORTS, new BrowseSection(MediaGroup.TYPE_SHORTS, getContext().getString(R.string.header_shorts), BrowseSection.TYPE_SHORTS_GRID, R.drawable.icon_shorts));
        mSectionsMapping.put(MediaGroup.TYPE_TRENDING, new BrowseSection(MediaGroup.TYPE_TRENDING, getContext().getString(R.string.header_trending), BrowseSection.TYPE_ROW, R.drawable.icon_trending));
        mSectionsMapping.put(MediaGroup.TYPE_KIDS_HOME, new BrowseSection(MediaGroup.TYPE_KIDS_HOME, getContext().getString(R.string.header_kids_home), BrowseSection.TYPE_ROW, R.drawable.icon_kids_home));
        mSectionsMapping.put(MediaGroup.TYPE_SPORTS, new BrowseSection(MediaGroup.TYPE_SPORTS, getContext().getString(R.string.header_sports), BrowseSection.TYPE_ROW, R.drawable.icon_sports));
        mSectionsMapping.put(MediaGroup.TYPE_LIVE, new BrowseSection(MediaGroup.TYPE_LIVE, getContext().getString(R.string.badge_live), BrowseSection.TYPE_ROW, R.drawable.icon_live));
        mSectionsMapping.put(MediaGroup.TYPE_MY_VIDEOS, new BrowseSection(MediaGroup.TYPE_MY_VIDEOS, getContext().getString(R.string.my_videos), BrowseSection.TYPE_GRID, R.drawable.icon_playlist));
        mSectionsMapping.put(MediaGroup.TYPE_GAMING, new BrowseSection(MediaGroup.TYPE_GAMING, getContext().getString(R.string.header_gaming), BrowseSection.TYPE_ROW, R.drawable.icon_gaming));
        if (!Helpers.equalsAny(country, "RU", "BY")) {
            mSectionsMapping.put(MediaGroup.TYPE_NEWS, new BrowseSection(MediaGroup.TYPE_NEWS, getContext().getString(R.string.header_news), BrowseSection.TYPE_ROW, R.drawable.icon_news));
        }
        mSectionsMapping.put(MediaGroup.TYPE_MUSIC, new BrowseSection(MediaGroup.TYPE_MUSIC, getContext().getString(R.string.header_music), BrowseSection.TYPE_ROW, R.drawable.icon_music));
        mSectionsMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, new BrowseSection(MediaGroup.TYPE_CHANNEL_UPLOADS, getContext().getString(R.string.header_channels), uploadsType, R.drawable.icon_channels, false));
        mSectionsMapping.put(MediaGroup.TYPE_SUBSCRIPTIONS, new BrowseSection(MediaGroup.TYPE_SUBSCRIPTIONS, getContext().getString(R.string.header_subscriptions), BrowseSection.TYPE_GRID, R.drawable.icon_subscriptions, false));
        mSectionsMapping.put(MediaGroup.TYPE_HISTORY, new BrowseSection(MediaGroup.TYPE_HISTORY, getContext().getString(R.string.header_history), BrowseSection.TYPE_GRID, R.drawable.icon_history, true));
        mSectionsMapping.put(MediaGroup.TYPE_BLOCKED_CHANNELS,
                new BrowseSection(MediaGroup.TYPE_BLOCKED_CHANNELS, getContext().getString(R.string.header_blocked_channels), BrowseSection.TYPE_GRID, R.drawable.icon_blocked_channels, false));
        mSectionsMapping.put(MediaGroup.TYPE_USER_PLAYLISTS, new BrowseSection(MediaGroup.TYPE_USER_PLAYLISTS, getContext().getString(R.string.header_playlists), BrowseSection.TYPE_ROW, R.drawable.icon_playlist, false));
        mSectionsMapping.put(MediaGroup.TYPE_NOTIFICATIONS, new BrowseSection(MediaGroup.TYPE_NOTIFICATIONS, getContext().getString(R.string.header_notifications), BrowseSection.TYPE_GRID, R.drawable.icon_notification, false));
        mSectionsMapping.put(MediaGroup.TYPE_PLAYBACK_QUEUE, new BrowseSection(MediaGroup.TYPE_PLAYBACK_QUEUE, getContext().getString(R.string.playback_queue_category_title), BrowseSection.TYPE_GRID, R.drawable.icon_queue, false));
        mSectionsMapping.put(LocalArchiveConstants.SECTION_ID, new BrowseSection(LocalArchiveConstants.SECTION_ID,
                getContext().getString(R.string.local_library), BrowseSection.TYPE_ROW, R.drawable.icon_playlist, false));

        if (getSidebarService().isSettingsSectionEnabled()) {
            mSectionsMapping.put(MediaGroup.TYPE_SETTINGS, new BrowseSection(MediaGroup.TYPE_SETTINGS, getContext().getString(R.string.header_settings), BrowseSection.TYPE_SETTINGS_GRID, R.drawable.icon_settings));
        }
    }

    private void initRowAndGridMapping() {
        mRowMapping.put(MediaGroup.TYPE_HOME, getContentService().getHomeObserve());
        mRowMapping.put(MediaGroup.TYPE_TRENDING, getContentService().getTrendingObserve());
        mRowMapping.put(MediaGroup.TYPE_KIDS_HOME, getContentService().getKidsHomeObserve());
        mRowMapping.put(MediaGroup.TYPE_SPORTS, getContentService().getSportsObserve());
        mRowMapping.put(MediaGroup.TYPE_LIVE, getContentService().getLiveObserve());
        mRowMapping.put(MediaGroup.TYPE_NEWS, getContentService().getNewsObserve());
        mRowMapping.put(MediaGroup.TYPE_MUSIC, getContentService().getMusicObserve());
        mRowMapping.put(MediaGroup.TYPE_GAMING, getContentService().getGamingObserve());
        mRowMapping.put(MediaGroup.TYPE_USER_PLAYLISTS, getContentService().getPlaylistRowsObserve());

        mGridMapping.put(MediaGroup.TYPE_SHORTS, getContentService().getShortsObserve());
        mGridMapping.put(MediaGroup.TYPE_SUBSCRIPTIONS, getContentService().getSubscriptionsObserve());
        mGridMapping.put(MediaGroup.TYPE_HISTORY, getContentService().getHistoryObserve());
        mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsByNewContentObserve());
        mGridMapping.put(MediaGroup.TYPE_NOTIFICATIONS, getNotificationsService().getNotificationItemsObserve());
        mGridMapping.put(MediaGroup.TYPE_MY_VIDEOS, getContentService().getMyVideosObserve());
    }

    private void initPinnedSections() {
        mSections.clear();

        Collection<Video> pinnedItems = getSidebarService().getPinnedItems();

        for (Video item : pinnedItems) {
            if (item != null) {
                if (item.sectionId == -1) { // pinned channel or playlist
                    BrowseSection section = createPinnedSection(item);
                    mSections.add(section);
                } else {
                    BrowseSection section = mSectionsMapping.get(item.sectionId);

                    if (section != null) {
                        mSections.add(section);
                    }
                }
            }
        }
    }

    private void initPinnedCallbacks() {
        Collection<Video> pinnedItems = getSidebarService().getPinnedItems();

        for (Video item : pinnedItems) {
            if (item != null && item.sectionId == -1) {
                createPinnedMapping(item);
            }
        }
    }

    private void initSettingsGridMapping() {
        mSettingsGridMapping.put(MediaGroup.TYPE_SETTINGS, () -> mDataSourcePresenter.getSettingItems(getContext()));
    }

    private void initLocalGridMapping() {
        mLocalGridMappings.put(MediaGroup.TYPE_PLAYBACK_QUEUE, () -> Playlist.instance().getAllReversed());
        mLocalGridMappings.put(MediaGroup.TYPE_BLOCKED_CHANNELS, this::getBlockedChannels);
        mLocalGridMappings.put(LocalArchiveConstants.SECTION_ID,
                () -> LocalArchiveRepository.instance(getContext()).getLibraryVideos());
    }

    private List<Video> getBlockedChannels() {
        BlockedChannelData blockedChannelData = BlockedChannelData.instance(getContext());
        List<Video> videos = new ArrayList<>();

        for (Pair<String, String> entry : blockedChannelData.getChannelIdsWithNames()) {
            Video video = new Video();
            video.channelId = entry.first;
            video.title = entry.second;
            videos.add(video);
        }

        return videos;
    }

    public void updateSections() {
        if (getView() == null) {
            return;
        }

        initPinnedData();

        refreshSections();
    }

    private void refreshSections() {
        if (getView() == null) {
            return;
        }

        // clean up (profile changed etc)
        getView().removeAllSections();

        int bootSectionId = getSidebarService().getBootSectionId();

        // Empty Home on first run fix. Switch to something non-empty.
        if (!getSignInService().isSigned() && VideoStateService.instance(getContext()).isEmpty()) {
            bootSectionId = MediaGroup.TYPE_MUSIC;
        }

        int index = 0;

        for (BrowseSection section : mErrorSections) {
            getView().addSection(index++, section);
        }

        for (BrowseSection section : mSections) { // contains sections and pinned items!
            if (section.getId() == MediaGroup.TYPE_SETTINGS) {
                section.setEnabled(true);
            }

            if (section.isEnabled()) {
                if (section.getId() == bootSectionId) {
                    mBootSectionIndex = index;
                }
                getView().addSection(index++, section);
            } else {
                getView().removeSection(section);
            }
        }

        // Refresh and restore last focus
        int selectedSectionIndex = findSectionIndex(mCurrentSection != null ? mCurrentSection.getId() : -1);
        getView().selectSection(selectedSectionIndex != -1 ? selectedSectionIndex : mBootSectionIndex, false);
    }

    private void initPinnedData() {
        initPinnedSections();
        initPinnedCallbacks();
        initPasswordSection();
    }

    private void sortSections() {
        // NOTE: Comparator.comparingInt API >= 24
        Collections.sort(mSections, (o1, o2) -> {
            return getSidebarService().getSectionIndex(o1.getId()) - getSidebarService().getSectionIndex(o2.getId());
        });
    }

    public void updateChannelSorting() {
        int sortingType = getMainUIData().getChannelCategorySorting();

        switch (sortingType) {
            case MainUIData.CHANNEL_SORTING_DEFAULT:
                mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsObserve());
                break;
            case MainUIData.CHANNEL_SORTING_NAME2:
            case MainUIData.CHANNEL_SORTING_NAME:
                mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsByNameObserve());
                break;
            case MainUIData.CHANNEL_SORTING_NEW_CONTENT:
                mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsByNewContentObserve());
                break;
            case MainUIData.CHANNEL_SORTING_LAST_VIEWED:
                mGridMapping.put(MediaGroup.TYPE_CHANNEL_UPLOADS, getContentService().getSubscribedChannelsByLastViewedObserve());
                break;
        }
    }

    public void updatePlaylistsStyle() {
        int playlistsStyle = getMainUIData().getPlaylistsStyle();

        switch (playlistsStyle) {
            case MainUIData.PLAYLISTS_STYLE_GRID:
                mRowMapping.remove(MediaGroup.TYPE_USER_PLAYLISTS);
                mGridMapping.put(MediaGroup.TYPE_USER_PLAYLISTS, getContentService().getPlaylistsObserve());
                updateCategoryType(MediaGroup.TYPE_USER_PLAYLISTS, BrowseSection.TYPE_GRID);
                break;
            case MainUIData.PLAYLISTS_STYLE_ROWS:
                mGridMapping.remove(MediaGroup.TYPE_USER_PLAYLISTS);
                mRowMapping.put(MediaGroup.TYPE_USER_PLAYLISTS, getContentService().getPlaylistRowsObserve());
                updateCategoryType(MediaGroup.TYPE_USER_PLAYLISTS, BrowseSection.TYPE_ROW);
                break;
        }
    }

    private void updateCategoryType(int categoryId, int categoryType) {
        if (categoryType == -1 || categoryId == -1 || mSections == null) {
            return;
        }

        BrowseSection section = mSectionsMapping.get(categoryId);

        if (section != null) {
            section.setType(categoryType);
        }

        for (BrowseSection category : mSections) {
            if (category.getId() == categoryId) {
                category.setType(categoryType);
                break;
            }
        }
    }

    @Override
    public void onViewDestroyed() {
        super.onViewDestroyed();
        Utils.removeCallbacks(mNetworkProbe);
        mProbeInFlight.set(false);
        disposeActions();
        saveSelectedItems();
    }

    @Override
    public void onVideoItemSelected(Video item) {
        if (getView() == null) {
            return;
        }

        if (belongsToChannelUploadsMultiGrid(item)) {
            if (getMainUIData().isUploadsAutoLoadEnabled()) {
                updateChannelUploadsMultiGrid(item);
            } else {
                updateChannelUploadsMultiGrid(null); // clear
            }
        }

        mCurrentVideo = item;
    }

    @Override
    public void onVideoItemClicked(Video item) {
        if (getContext() == null || item == null) {
            return;
        }

        if (LocalModeSettings.isLocalOnly() && !isLocalContent(item)) {
            MessageHelpers.showMessage(getContext(), "This YouTube item is unavailable in LOCAL ONLY mode.");
            return;
        }

        if (item.isLocalArchive || item.hasLocalArchiveCopy) {
            com.liskovsoft.smartyoutubetv2.common.local.LocalArchivePlaybackManager
                    .instance(getContext()).play(item, item.isLocalArchive || item.isLocalArchiveOnly);
            return;
        }

        // Check that channels new look enabled and we're on the first column
        if (belongsToChannelUploadsMultiGrid(item)) {
            if (getMainUIData().isUploadsAutoLoadEnabled()) {
                VideoActionPresenter.instance(getContext()).apply(item);
            } else {
                updateChannelUploadsMultiGrid(item);
            }
        } else {
            VideoActionPresenter.instance(getContext()).apply(item);
        }
    }

    private boolean isLocalContent(Video item) {
        return item != null && (item.isLocalArchive || item.isLocalArchiveOnly || item.hasLocalArchiveCopy ||
                LocalArchiveChannelIdentity.isLocalId(item.channelId) ||
                LocalArchivePlaylistIdentity.isLocalId(item.channelId));
    }

    @Override
    public void onVideoItemLongClicked(Video item) {
        if (getContext() == null) {
            return;
        }

        if (belongsToChannelUploads(item)) { // We need to be sure we exactly on Channels section
            ChannelUploadsMenuPresenter.instance(getContext()).showMenu(item, (videoItem, action) -> {
                if (action == VideoMenuCallback.ACTION_UNSUBSCRIBE) { // works with any uploads section look
                    removeItem(item);
                    if (LocalArchiveChannelIdentity.isLocalId(item.channelId)) refresh(false);
                }
            });
        } else {
            VideoMenuPresenter.instance(getContext()).showMenu(item, (videoItem, action) -> {
                if (action == VideoMenuCallback.ACTION_REMOVE ||
                    action == VideoMenuCallback.ACTION_REMOVE_FROM_PLAYLIST ||
                    (action == VideoMenuCallback.ACTION_REMOVE_FROM_QUEUE && isPlaybackQueueSection())) {
                    removeItem(videoItem);
                } else if (action == VideoMenuCallback.ACTION_UNSUBSCRIBE && isMultiGridChannelUploadsSection()) {
                    removeItem(mCurrentVideo);
                    VideoMenuPresenter.instance(getContext()).closeDialog();
                } else if (action == VideoMenuCallback.ACTION_UNSUBSCRIBE && isSubscriptionsSection()) {
                    removeItemAuthor(videoItem);
                    VideoMenuPresenter.instance(getContext()).closeDialog();
                } else if (action == VideoMenuCallback.ACTION_SUBSCRIBE &&
                        mCurrentSection != null && mCurrentSection.getId() == MediaGroup.TYPE_CHANNEL_UPLOADS) {
                    refresh(false);
                } else if (action == VideoMenuCallback.ACTION_REMOVE_AUTHOR) {
                    removeItemAuthor(videoItem);
                }
            });
        }
    }

    @Override
    public void onScrollEnd(Video item) {
        if (item == null) {
            Log.e(TAG, "Can't scroll. Video is null.");
            return;
        }

        VideoGroup group = item.getGroup();

        continueGroup(group);
    }

    @Override
    public void onSectionFocused(int sectionId) {
        DiagnosticTiming.event(null, "presenter.section_focused", "sectionId=" + sectionId);
        disposeActions();
        saveSelectedItems(); // save previous state
        mCurrentSection = findSectionById(sectionId);
        mCurrentVideo = null; // fast scroll through the sections (fix empty selected item)
        restoreSelectedItems(); // Don't place anywhere else

        // Header focus changes immediately. Defer content loading until the last selected
        // section settles so rapid sidebar navigation does not rebuild every intermediate page.
        Utils.removeCallbacks(mRefreshFocusedSection);
        Utils.postDelayed(mRefreshFocusedSection, 250);
    }

    @Override
    public void onSectionLongPressed(int sectionId) {
        SectionMenuPresenter.instance(getContext()).showMenu(findSectionById(sectionId));
    }

    @Override
    public boolean hasPendingActions() {
        return RxHelper.isAnyActionRunning(mActions);
    }

    public boolean isItemPinned(Video item) {
        Collection<Video> items = getSidebarService().getPinnedItems();

        return items.contains(item);
    }

    public void moveSectionUp(BrowseSection section) {
        mCurrentSection = section; // move current focus
        getSidebarService().moveSectionUp(section.getId());
        updateSections();
    }

    public void moveSectionDown(BrowseSection section) {
        mCurrentSection = section; // move current focus
        getSidebarService().moveSectionDown(section.getId());
        updateSections();
    }

    public void renameSection(BrowseSection section) {
        mCurrentSection = section; // move current focus
        getSidebarService().renameSection(section.getId(), section.getTitle());
        updateSections();
    }

    public void renameSection(Video section) {
        getSidebarService().renameSection(section.getId(), section.getTitle());
        updateSections();
    }

    public void enableAllSections(boolean enable) {
        enableSection(MediaGroup.TYPE_HISTORY, enable);
        enableSection(MediaGroup.TYPE_USER_PLAYLISTS, enable);
        enableSection(MediaGroup.TYPE_SUBSCRIPTIONS, enable);
        enableSection(MediaGroup.TYPE_CHANNEL_UPLOADS, enable);
        enableSection(MediaGroup.TYPE_GAMING, enable);
        enableSection(MediaGroup.TYPE_MUSIC, enable);
        enableSection(MediaGroup.TYPE_NEWS, enable);
        enableSection(MediaGroup.TYPE_HOME, enable);
        enableSection(MediaGroup.TYPE_TRENDING, enable);
        enableSection(MediaGroup.TYPE_SHORTS, enable);
    }

    public void enableSection(int sectionId, boolean enable) {
        getSidebarService().enableSection(sectionId, enable);

        if (!enable && mCurrentSection != null && mCurrentSection.getId() == sectionId) {
            mCurrentSection = findNearestSection(sectionId);
        }

        updateSections();
    }

    public void pinItem(Video item) {
        if (getView() == null) {
            return;
        }

        int idx = getSidebarService().addPinnedItem(item);

        createPinnedMapping(item);

        BrowseSection newSection = createPinnedSection(item);
        if (!mSections.contains(newSection)) {
            if (idx != -1) {
                mSections.add(idx, newSection);
            } else {
                mSections.add(newSection);
            }
        }
        getView().addSection(idx, newSection);
    }

    public void pinItem(String title, int resId, ErrorFragmentData data) {
        if (getView() == null) {
            return;
        }

        BrowseSection newSection = new BrowseSection(title.hashCode(), title, BrowseSection.TYPE_ERROR, resId, false, data);
        Helpers.removeIf(mErrorSections, section -> section.getId() == newSection.getId());
        mErrorSections.add(newSection);
        getView().addSection(0, newSection);
    }

    private void appendToSections(String title, int resId, ErrorFragmentData data) {
        int id = title.hashCode();
        Helpers.removeIf(mSections, section -> section.getId() == id);
        mSections.add(new BrowseSection(id, title, BrowseSection.TYPE_ERROR, resId, false, data));
    }

    public void unpinItem(Video item) {
        getSidebarService().removePinnedItem(item);
        getGeneralData().removeSelectedItem(item.getId());

        BrowseSection section = null;

        for (BrowseSection cat : mSections) {
            if (cat.getId() == item.getId()) {
                section = cat;
                break;
            }
        }

        mGridMapping.remove(item.getId());

        if (getView() != null) {
            getView().removeSection(section);
        }
    }

    public void refresh() {
        refresh(true);
    }

    public void refresh(boolean focusOnContent) {
        if (isSubscriptionsSection()) {
            getContentService().forceRefreshSubscriptions();
        }
        updateCurrentSection();
        if (focusOnContent && getView() != null) {
            getView().focusOnContent();
        }
    }

    private void updateRefreshTime() {
        mLastUpdateTimeMs = System.currentTimeMillis();
    }

    private void updateCurrentSection() {
        disposeActions();

        if (getView() == null || mCurrentSection == null) {
            return;
        }

        long startedAt = DiagnosticTiming.nowNanos();
        DiagnosticTiming.event(null, "presenter.update_section.begin", "title=" + mCurrentSection.getTitle());
        try {
            Log.d(TAG, "Update section %s", mCurrentSection.getTitle());
            updateSection(mCurrentSection);
        } finally {
            DiagnosticTiming.event(null, "presenter.update_section.end", "durationNs=" + (DiagnosticTiming.nowNanos() - startedAt));
        }
    }

    private void updateSection(BrowseSection section) {
        if (LocalModeSettings.isLocalOnly()) {
            // Cancel any AUTO-mode requests before rendering local content or an unavailable state.
            disposeActions();
            if (updateLocalOnlySection(section)) return;
            if (isRemoteSection(section)) {
                showLocalOnlyUnavailable(section);
                return;
            }
        }
        if (mAutoOfflineState.shouldUseLocalContent()) {
            disposeActions();
            if (updateLocalOnlySection(section)) return;
            if (isRemoteSection(section)) {
                showAutoOfflineUnavailable(section);
                return;
            }
        }
        switch (section.getType()) {
            case BrowseSection.TYPE_GRID:
            case BrowseSection.TYPE_SHORTS_GRID:
                if (mGridMapping.containsKey(section.getId())) {
                    Observable<MediaGroup> group = mGridMapping.get(section.getId());
                    updateVideoGrid(section, group, section.isAuthOnly());
                } else if (mLocalGridMappings.containsKey(section.getId())) {
                    Callable<List<Video>> localVideos = mLocalGridMappings.get(section.getId());
                    updateLocalGrid(section, localVideos);
                }
                break;
            case BrowseSection.TYPE_ROW:
                if (section.getId() == LocalArchiveConstants.SECTION_ID) {
                    updateLocalGrid(section, mLocalGridMappings.get(section.getId()));
                } else {
                    Observable<List<MediaGroup>> groups = mRowMapping.get(section.getId());
                    updateVideoRows(section, groups, section.isAuthOnly());
                }
                break;
            case BrowseSection.TYPE_SETTINGS_GRID:
                Callable<List<SettingsItem>> items = mSettingsGridMapping.get(section.getId());
                updateSettingsGrid(section, items);
                break;
            case BrowseSection.TYPE_MULTI_GRID:
                Observable<MediaGroup> group2 = mGridMapping.get(section.getId());
                updateVideoGrid(section, group2, 0, section.isAuthOnly());
                break;
            case BrowseSection.TYPE_ERROR:
                getView().showProgressBar(false);
                break;
        }

        updateRefreshTime();
    }

    private boolean updateLocalOnlySection(BrowseSection section) {
        if (section == null) return false;
        int id = section.getId();
        if (id == MediaGroup.TYPE_NEWS) return false;
        if (id != MediaGroup.TYPE_HOME && id != MediaGroup.TYPE_SUBSCRIPTIONS &&
                id != MediaGroup.TYPE_CHANNEL_UPLOADS && id != MediaGroup.TYPE_USER_PLAYLISTS &&
                id != MediaGroup.TYPE_HISTORY) return false;

        if (!new LocalArchiveSettings(getContext()).isLibraryEnabledForActiveProfile()) {
            showLocalEmptyState(section, "Local Library is disabled for this profile.", "Enable it in Settings > Local Library.");
            return true;
        }

        LocalArchiveRepository repository = LocalArchiveRepository.instance(getContext());
        String profileId = LocalProfileManager.instance().getActiveId();
        if (repository.isScanned()) {
            showLocalOnlyContent(section, repository.getLibraryVideos(), profileId);
        } else {
            getView().showProgressBar(true);
            repository.scanAsync(new LocalArchiveRepository.Callback<List<Video>>() {
                @Override
                public void onSuccess(List<Video> videos) {
                    if (!isCurrentLocalRequest(section, profileId)) return;
                    showLocalOnlyContent(section, videos, profileId);
                }

                @Override
                public void onError(String message) {
                    if (!isCurrentLocalRequest(section, profileId)) return;
                    showLocalEmptyState(section, "Local archive is unavailable.", message);
                }
            });
        }
        return true;
    }

    private boolean isCurrentLocalRequest(BrowseSection section, String profileId) {
        return getView() != null && mCurrentSection != null && mCurrentSection.getId() == section.getId() &&
                (LocalModeSettings.isLocalOnly() || mAutoOfflineState.shouldUseLocalContent()) &&
                profileId.equals(LocalProfileManager.instance().getActiveId());
    }

    private void showLocalOnlyContent(BrowseSection section, List<Video> scannedVideos, String profileId) {
        if (!isCurrentLocalRequest(section, profileId)) return;
        long startedAt = DiagnosticTiming.nowNanos();
        DiagnosticTiming.event(null, "section.data_prepare.begin", "title=" + section.getTitle() +
                " videos=" + (scannedVideos == null ? 0 : scannedVideos.size()));
        List<Video> videos = new ArrayList<>();
        if (scannedVideos != null) {
            for (Video video : scannedVideos) {
                if (video != null && video.hasLocalArchiveCopy) videos.add(video);
            }
        }
        switch (section.getId()) {
            case MediaGroup.TYPE_HOME:
                updateLocalHome(section, videos);
                break;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                updateLocalSubscriptions(section, videos);
                break;
            case MediaGroup.TYPE_CHANNEL_UPLOADS:
                updateLocalSubscribedChannels(section, videos);
                break;
            case MediaGroup.TYPE_USER_PLAYLISTS:
                updateLocalPlaylists(section);
                break;
            case MediaGroup.TYPE_HISTORY:
                updateLocalHistory(section, videos);
                break;
        }
        DiagnosticTiming.event(null, "section.data_prepare.end", "title=" + section.getTitle() +
                " durationNs=" + (DiagnosticTiming.nowNanos() - startedAt) + " videos=" + videos.size());
    }

    private Set<String> getLocalSubscribedChannelIds(List<Video> videos) {
        ChannelGroupServiceWrapper subscriptions = ChannelGroupServiceWrapper.instance(getContext());
        return LocalArchiveSectionContent.localSubscribedChannelIds(videos, subscriptions::isSubscribed);
    }

    private void updateLocalSubscriptions(BrowseSection section, List<Video> videos) {
        List<Video> subscribed = LocalArchiveSectionContent.subscribedVideos(videos, getLocalSubscribedChannelIds(videos));
        if (subscribed.isEmpty()) {
            showLocalEmptyState(section, "No archived videos from subscribed channels.", null);
            return;
        }
        updateLocalGridItems(section, subscribed, "Local Subscriptions");
    }

    private void updateLocalSubscribedChannels(BrowseSection section, List<Video> videos) {
        List<Video> channels = LocalArchiveSectionContent.subscribedChannels(videos, getLocalSubscribedChannelIds(videos));
        if (channels.isEmpty()) {
            showLocalEmptyState(section, "No archived channels are subscribed.", null);
            return;
        }
        updateLocalGridItems(section, channels, "Local Channels");
    }

    private void updateLocalPlaylists(BrowseSection section) {
        List<Video> playlists = LocalArchiveRepository.instance(getContext()).getLibraryPlaylistVideos();
        if (playlists.isEmpty()) {
            showLocalEmptyState(section, "No local playlists are available.", null);
            return;
        }
        updateLocalGridItems(section, playlists, "Local Playlists");
    }

    private void updateLocalHistory(BrowseSection section, List<Video> archiveVideos) {
        Map<String, Video> archiveById = new HashMap<>();
        for (Video video : archiveVideos) archiveById.put(video.videoId, video);
        List<Video> history = new ArrayList<>();
        List<State> states = VideoStateService.instance(getContext()).getStates();
        for (int i = states.size() - 1; i >= 0; i--) {
            State state = states.get(i);
            if (state == null || state.video == null) continue;
            Video video = archiveById.get(state.video.videoId);
            if (video != null) {
                video.sync(state);
                history.add(video);
            }
        }
        if (history.isEmpty()) {
            showLocalEmptyState(section, "No local watch history exists.", "Videos watched from the archive will appear here.");
            return;
        }
        updateLocalGridItems(section, history, "Local History");
    }

    private void updateLocalHome(BrowseSection section, List<Video> videos) {
        List<VideoGroup> rows = new ArrayList<>();
        List<Video> recommended = LocalArchiveRepository.instance(getContext()).getRecommendedVideos();
        if (!recommended.isEmpty()) {
            VideoGroup row = VideoGroup.from(recommended, section);
            row.setId("local-home-recommended-today".hashCode());
            row.setTitle("[LOCAL] Recommended Today");
            rows.add(row);
        }

        List<LocalArchiveChannelRows.Row<Video>> channels = LocalArchiveChannelRows.group(videos,
                new LocalArchiveChannelRows.Metadata<Video>() {
                    @Override public String channelId(Video video) { return video.channelId; }
                    @Override public String channelName(Video video) { return video.author; }
                });
        for (LocalArchiveChannelRows.Row<Video> channel : channels) {
            VideoGroup row = VideoGroup.from(channel.videos, section);
            row.setId(channel.id);
            row.setTitle(channel.title);
            rows.add(row);
        }

        List<Video> playlists = LocalArchiveRepository.instance(getContext()).getLibraryPlaylistVideos();
        if (!playlists.isEmpty()) {
            VideoGroup row = VideoGroup.from(playlists, section);
            row.setId("local-home-playlists".hashCode());
            row.setTitle("Local Playlists");
            rows.add(row);
        }
        if (!videos.isEmpty()) {
            VideoGroup row = VideoGroup.from(videos, section);
            row.setId("local-home-videos".hashCode());
            row.setTitle("[LOCAL] Archived videos");
            rows.add(row);
        }
        getView().updateSectionRowsOnNextFrames(rows);
        if (videos.isEmpty() && playlists.isEmpty()) {
            showLocalEmptyState(section, "No local archive content is available.", null);
        } else {
            getView().showProgressBar(false);
        }
    }

    private void updateLocalGridItems(BrowseSection section, List<Video> items, String title) {
        VideoGroup group = VideoGroup.from(items, section, section.getType() == BrowseSection.TYPE_MULTI_GRID ? 0 : -1);
        group.setAction(VideoGroup.ACTION_REPLACE);
        group.setId(section.getId());
        group.setTitle(title);
        getView().updateSection(group);
        getView().showProgressBar(false);
    }

    private void showLocalEmptyState(BrowseSection section, String title, String subtitle) {
        if (getView() == null) return;
        VideoGroup clear = VideoGroup.from(section);
        clear.setAction(VideoGroup.ACTION_REPLACE);
        clear.setId(section.getId());
        clear.setTitle(section.getTitle());
        getView().updateSection(clear);
        boolean libraryEnabled = new LocalArchiveSettings(getContext()).isLibraryEnabledForActiveProfile();
        boolean history = section.getId() == MediaGroup.TYPE_HISTORY;
        String message = !libraryEnabled
                ? "Local Library is disabled for this profile."
                : subtitle == null || subtitle.isEmpty() ? title : title + "\n" + subtitle;
        String actionText = null;
        Runnable action = null;
        if (!libraryEnabled) {
            actionText = getContext().getString(R.string.open_settings);
            action = () -> selectSection(MediaGroup.TYPE_SETTINGS);
        } else if (!history) {
            actionText = getContext().getString(R.string.open_local_library);
            action = () -> selectSection(LocalArchiveConstants.SECTION_ID);
        }
        getView().showError(new LocalSectionEmptyError(message, actionText, action));
        getView().showProgressBar(false);
    }

    private void showLocalOnlyUnavailable(BrowseSection section) {
        if (getView() == null) return;
        VideoGroup clear = VideoGroup.from(section);
        clear.setAction(VideoGroup.ACTION_REPLACE);
        clear.setId(section.getId());
        clear.setTitle(section.getTitle());
        getView().updateSection(clear);
        getView().showError(AutoOfflineUnavailableError.forLocalOnly(
                getContext().getString(R.string.open_local_library),
                () -> selectSection(LocalArchiveConstants.SECTION_ID)));
        getView().showProgressBar(false);
    }

    private void showAutoOfflineUnavailable(BrowseSection section) {
        if (getView() == null) return;
        VideoGroup clear = VideoGroup.from(section);
        clear.setAction(VideoGroup.ACTION_REPLACE);
        clear.setId(section.getId());
        clear.setTitle(section.getTitle());
        getView().updateSection(clear);
        boolean libraryEnabled = new LocalArchiveSettings(getContext()).isLibraryEnabledForActiveProfile();
        String message = "YouTube is unavailable while offline.";
        String actionText = null;
        Runnable action = null;
        if (section.getId() == MediaGroup.TYPE_NEWS) {
            if (libraryEnabled) {
                actionText = getContext().getString(R.string.open_local_library);
                action = () -> selectSection(LocalArchiveConstants.SECTION_ID);
            } else {
                message += "\nLocal Library is disabled.";
                actionText = getContext().getString(R.string.open_settings);
                action = () -> selectSection(MediaGroup.TYPE_SETTINGS);
            }
        }
        getView().showError(new AutoOfflineUnavailableError(message, actionText, action));
        getView().showProgressBar(false);
    }

    private boolean isRemoteSection(BrowseSection section) {
        if (section == null || section.getType() == BrowseSection.TYPE_SETTINGS_GRID ||
                section.getId() == LocalArchiveConstants.SECTION_ID ||
                mLocalGridMappings.containsKey(section.getId())) {
            return false;
        }
        return section.getType() == BrowseSection.TYPE_ROW ||
                section.getType() == BrowseSection.TYPE_GRID ||
                section.getType() == BrowseSection.TYPE_SHORTS_GRID ||
                section.getType() == BrowseSection.TYPE_MULTI_GRID;
    }

    private void updateSettingsGrid(BrowseSection section, Callable<List<SettingsItem>> items) {
        getView().updateSection(SettingsGroup.from(Helpers.get(items), section));
        getView().showProgressBar(false);
    }

    private void updateLocalGrid(BrowseSection section, Callable<List<Video>> items) {
        if (section.getId() == LocalArchiveConstants.SECTION_ID) {
            if (!new LocalArchiveSettings(getContext()).isLibraryEnabledForActiveProfile()) {
                updateLocalGridContent(section, Collections.emptyList());
                return;
            }
            LocalArchiveRepository repository = LocalArchiveRepository.instance(getContext());
            if (!repository.isScanned()) {
                getView().showProgressBar(true);
                repository.scanAsync(new LocalArchiveRepository.Callback<List<Video>>() {
                    @Override
                    public void onSuccess(List<Video> videos) {
                        if (getView() == null || mCurrentSection == null ||
                                mCurrentSection.getId() != LocalArchiveConstants.SECTION_ID) return;
                        updateLocalGridContent(section, videos);
                    }

                    @Override
                    public void onError(String message) {
                        if (getView() != null && mCurrentSection != null &&
                                mCurrentSection.getId() == LocalArchiveConstants.SECTION_ID) {
                            updateLocalGridContent(section, Collections.emptyList());
                            MessageHelpers.showMessage(getContext(), message);
                        }
                    }
                });
                return;
            }
        }
        updateLocalGridContent(section, Helpers.get(items));
    }

    private void updateLocalGridContent(BrowseSection section, List<Video> videos) {
        if (section.getType() == BrowseSection.TYPE_ROW) {
            updateLocalChannelRows(section, videos);
            return;
        }
        List<Video> localItems = new ArrayList<>(videos);
        localItems.addAll(LocalArchiveRepository.instance(getContext()).getLibraryPlaylistVideos());
        VideoGroup videoGroup = VideoGroup.from(localItems, section);
        videoGroup.setAction(VideoGroup.ACTION_REPLACE);
        videoGroup.setId(videoGroup.hashCode());
        videoGroup.setTitle(section.getTitle());
        getView().updateSection(videoGroup);
        getView().showProgressBar(false);
    }

    /** Display one local row per canonical YouTube channel ID, without a network lookup. */
    private void updateLocalChannelRows(BrowseSection section, List<Video> videos) {
        long startedAt = DiagnosticTiming.nowNanos();
        DiagnosticTiming.event(null, "section.channel_group.begin", "videos=" + (videos == null ? 0 : videos.size()));
        VideoGroup clear = VideoGroup.from(section);
        clear.setAction(VideoGroup.ACTION_REPLACE);
        getView().updateSection(clear);

        List<LocalArchiveChannelRows.Row<Video>> channels = LocalArchiveChannelRows.group(videos,
                new LocalArchiveChannelRows.Metadata<Video>() {
                    @Override
                    public String channelId(Video video) {
                        return video.channelId;
                    }

                    @Override
                    public String channelName(Video video) {
                        return video.author;
                    }
                });
        DiagnosticTiming.event(null, "section.channel_group.end", "durationNs=" + (DiagnosticTiming.nowNanos() - startedAt) +
                " rows=" + channels.size());

        for (LocalArchiveChannelRows.Row<Video> channel : channels) {
            VideoGroup row = VideoGroup.from(channel.videos, section);
            row.setId(channel.id);
            row.setTitle(channel.title);
            getView().updateSection(row);
        }
        List<Video> playlists = LocalArchiveRepository.instance(getContext()).getLibraryPlaylistVideos();
        if (!playlists.isEmpty()) {
            VideoGroup row = VideoGroup.from(playlists, section);
            row.setId(("local-playlists:" + playlists.get(0).author).hashCode());
            row.setTitle("[LOCAL] " + playlists.get(0).author + " playlists");
            getView().updateSection(row);
        }
        getView().showProgressBar(false);
    }

    private void updateVideoRows(BrowseSection section, Observable<List<MediaGroup>> groups, boolean authCheck) {
        Log.d(TAG, "loadRowsHeader: Start loading section: " + section.getTitle());
        String profileId = LocalProfileManager.instance().getActiveId();
        authCheck(authCheck, () -> {
            if (shouldDiscardRemoteCallback(section, profileId, false)) return;
            updateVideoRows(section, groups, profileId);
        });
    }

    private void updateVideoGrid(BrowseSection section, Observable<MediaGroup> group, boolean authCheck) {
        updateVideoGrid(section, group, -1, authCheck);
    }

    private void updateVideoGrid(BrowseSection section, Observable<MediaGroup> group, int column, boolean authCheck) {
        Log.d(TAG, "loadMultiGridHeader: Start loading section: " + section.getTitle());
        String profileId = LocalProfileManager.instance().getActiveId();
        authCheck(authCheck, () -> {
            if (shouldDiscardRemoteCallback(section, profileId, false)) return;
            updateVideoGrid(section, group, column, profileId, false);
        });
    }

    private void updateVideoRows(BrowseSection section, Observable<List<MediaGroup>> groups, String profileId) {
        Log.d(TAG, "updateRowsHeader: Start loading section: " + section.getTitle());

        disposeActions();

        if (getView() == null) {
            Log.e(TAG, "Browse view has been unloaded from the memory. Low RAM?");
            getViewManager().startView(BrowseView.class);
            return;
        }
        
        getView().showProgressBar(true);

        VideoGroup firstGroup = VideoGroup.from(section);
        firstGroup.setAction(VideoGroup.ACTION_REPLACE);
        getView().updateSection(firstGroup);

        if (groups == null) {
            // No group. Maybe just clear.
            getView().showProgressBar(false);
            return;
        }

        Disposable updateAction = groups
                .subscribe(
                        mediaGroups -> {
                            onYouTubeContentAvailable(profileId, false);
                            if (shouldDiscardRemoteCallback(section, profileId, false)) return;
                            getView().showProgressBar(false);

                            filterHomeIfNeeded(mediaGroups);

                            for (MediaGroup mediaGroup : mediaGroups) {
                                if (mediaGroup.isEmpty()) {
                                    Log.e(TAG, "loadRowsHeader: MediaGroup is empty. Group Name: " + mediaGroup.getTitle());
                                    continue;
                                }

                                VideoGroup videoGroup = VideoGroup.from(mediaGroup, section);
                                for (Video video : videoGroup.getVideos()) {
                                    LocalArchiveRepository.instance(getContext()).attachLocalCopy(video);
                                }

                                if (TextUtils.isEmpty(videoGroup.getTitle())) {
                                    videoGroup.setTitle(getContext().getString(R.string.suggestions));
                                }

                                getView().updateSection(videoGroup);
                                mBrowseProcessor.process(videoGroup);

                                continueGroupIfNeeded(videoGroup, false);
                            }
                        },
                        error -> {
                            if (handleYouTubeRequestFailure(section, profileId, false, error)) return;
                            if (shouldDiscardRemoteCallback(section, profileId, false)) return;
                            Log.e(TAG, "updateRowsHeader error: %s", error.getMessage());
                            handleLoadError(error);
                        }, () -> {
                            if (shouldDiscardRemoteCallback(section, profileId, false)) return;
                            handleLoadError(null);
                        });

        mActions.add(updateAction);
    }

    private void updateVideoGrid(BrowseSection section, Observable<MediaGroup> group, int column,
                                 String profileId, boolean localSource) {
        disposeActions();

        // authCheck may defer this callback until after the profile mode changes.
        if (!profileId.equals(LocalProfileManager.instance().getActiveId())) {
            if (mCurrentSection != null && mCurrentSection.getId() == section.getId()) updateSection(mCurrentSection);
            return;
        }
        if (!localSource && LocalModeSettings.isLocalOnly() && isRemoteSection(section)) {
            showLocalOnlyUnavailable(section);
            return;
        }

        if (getView() == null) {
            Log.e(TAG, "Browse view has been unloaded from the memory. Low RAM?");
            getViewManager().startView(BrowseView.class);
            return;
        }

        Log.d(TAG, "updateGridHeader: Start loading section: " + section.getTitle());

        getView().showProgressBar(true);

        // Stay on the same group in case of multiple subscribe calls
        VideoGroup baseGroup = VideoGroup.from(section, column);
        baseGroup.setAction(VideoGroup.ACTION_REPLACE);
        boolean keepCachedSubscriptions = section.getId() == MediaGroup.TYPE_SUBSCRIPTIONS
                && getContentService().hasCachedSubscriptions();
        if (!keepCachedSubscriptions) {
            getView().updateSection(baseGroup);
        }

        if (group == null) {
            // No group. Maybe just clear.
            getView().showProgressBar(false);
            return;
        }

        Disposable updateAction = group
                .subscribe(
                        mediaGroup -> {
                            onYouTubeContentAvailable(profileId, localSource);
                            if (shouldDiscardRemoteCallback(section, profileId, localSource)) return;
                            getView().showProgressBar(false);

                            if (getView() == null) {
                                Log.e(TAG, "Browse view has been unloaded from the memory. Low RAM?");
                                getViewManager().startView(BrowseView.class);
                                return;
                            }

                            VideoGroup videoGroup = VideoGroup.from(baseGroup, mediaGroup);
                            for (Video video : videoGroup.getVideos()) {
                                LocalArchiveRepository.instance(getContext()).attachLocalCopy(video);
                            }
                            appendLocalHistory(videoGroup);
                            getView().updateSection(videoGroup);
                            mBrowseProcessor.process(videoGroup);

                            continueGroupIfNeeded(videoGroup);
                        },
                        error -> {
                            if (handleYouTubeRequestFailure(section, profileId, localSource, error)) return;
                            if (shouldDiscardRemoteCallback(section, profileId, localSource)) return;
                            Log.e(TAG, "updateGridHeader error: %s", error.getMessage());
                            if (LocalModeSettings.isLocalOnly() && mCurrentSection != null &&
                                    mCurrentSection.getId() == section.getId()) {
                                showLocalOnlyUnavailable(section);
                            } else {
                                handleLoadError(error);
                            }
                        }, () -> {
                            if (shouldDiscardRemoteCallback(section, profileId, localSource)) return;
                            handleLoadError(null);
                        });

        mActions.add(updateAction);
    }

    private void continueGroup(VideoGroup group) {
        continueGroup(group, true);
    }

    private void continueGroup(VideoGroup group, boolean showLoading) {
        if (!mAutoOfflineState.shouldContinueRemote(LocalModeSettings.isLocalOnly())) {
            Log.d(TAG, "continueGroup skipped while local/offline content is active");
            return;
        }
        if (getView() == null) {
            Log.e(TAG, "Can't continue group. The view is null.");
            return;
        }

        if (group == null) {
            Log.e(TAG, "Can't continue group. The group is null.");
            return;
        }

        if (getCurrentSection() != null && mLocalGridMappings.containsKey(getCurrentSection().getId())) {
            Log.d(TAG, "Local grid section doesn't assume a continuation...");
            return;
        }

        Log.d(TAG, "continueGroup: start continue group: " + group.getTitle());

        // Small amount of items == small load time. Loading bar are useless?
        if (showLoading) {
            getView().showProgressBar(true);
        }

        MediaGroup mediaGroup = group.getMediaGroup();
        if (mediaGroup == null) return;
        String profileId = LocalProfileManager.instance().getActiveId();
        BrowseSection section = group.getSection();

        Observable<MediaGroup> continuation;

        //if (mediaGroup.getType() == MediaGroup.TYPE_SUGGESTIONS) { // Pinned playlist
        //    continuation = mItemService.continueGroupObserve(mediaGroup);
        //} else {
        //    continuation = getContentService().continueGroupObserve(mediaGroup);
        //}

        continuation = getContentService().continueGroupObserve(mediaGroup);

        Disposable continueAction = continuation
                .subscribe(
                        continueGroup -> {
                            onYouTubeContentAvailable(profileId, false);
                            if (section != null && shouldDiscardRemoteCallback(section, profileId, false)) return;
                            getView().showProgressBar(false);

                            VideoGroup videoGroup = VideoGroup.from(group, continueGroup);
                            getView().updateSection(videoGroup);
                            mBrowseProcessor.process(videoGroup);

                            continueGroupIfNeeded(videoGroup, showLoading);
                        },
                        error -> {
                            if (section != null && handleYouTubeRequestFailure(section, profileId, false, error)) return;
                            if (section != null && shouldDiscardRemoteCallback(section, profileId, false)) return;
                            Log.e(TAG, "continueGroup error: %s", error.getMessage());
                            if (getView() != null) {
                                getView().showProgressBar(false);
                            }
                        },
                        () -> {
                            if (section != null && shouldDiscardRemoteCallback(section, profileId, false)) return;
                            if (getView() != null) {
                                getView().showProgressBar(false);
                            }
                        }
                );

        mActions.add(continueAction);
    }

    private void authCheck(boolean check, Runnable callback) {
        if (!check) {
            callback.run();
            return;
        }

        getView().showProgressBar(true);

        if (getSignInService().isSigned()) {
            callback.run();
        } else if (getView() != null) {
            if (isHistorySection() && !VideoStateService.instance(getContext()).isEmpty()) {
                getView().showProgressBar(false);
                VideoGroup videoGroup = VideoGroup.from(getCurrentSection());
                appendLocalHistory(videoGroup);
                getView().updateSection(videoGroup);
            } else {
                getView().showProgressBar(false);
                getView().showError(new SignInError(getContext()));
            }
        }
    }

    /**
     * Most tiny ui has 8 cards in a row or 24 in grid.
     */
    private void continueGroupIfNeeded(VideoGroup group) {
        continueGroupIfNeeded(group, true);
    }

    /**
     * Most tiny ui has 8 cards in a row or 24 in grid.
     */
    private void continueGroupIfNeeded(VideoGroup group, boolean showLoading) {
        if (LocalModeSettings.isLocalOnly() || group == null || group.getMediaGroup() == null) return;
        if (MediaServiceManager.instance().shouldContinueTheGroup(getContext(), group, isGridSection())) {
            continueGroup(group, showLoading);
        }
    }

    private void disposeActions() {
        RxHelper.disposeActions(mActions);
        Utils.removeCallbacks(mRefreshSection);
        Utils.removeCallbacks(mRefreshFocusedSection);
        mLastUpdateTimeMs = -1;
        mBrowseProcessor.dispose();
    }

    private void updateChannelUploadsMultiGrid(Video item) {
        if (mCurrentSection == null) {
            return;
        }

        boolean localSource = item != null && (LocalArchiveChannelIdentity.isLocalId(item.channelId) ||
                LocalArchivePlaylistIdentity.isLocalId(item.channelId));
        updateVideoGrid(mCurrentSection, ChannelUploadsPresenter.instance(getContext()).obtainUploadsObservable(item),
                1, false, localSource);
    }

    private void updateVideoGrid(BrowseSection section, Observable<MediaGroup> group, int column,
                                 boolean authCheck, boolean localSource) {
        String profileId = LocalProfileManager.instance().getActiveId();
        authCheck(authCheck, () -> {
            if (!profileId.equals(LocalProfileManager.instance().getActiveId())) {
                if (mCurrentSection != null && mCurrentSection.getId() == section.getId()) updateSection(mCurrentSection);
                return;
            }
            updateVideoGrid(section, group, column, profileId, localSource);
        });
    }

    private boolean shouldDiscardRemoteCallback(BrowseSection section, String profileId, boolean localSource) {
        if (!profileId.equals(LocalProfileManager.instance().getActiveId())) {
            if (mCurrentSection != null && mCurrentSection.getId() == section.getId()) updateSection(mCurrentSection);
            return true;
        }
        if (mAutoOfflineState.shouldDiscardRemoteUpdate(localSource, LocalModeSettings.isLocalOnly()) &&
                isRemoteSection(section)) {
            if (mCurrentSection != null && mCurrentSection.getId() == section.getId()) updateSection(section);
            return true;
        }
        return getView() == null;
    }

    private void onYouTubeContentAvailable(String profileId, boolean localSource) {
        if (localSource || LocalModeSettings.isLocalOnly() ||
                !profileId.equals(LocalProfileManager.instance().getActiveId())) return;
        if (mAutoOfflineState.onProbeSuccess()) {
            Log.d(TAG, "Valid YouTube content response restored AUTO availability");
        }
    }

    private boolean handleYouTubeRequestFailure(BrowseSection section, String profileId, boolean localSource, Throwable error) {
        if (localSource || LocalModeSettings.isLocalOnly() || !YouTubeNetworkError.isRequestFailure(error)) return false;
        if (!profileId.equals(LocalProfileManager.instance().getActiveId())) return true;
        if (mAutoOfflineState.onRequestFailure()) {
            disposeActions();
            updateCurrentSectionForAvailabilityChange();
        }
        if (inForeground()) scheduleNetworkProbe();
        return true;
    }

    private boolean belongsToChannelUploadsMultiGrid(Video item) {
        return isMultiGridChannelUploadsSection() && belongsToChannelUploads(item);
    }

    private boolean belongsToChannelUploads(Video item) {
        return item.belongsToChannelUploads() && !item.hasVideo();
    }

    @Nullable
    public BrowseSection getCurrentSection() {
        return mCurrentSection;
    }

    private BrowseSection findSectionById(int sectionId) {
        for (BrowseSection section : mErrorSections) {
            if (section.getId() == sectionId) {
                return section;
            }
        }

        for (BrowseSection section : mSections) {
            if (section.getId() == sectionId) {
                return section;
            }
        }

        return null;
    }

    private int findSectionIndex(int sectionId) {
        if (sectionId == -1) {
            return -1;
        }

        int sectionIndex = -1;

        for (BrowseSection section : mErrorSections) {
            if (section.isEnabled()) {
                sectionIndex++;
                if (section.getId() == sectionId) {
                    return sectionIndex;
                }
            }
        }

        for (BrowseSection section : mSections) {
            if (section.isEnabled()) {
                sectionIndex++;
                if (section.getId() == sectionId) {
                    return sectionIndex;
                }
            }
        }

        return -1;
    }

    private BrowseSection findNearestSection(int sectionId) {
        BrowseSection result = findNearestSection(mErrorSections, sectionId);

        if (result == null) {
            result = findNearestSection(mSections, sectionId);
        }

        return result;
    }

    private BrowseSection findNearestSection(List<BrowseSection> sections, int sectionId) {
        BrowseSection result = null;
        BrowseSection previousSection = null;
        boolean found = false;
        for (BrowseSection section : sections) {
            if (section.getId() == sectionId) {
                found = true;
                continue;
            }
            if (section.isEnabled()) {
                if (found) {
                    result = section;
                    break;
                }
                previousSection = section;
            }
        }

        return result != null ? result : previousSection;
    }

    private void filterHomeIfNeeded(List<MediaGroup> mediaGroups) {
        if (mediaGroups == null || !isHomeSection()) {
            return;
        }

        Helpers.removeIf(mediaGroups, value -> Helpers.containsAny(
                value.getTitle(),
                "Primetime", // Free movies and shows row
                "News", // Top news
                "news", // Top news
                "NBA TV", // Sports
                "The Life of a Showgirl", // Taylor Swift ADS
                "FIFA", // Sports (FIFA World Cup 2026™)
                "FORMULA 1" // Sports (FORMULA 1 BRITISH GRAND PRIX)
        ) || Helpers.equalsAny(
                value.getTitle(),
                //getContext().getString(R.string.news_row_name),
                getContext().getString(R.string.breaking_news_row_name),
                getContext().getString(R.string.covid_news_row_name)
        ));
    }

    private int moveToTopIfNeeded(MediaGroup mediaGroup) {
        if (mediaGroup == null) {
            return -1;
        }

        return Helpers.equalsAny(mediaGroup.getTitle(), getContext().getString(R.string.trending_row_name)) ? 0 : -1;
    }

    private Observable<MediaGroup> createPinnedGridAction(Video item) {
        if (item.channelGroupId != null) {
            return getContentService().getRssFeedObserve(ChannelGroupServiceWrapper.instance(getContext()).findChannelIdsForGroup(item.channelGroupId));
        }

        return ChannelUploadsPresenter.instance(getContext()).obtainUploadsObservable(item);
    }

    private Observable<List<MediaGroup>> createPinnedRowAction(Video item) {
        return ChannelPresenter.instance(getContext()).obtainChannelObservable(item.channelId);
    }

    /**
     * Is Channels new look enabled?
     */
    public boolean isMultiGridChannelUploadsSection() {
        return mCurrentSection != null && mCurrentSection.getType() == BrowseSection.TYPE_MULTI_GRID && mCurrentSection.getId() == MediaGroup.TYPE_CHANNEL_UPLOADS;
    }

    public boolean isSettingsSection() {
        return isSection(MediaGroup.TYPE_SETTINGS);
    }

    public boolean isPlaylistsSection() {
        return isSection(MediaGroup.TYPE_USER_PLAYLISTS);
    }

    public boolean isHomeSection() {
        return isSection(MediaGroup.TYPE_HOME);
    }

    public boolean isHistorySection() {
        return isSection(MediaGroup.TYPE_HISTORY);
    }

    public boolean isSubscriptionsSection() {
        return isSection(MediaGroup.TYPE_SUBSCRIPTIONS);
    }
    
    public boolean isPlaybackQueueSection() {
        return isSection(MediaGroup.TYPE_PLAYBACK_QUEUE);
    }

    public boolean isPinnedSection() {
        return mCurrentSection != null && isPinnedId(mCurrentSection.getId());
    }

    private boolean isPinnedId(int id) {
        return id > 100;
    }

    private boolean isSection(int sectionId) {
        return mCurrentSection != null && mCurrentSection.getId() == sectionId;
    }

    public void selectSection(int sectionId) {
        getViewManager().startView(BrowseView.class); // focus view

        if (getView() == null) {
            mBootstrapSectionId = sectionId;
            return;
        }

        int sectionIndex = findSectionIndex(sectionId);

        if (sectionIndex == -1) {
            enableSection(sectionId, true);
            sectionIndex = findSectionIndex(sectionId);
            getSidebarService().enableSection(sectionId, false); // enable temporally (till restart)
        }

        if (sectionIndex != -1) {
            getView().selectSection(sectionIndex, true);
        }
    }

    public boolean inForeground() {
        return getViewManager().getTopView() == BrowseView.class;
    }

    private boolean isGridSection() {
        return mCurrentSection != null && mCurrentSection.getType() != BrowseSection.TYPE_ROW;
    }

    @Override
    public void onAccountChanged(Account account) {
        Log.d(TAG, "On account changed");

        if (getView() == null) {
            return;
        }

        initSectionMappings();
        updateChannelSorting();
        updatePlaylistsStyle();
        updateSections();
    }

    @Override
    public void onProfileChanged() {
        mProfileGeneration++;
        Utils.removeCallbacks(mNetworkProbe);
        mProbeInFlight.set(false);
        mAutoOfflineState.resetForProfile(hasValidatedInternet(), System.currentTimeMillis());
        YouTubeServiceManager.instance().invalidateCache();
        updateSections();
        updateAutoNetworkState();
    }

    public Video getCurrentVideo() {
        return mCurrentVideo;
    }

    private void initPasswordSection() {
        AccountsData accountsData = AccountsData.instance(getContext());
        if (accountsData.getAccountPassword() == null || accountsData.isPasswordAccepted()) {
            return;
        }

        mSections.clear();
        appendToSections(getContext().getString(R.string.header_notifications), R.drawable.icon_notification, new PasswordError(getContext()));
    }

    private void createPinnedMapping(Video item) {
        if (enableRows(item)) {
            mRowMapping.put(item.getId(), createPinnedRowAction(item));
        } else {
            mGridMapping.put(item.getId(), createPinnedGridAction(item));
        }
    }

    private BrowseSection createPinnedSection(Video item) {
        return new BrowseSection(
                item.getId(), item.getTitle(), enableRows(item) ? BrowseSection.TYPE_ROW : BrowseSection.TYPE_GRID, R.drawable.icon_pin, item.getCardImageUrl(), false, item);
    }

    private boolean enableRows(Video item) {
        return getMainUIData().isPinnedChannelRowsEnabled() && item.hasChannel() && !item.isPlaylistAsChannel();
    }

    private void handleLoadError(Throwable error) {
        if (getView() == null) {
            return;
        }

        getView().showProgressBar(false);

        if (getView().isEmpty() || error != null) {
            ErrorFragmentData errorFragmentData;
            if (error != null && !Helpers.containsAny(error.getMessage(), "fromNullable result is null")) {
                errorFragmentData = new CategoryEmptyError(getContext(), error);
            } else if (getSignInService().isSigned()) {
                errorFragmentData = new CategoryEmptyError(getContext(), null);
            } else {
                errorFragmentData = new SignInError(getContext());
            }

            if (error != null &&
                    com.liskovsoft.smartyoutubetv2.common.app.models.errors.YouTubeNetworkError.isYouTubeDnsFailure(error)) {
                Log.e(TAG, "YouTube connection error: %s", Utils.getStackTraceAsString(error));
            }
            getView().showError(errorFragmentData);

            if (Utils.fixRetrofitErrors(getContext(), error)) {
                return;
            }

            if (!LocalModeSettings.isLocalOnly() && !mAutoOfflineState.shouldUseLocalContent()) {
                Utils.removeCallbacks(mRefreshSection);
                Utils.postDelayed(mRefreshSection, SECTION_RETRY_INTERVAL_MS);
            }
        }
    }

    private void appendLocalHistory(VideoGroup videoGroup) {
        if (!isHistorySection()) {
            return;
        }

        VideoStateService stateService = VideoStateService.instance(getContext());

        if (stateService.isEmpty() || (!stateService.isHistoryBroken() && !videoGroup.isEmpty())) {
            return;
        }

        Video lastHistoryItem = videoGroup.isEmpty() ? null : videoGroup.get(0);
        State lastState = stateService.getLastState();

        if (lastState == null || Helpers.equals(lastHistoryItem, lastState.video)) {
            return;
        }

        for (State state : stateService.getStates()) {
            if (lastHistoryItem == null || state.timestamp > stateService.getSessionStartTimeMs()) {
                videoGroup.add(0, state.video);
            }
        }
    }
}
