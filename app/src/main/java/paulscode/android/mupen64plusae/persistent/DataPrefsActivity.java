/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * Copyright (C) 2013 Paul Lamb
 *
 * This file is part of Mupen64PlusAE.
 *
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 *
 * Authors: littleguy77
 */
package paulscode.android.mupen64plusae.persistent;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceClickListener;
import androidx.preference.PreferenceManager;
import android.text.TextUtils;

import paulscode.android.mupen64plusae.R;

import paulscode.android.mupen64plusae.ActivityHelper;
import paulscode.android.mupen64plusae.compat.AppCompatPreferenceActivity;
import paulscode.android.mupen64plusae.dialog.Prompt;
import paulscode.android.mupen64plusae.preference.PrefUtil;
import paulscode.android.mupen64plusae.util.FileUtil;
import paulscode.android.mupen64plusae.util.LegacyFilePicker;
import paulscode.android.mupen64plusae.util.LocaleContextWrapper;
import paulscode.android.mupen64plusae.util.Notifier;

public class DataPrefsActivity extends AppCompatPreferenceActivity implements OnPreferenceClickListener,
    SharedPreferences.OnSharedPreferenceChangeListener
{
    // App data and user preferences
    private AppData mAppData = null;
    private GlobalPrefs mGlobalPrefs = null;

    private SharedPreferences mPrefs = null;

    // Runs once all files access has been granted from the settings screen
    private Runnable mPendingStorageAction = null;

    ActivityResultLauncher<Intent> mLaunchGameDataFolderPicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null) {

                    Uri fileUri = getUri(data);

                    Preference currentPreference = findPreference(GlobalPrefs.PATH_GAME_SAVES);
                    if (currentPreference != null && fileUri != null) {

                        if (!mAppData.useLegacyFileBrowser) {
                            // The data folder is written to, so a folder the picker only granted
                            // read access to must not be stored (GitHub #2).
                            try {
                                getContentResolver().takePersistableUriPermission(fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            } catch (SecurityException e) {
                                Log.e("DataPrefsActivity", "No write permission for " + fileUri, e);
                                Notifier.showToast(this, R.string.dataFolder_no_write_access);
                                return;
                            }
                        }

                        storeGameDataFolder(fileUri);
                    }
                }
            });

    ActivityResultLauncher<Intent> mManageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
                        && mPendingStorageAction != null) {
                    mPendingStorageAction.run();
                }
                mPendingStorageAction = null;
            });

    ActivityResultLauncher<Intent> mLaunchIdlFilePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null) {

                    Uri fileUri = getUri(data);

                    Preference currentPreference = findPreference(GlobalPrefs.PATH_JAPAN_IPL_ROM);
                    if (currentPreference != null && fileUri != null) {

                        if (!mAppData.useLegacyFileBrowser) {
                            getContentResolver().takePersistableUriPermission(fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }

                        DocumentFile file = FileUtil.getDocumentFileSingle(this, fileUri);
                        String summary = file == null ? "" : file.getName();
                        currentPreference.setSummary(summary);
                        mGlobalPrefs.putString(GlobalPrefs.PATH_JAPAN_IPL_ROM, fileUri.toString());
                    }
                }
            });

    private Uri getUri(Intent data)
    {
        AppData appData = new AppData( this );
        Uri returnValue = null;
        if (appData.useLegacyFileBrowser) {
            final Bundle extras = data.getExtras();

            if (extras != null) {
                final String searchUri = extras.getString(ActivityHelper.Keys.SEARCH_PATH);
                returnValue = Uri.parse(searchUri);
            }
        } else {
            returnValue = data.getData();
        }

        return returnValue;
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        if(TextUtils.isEmpty(LocaleContextWrapper.getLocalCode()))
        {
            super.attachBaseContext(newBase);
        }
        else
        {
            super.attachBaseContext(LocaleContextWrapper.wrap(newBase,LocaleContextWrapper.getLocalCode()));
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        // Get app data and user preferences
        mAppData = new AppData(this);
        mGlobalPrefs = new GlobalPrefs(this, mAppData);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override
    protected String getSharedPrefsName() {
        return null;
    }

    @Override
    protected int getSharedPrefsId()
    {
        return R.xml.preferences_data;
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        refreshViews();
        mPrefs.registerOnSharedPreferenceChangeListener( this );
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        mPrefs.unregisterOnSharedPreferenceChangeListener( this );
    }

    @Override
    public boolean onPreferenceClick(Preference preference)
    {
        // Handle the clicks on certain menu items that aren't actually
        // preferences
        final String key = preference.getKey();

        if (GlobalPrefs.PATH_GAME_SAVES.equals(key)) {
            chooseGameDataFolderSource();
        } else if (GlobalPrefs.PATH_JAPAN_IPL_ROM.equals(key)) {
            startFilePicker();
        } else {// Let Android handle all other preference clicks
            return false;
        }

        // Tell Android that we handled the click
        return true;
    }

    @Override
    protected void OnPreferenceScreenChange(String key)
    {
        // Handle certain menu items that require extra processing or aren't
        // actually preferences
        PrefUtil.setOnPreferenceClickListener(this, GlobalPrefs.PATH_GAME_SAVES, this);
        PrefUtil.setOnPreferenceClickListener(this, GlobalPrefs.PATH_JAPAN_IPL_ROM, this);


        Preference currentPreference = findPreference(GlobalPrefs.PATH_GAME_SAVES);
        if (currentPreference != null) {
            String uri = mGlobalPrefs.getString(GlobalPrefs.PATH_GAME_SAVES, "");

            if (!TextUtils.isEmpty(uri)) {
                DocumentFile file = FileUtil.getDocumentFileTree(this, Uri.parse(uri));
                currentPreference.setSummary(file.getName());
            }
        }

        currentPreference = findPreference(GlobalPrefs.PATH_JAPAN_IPL_ROM);
        if (currentPreference != null) {
            String uri = mGlobalPrefs.getString(GlobalPrefs.PATH_JAPAN_IPL_ROM, "");

            if (!TextUtils.isEmpty(uri)) {
                DocumentFile file = FileUtil.getDocumentFileSingle(this, Uri.parse(uri));
                currentPreference.setSummary(file == null ? "" : file.getName());
            }
        }
    }

    @Override
    public void onSharedPreferenceChanged( SharedPreferences sharedPreferences, String key )
    {
        refreshViews();
    }

    private void refreshViews()
    {
        PrefUtil.enablePreference(this, GlobalPrefs.PATH_GAME_SAVES,
                mPrefs.getString(GlobalPrefs.GAME_DATA_STORAGE_TYPE, "external").equals("external"));
    }

    private void storeGameDataFolder(Uri folderUri)
    {
        Preference currentPreference = findPreference(GlobalPrefs.PATH_GAME_SAVES);
        if (currentPreference != null) {
            DocumentFile file = FileUtil.getDocumentFileTree(this, folderUri);
            currentPreference.setSummary(file == null ? "" : file.getName());
        }
        mGlobalPrefs.putString(GlobalPrefs.PATH_GAME_SAVES, folderUri.toString());
    }

    /**
     * Same two ways in as for ROMs: the system folder picker, or a typed path with all files
     * access for when the picker refuses the folder (GitHub #2).
     */
    private void chooseGameDataFolderSource()
    {
        final CharSequence[] items = {
                getString(R.string.scanRomsDialog_select_folder),
                getString(R.string.scanRomsDialog_enter_path)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.gameDataStorageExternalPath_title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        startFolderPicker();
                    } else {
                        ensureManageStoragePermission(this::startManualPathEntry);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void ensureManageStoragePermission(Runnable onGranted)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
            onGranted.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.scanRomsDialog_storage_permission_title)
                .setMessage(R.string.scanRomsDialog_storage_permission_message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    mPendingStorageAction = onGranted;
                    mManageStorageLauncher.launch(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startManualPathEntry()
    {
        Prompt.promptText(this, getString(R.string.scanRomsDialog_enter_path_title), null,
                "/storage/emulated/0/", getString(R.string.dataFolder_enter_path_hint), InputType.TYPE_CLASS_TEXT,
                (text, which) -> {
                    if (which != DialogInterface.BUTTON_POSITIVE || text == null) {
                        return;
                    }
                    final DataFolderPath.Result result = DataFolderPath.check(text.toString());
                    switch (result.status) {
                        case OK:
                            storeGameDataFolder(Uri.fromFile(result.folder));
                            break;
                        case NOT_WRITABLE:
                            Notifier.showToast(this, R.string.dataFolder_not_writable, result.folder.getPath());
                            break;
                        case EMPTY:
                            break;
                        default:
                            Notifier.showToast(this, R.string.scanRomsDialog_path_not_found, text.toString().trim());
                            break;
                    }
                });
    }

    private void startFolderPicker()
    {
        Intent intent;

        if (mAppData.useLegacyFileBrowser) {
            intent = new Intent(this, LegacyFilePicker.class);
            intent.putExtra( ActivityHelper.Keys.CAN_SELECT_FILE, false );
            intent.putExtra( ActivityHelper.Keys.CAN_VIEW_EXT_STORAGE, true);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                    Intent.FLAG_GRANT_READ_URI_PERMISSION|
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION|
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        }
        mLaunchGameDataFolderPicker.launch(intent);
    }

    private void startFilePicker()
    {
        Intent intent;
        if (mAppData.useLegacyFileBrowser) {
            intent = new Intent(this, LegacyFilePicker.class);
            intent.putExtra( ActivityHelper.Keys.CAN_SELECT_FILE, true );
            intent.putExtra( ActivityHelper.Keys.CAN_VIEW_EXT_STORAGE, true);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            intent.putExtra("android.content.extra.SHOW_ADVANCED", true);
        }
        mLaunchIdlFilePicker.launch(intent);
    }
}
