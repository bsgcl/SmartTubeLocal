package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveRepository;
import com.liskovsoft.smartyoutubetv2.common.local.LocalArchiveSettings;
import com.liskovsoft.smartyoutubetv2.common.R;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** TV-friendly editor for the archive connection and active-profile access. */
public final class LocalArchiveSettingsPresenter extends BasePresenter<Void> {
    private final AtomicBoolean scanInProgress = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());

    private LocalArchiveSettingsPresenter(Context context) {
        super(context);
    }

    public static LocalArchiveSettingsPresenter instance(Context context) {
        return new LocalArchiveSettingsPresenter(context);
    }

    public void show() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(getContext(), "Local Library requires Android 6.0 or newer for encrypted SMB credentials",
                    Toast.LENGTH_LONG).show();
            return;
        }
        LocalArchiveSettings settings = new LocalArchiveSettings(getContext());
        LocalArchiveSettings.Config config = settings.getConfig();
        AppDialogPresenter menu = AppDialogPresenter.instance(getContext());
        menu.setCloseTimeoutMs(0);
        menu.appendSingleSwitch(UiOptionItem.from("Allow Local Library for this profile",
                "When enabled, this profile can browse and play all compatible videos in the archive.",
                option -> {
                    settings.setLibraryEnabledForActiveProfile(option.isSelected());
                    onSettingsSaved();
                }, settings.isLibraryEnabledForActiveProfile()));
        appendAction(menu, "SMB server: " + config.host,
                () -> edit("SMB server", config.host, false, settings::setHost));
        appendAction(menu, "Share: " + config.share,
                () -> edit("SMB share", config.share, false, settings::setShare));
        appendAction(menu, "Archive folder: " + config.archiveFolder,
                () -> edit("Archive folder", config.archiveFolder, false, settings::setArchiveFolder));
        appendAction(menu, "SMB username",
                () -> edit("SMB username", config.username, false, settings::setUsername));
        appendAction(menu, "Replace SMB password",
                () -> edit("SMB password", "", true, settings::setPassword));
        appendAction(menu, "Clear saved SMB password", () -> confirmClearPassword(settings));
        appendAction(menu, "Refresh catalogue from SMB", this::refreshCatalogue);
        appendAction(menu, "Test and scan archive", this::scan);
        menu.showDialog("Local Library settings");
    }

    private void appendAction(AppDialogPresenter menu, String title, Runnable action) {
        menu.appendSingleButton(UiOptionItem.from(title, option -> {
            menu.closeDialog();
            main.postDelayed(action, 250);
        }));
    }

    private void edit(String title, String initialValue, boolean password, ValueConsumer consumer) {
        EditText input = new EditText(getContext());
        input.setSingleLine(true);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        input.setSelectAllOnFocus(true);
        input.setText(initialValue == null ? "" : initialValue);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD :
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        AlertDialog editor = new AlertDialog.Builder(getContext(), R.style.AppDialog)
                .setTitle(title)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        editor.setOnDismissListener(ignored -> main.postDelayed(this::show, 250));
        editor.setOnShowListener(ignored -> {
            input.requestFocus();
            Button save = editor.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(view -> {
                try {
                    consumer.accept(input.getText().toString());
                } catch (IllegalArgumentException e) {
                    Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                editor.dismiss();
                onSettingsSaved();
            });
            if (editor.getWindow() != null) {
                editor.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE |
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            input.post(input::requestFocus);
        });
        editor.show();
    }

    private void confirmClearPassword(LocalArchiveSettings settings) {
        AlertDialog confirmation = new AlertDialog.Builder(getContext(), R.style.AppDialog)
                .setTitle("Clear saved SMB password?")
                .setMessage("The saved password will be removed. You can enter it again later.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Clear password", (dialog, which) -> {
                    settings.clearPassword();
                    onSettingsSaved();
                    Toast.makeText(getContext(), "Saved SMB password cleared", Toast.LENGTH_LONG).show();
                })
                .create();
        confirmation.setOnDismissListener(ignored -> main.postDelayed(this::show, 250));
        confirmation.show();
    }

    private void onSettingsSaved() {
        LocalArchiveRepository.instance(getContext()).invalidate();
        BrowsePresenter.instance(getContext()).refresh(false);
    }

    /** Explicit Stage 3 catalogue refresh: re-read pointer + library from the share,
     *  keeping the last valid cached catalogue if the refresh fails. */
    private void refreshCatalogue() {
        runScan("Refreshing catalogue...");
    }

    private void scan() {
        runScan("Testing connection and scanning archive...");
    }

    private void runScan(String progressMessage) {
        if (!new LocalArchiveSettings(getContext()).isLibraryEnabledForActiveProfile()) {
            Toast.makeText(getContext(), "Allow Local Library for this profile before scanning", Toast.LENGTH_LONG).show();
            return;
        }

        if (!scanInProgress.compareAndSet(false, true)) {
            Toast.makeText(getContext(), "An archive scan is already running", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progress = new ProgressDialog(getContext());
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.setMessage(progressMessage);
        progress.show();

        LocalArchiveRepository repository = LocalArchiveRepository.instance(getContext());
        repository.invalidate();
        repository.scanAsync(new LocalArchiveRepository.Callback<List<Video>>() {
            @Override
            public void onSuccess(List<Video> videos) {
                scanInProgress.set(false);
                if (progress.isShowing()) progress.dismiss();
                Toast.makeText(getContext(), videos.size() + " videos found in Local Library", Toast.LENGTH_LONG).show();
                BrowsePresenter.instance(getContext()).refresh(false);
                main.postDelayed(LocalArchiveSettingsPresenter.this::show, 250);
            }

            @Override
            public void onError(String message) {
                scanInProgress.set(false);
                if (progress.isShowing()) progress.dismiss();
                Toast.makeText(getContext(), "Archive scan failed: " + message, Toast.LENGTH_LONG).show();
                main.postDelayed(LocalArchiveSettingsPresenter.this::show, 250);
            }
        });
    }

    private interface ValueConsumer {
        void accept(String value);
    }
}
