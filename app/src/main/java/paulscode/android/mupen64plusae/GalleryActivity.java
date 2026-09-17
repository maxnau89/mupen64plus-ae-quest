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
package paulscode.android.mupen64plusae;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.PointerIcon;
import android.view.View;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.SearchView.OnQueryTextListener;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import paulscode.android.mupen64plusae.dialog.ConfirmationDialog;
import paulscode.android.mupen64plusae.dialog.ConfirmationDialog.PromptConfirmListener;
import paulscode.android.mupen64plusae.dialog.LocaleDialog;
import paulscode.android.mupen64plusae.dialog.Popups;
import paulscode.android.mupen64plusae.game.GameActivity;
import paulscode.android.mupen64plusae.jni.CoreService;
import paulscode.android.mupen64plusae.persistent.AppData;
import paulscode.android.mupen64plusae.persistent.ConfigFile;
import paulscode.android.mupen64plusae.persistent.GamePrefs;
import paulscode.android.mupen64plusae.persistent.GlobalPrefs;
import paulscode.android.mupen64plusae.task.ExtractAssetsOrCleanupTask;
import paulscode.android.mupen64plusae.task.GalleryRefreshTask;
import paulscode.android.mupen64plusae.task.GalleryRefreshTask.GalleryRefreshFinishedListener;
import paulscode.android.mupen64plusae.task.SyncProgramsJobService;
import paulscode.android.mupen64plusae.util.CountryCode;
import paulscode.android.mupen64plusae.util.DisplayWrapper;
import paulscode.android.mupen64plusae.util.FileUtil;
import paulscode.android.mupen64plusae.util.LocaleContextWrapper;
import paulscode.android.mupen64plusae.util.Notifier;
import paulscode.android.mupen64plusae.game.xr.QuestXr;

public class GalleryActivity extends AppCompatActivity implements PromptConfirmListener,
        GalleryRefreshFinishedListener
{
    // Saved instance states
    private static final String STATE_QUERY = "STATE_QUERY";
    private static final String STATE_SIDEBAR = "STATE_SIDEBAR";
    private static final String STATE_FILE_TO_DELETE = "STATE_FILE_TO_DELETE";
    private static final String STATE_CACHE_ROM_INFO_FRAGMENT = "STATE_CACHE_ROM_INFO_FRAGMENT";
    private static final String STATE_GALLERY_REFRESH_NEEDED = "STATE_GALLERY_REFRESH_NEEDED";
    private static final String STATE_SCROLL_TO_POSITION = "STATE_SCROLL_TO_POSITION";
    private static final String STATE_LAUNCH_GAME_AFTER_SCAN = "STATE_LAUNCH_GAME_AFTER_SCAN";
    private static final String STATE_GAME_STARTED_EXTERNALLY = "STATE_GAME_STARTED_EXTERNALLY";
    private static final String STATE_REMOVE_FROM_LIBRARY_DIALOG = "STATE_REMOVE_FROM_LIBRARY_DIALOG";
    private static final String STATE_CLEAR_SHADERCACHE_DIALOG = "STATE_CLEAR_SHADERCACHE_DIALOG";
    private static final String STATE_LOCALE_DIALOG = "STATE_LOCALE_DIALOG";
    private static final String STATE_HARDWARE_INFO_POPUP = "STATE_HARDWARE_INFO_POPUP";
    private static final String STATE_SHOW_APP_VERSION_POPUP = "STATE_SHOW_APP_VERSION_POPUP";
    private static final String STATE_FAQ_POPUP = "STATE_FAQ_POPUP";
    public static final String KEY_IS_LEANBACK = "KEY_IS_LEANBACK";
    public static final String KEY_IS_SHORTCUT = "KEY_IS_SHORTCUT";

    // Auto save file names, see GameDataManager
    private static final String AUTO_SAVE_MATCHER = "^\\d\\d\\d\\d-\\d\\d-\\d\\d-\\d\\d-\\d\\d-\\d\\d\\..*sav$";

    public static final int REMOVE_FROM_LIBRARY_DIALOG_ID = 1;
    public static final int CLEAR_SHADER_CACHE_DIALOG_ID = 2;

    // App data and user preferences
    private AppData mAppData = null;
    private GlobalPrefs mGlobalPrefs = null;

    // Widgets
    private RecyclerView mGridView;
    private View mRootLayout = null;

    // Searching
    private SearchView mSearchView;
    private String mSearchQuery = "";

    // Resizable gallery thumbnails
    public int galleryWidth;
    public int galleryMaxWidth;
    public int galleryHalfSpacing;
    public int galleryColumns = 2;
    public float galleryAspectRatio;

    // Misc.
    private GalleryItem mSelectedItem = null;

    private ScanRomsFragment mCacheRomInfoFragment = null;

    //If this is set to true, the gallery will be refreshed next time this activity is resumed
    boolean mRefreshNeeded = false;

    boolean mGameStartedExternally = false;

    String mPathToDelete = null;

    private int mCurrentVisiblePosition = 0;

    // Launch a game after searching is complete
    private String mLaunchGameAfterScan = "";
    private String mScanForGameOnResume = "";

    private ConfigFile mConfig;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    List<GalleryItem> mItemsCache = new ArrayList<>();
    List<GalleryItem> mAllItems = new ArrayList<>();
    List<GalleryItem> mRecentItemsCache = new ArrayList<>();

    ActivityResultLauncher<Intent> mLaunchGame = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    // Call this here as well since onActivityResult happens before onResume
                    createSearchMenu();

                    mSearchQuery = "";

                    if (mSearchView != null) {
                        mSearchView.setQuery( mSearchQuery, true );
                    }

                    if(mGameStartedExternally)
                    {
                        finishAffinity();
                    }
                }
            });

    ActivityResultLauncher<Intent> mLaunchScanRoms = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                    // Call this here as well since onActivityResult happens before onResume
                    createSearchMenu();

                    final Bundle extras = data.getExtras();

                    if (extras != null) {
                        final String searchUri = extras.getString( ActivityHelper.Keys.SEARCH_PATH );
                        final boolean searchZips = extras.getBoolean( ActivityHelper.Keys.SEARCH_ZIPS );
                        final boolean downloadArt = extras.getBoolean( ActivityHelper.Keys.DOWNLOAD_ART );
                        final boolean clearGallery = extras.getBoolean( ActivityHelper.Keys.CLEAR_GALLERY );
                        final boolean searchSubdirectories = extras.getBoolean( ActivityHelper.Keys.SEARCH_SUBDIR );
                        final boolean searchSingleFile = extras.getBoolean( ActivityHelper.Keys.SEARCH_SINGLE_FILE );

                        if (searchUri != null)
                        {
                            refreshRoms(searchUri, searchZips, downloadArt, clearGallery, searchSubdirectories, searchSingleFile);
                        }
                    }
                }
            });

    private void loadGameFromExtras( Bundle extras) {

        Intent intent = new Intent(CoreService.SERVICE_EVENT);
        if (extras != null) {

            // You can also include some extra data.
            intent.putExtra(CoreService.SERVICE_QUIT, true);
            sendBroadcast(intent);

            int currentAttempt = 0;
            while (ActivityHelper.isServiceRunning(this, ActivityHelper.coreServiceProcessName) &&
                    currentAttempt++ < 100) {
                Log.i("GalleryActivity", "Waiting on pevious instance to exit");

                // Sleep for 10 ms to prevent a tight loop
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            if (!extras.getBoolean(KEY_IS_LEANBACK)) {
                Log.i("GalleryActivity", "Loading ROM from other app");
                final String givenRomPath = extras.getString( ActivityHelper.Keys.ROM_PATH );

                if( !TextUtils.isEmpty( givenRomPath ) ) {
                    getIntent().replaceExtras((Bundle)null);

                    launchGameOnCreation(givenRomPath, true);
                }
            } else {

                mGameStartedExternally = true;
                
                Log.i("GalleryActivity", "Loading ROM from leanback");
                String romPath = extras.getString(ActivityHelper.Keys.ROM_PATH );
                String zipPath = extras.getString(ActivityHelper.Keys.ZIP_PATH );
                String md5 = extras.getString(ActivityHelper.Keys.ROM_MD5);
                String crc = extras.getString(ActivityHelper.Keys.ROM_CRC);
                String headerName = extras.getString(ActivityHelper.Keys.ROM_HEADER_NAME);

                byte countryCode;
                if (extras.getBoolean(KEY_IS_SHORTCUT)) {
                    countryCode = (byte)extras.getInt(ActivityHelper.Keys.ROM_COUNTRY_CODE);
                } else {
                    countryCode = extras.getByte(ActivityHelper.Keys.ROM_COUNTRY_CODE);
                }

                String artPath = extras.getString(ActivityHelper.Keys.ROM_ART_PATH);
                String goodName = extras.getString(ActivityHelper.Keys.ROM_GOOD_NAME);
                String displayName = extras.getString(ActivityHelper.Keys.ROM_DISPLAY_NAME);

                if (displayName == null) {
                    displayName = goodName;
                }

                launchGameActivity( romPath, zipPath,  md5, crc, headerName, countryCode, artPath, goodName, displayName, true,
                        false, false);
                getIntent().replaceExtras((Bundle)null);
            }
        } else {
            // You can also include some extra data.
            intent.putExtra(CoreService.SERVICE_RESUME, true);
            sendBroadcast(intent);
        }
    }

    @Override
    protected void onNewIntent( Intent intent )
    {
        Log.i("GalleryActivity", "onNewIntent");

        // If the activity is already running and is launched again (e.g. from a file manager app),
        // the existing instance will be reused rather than a new one created. This behavior is
        // specified in the manifest (launchMode = singleTask). In that situation, any activities
        // above this on the stack (e.g. GameActivity, GamePrefsActivity) will be destroyed
        // gracefully and onNewIntent() will be called on this instance. onCreate() will NOT be
        // called again on this instance.
        super.onNewIntent( intent );

        // Only remember the last intent used
        setIntent( intent );

        // Get the ROM path if it was passed from another activity/app
        if (getIntent() != null)
        {
            boolean launchedFromHistory = (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
            if (!launchedFromHistory) {
                final Bundle extras = getIntent().getExtras();
                loadGameFromExtras(extras);
            }
        }
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
    protected void onCreate( Bundle savedInstanceState )
    {
        Log.i("GalleryActivity", "onCreate");

        super.onCreate( savedInstanceState );

        if( savedInstanceState != null )
        {
            mSelectedItem = null;
            final String sideBarMd5 = savedInstanceState.getString( STATE_SIDEBAR );
            if( sideBarMd5 != null )
            {
                mSelectedItem = new GalleryItem(this, sideBarMd5, null, null,
                        CountryCode.DEMO, null, null, null, null, null, 0, 0.0f);
            }

            final String query = savedInstanceState.getString( STATE_QUERY );
            if( query != null )
                mSearchQuery = query;

            mPathToDelete = savedInstanceState.getString( STATE_FILE_TO_DELETE );
            mRefreshNeeded = savedInstanceState.getBoolean(STATE_GALLERY_REFRESH_NEEDED);
            mGameStartedExternally = savedInstanceState.getBoolean(STATE_GAME_STARTED_EXTERNALLY);
            mCurrentVisiblePosition = savedInstanceState.getInt(STATE_SCROLL_TO_POSITION);
            mLaunchGameAfterScan = savedInstanceState.getString(STATE_LAUNCH_GAME_AFTER_SCAN);
        }

        // Get app data and user preferences
        mAppData = new AppData( this );
        mGlobalPrefs = new GlobalPrefs( this, mAppData );
        mConfig = new ConfigFile(mGlobalPrefs.romInfoCacheCfg);

        // Lay out the content
        setContentView( R.layout.gallery_activity );
        mGridView = findViewById( R.id.gridview );

        // Add the toolbar to the activity (which supports the fancy menu/arrow animation)
        final Toolbar toolbar = findViewById( R.id.toolbar );
        toolbar.setTitle( R.string.app_name );
        final View firstGridChild = mGridView.getChildAt(0);

        if(firstGridChild != null)
        {
            toolbar.setNextFocusDownId(firstGridChild.getId());
        }

        setSupportActionBar( toolbar );

        mRootLayout = findViewById( R.id.drawerLayout );
        setupQuestRail();

        // find the retained fragment on activity restarts
        final FragmentManager fm = getSupportFragmentManager();
        mCacheRomInfoFragment = (ScanRomsFragment) fm.findFragmentByTag(STATE_CACHE_ROM_INFO_FRAGMENT);

        if(mCacheRomInfoFragment == null)
        {
            mCacheRomInfoFragment = new ScanRomsFragment();
            fm.beginTransaction().add(mCacheRomInfoFragment, STATE_CACHE_ROM_INFO_FRAGMENT).commit();
        }

        // Don't call the async version otherwise the scroll position is lost
        refreshGrid();

        if(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT )
        {
            DisplayWrapper.drawBehindSystemBars(this);
        }

        CoordinatorLayout coordLayout = findViewById(R.id.coordLayout);

        ViewCompat.setOnApplyWindowInsetsListener(coordLayout, (v, insets) -> {

            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)coordLayout.getLayoutParams();
            params.topMargin = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            params.rightMargin = insets.getInsets(WindowInsetsCompat.Type.systemBars()).right;
            coordLayout.setLayoutParams(params);

            return insets;
        });

        mRootLayout.setOnHoverListener((v, event) -> {
            mHandler.postDelayed(() -> v.setPointerIcon(PointerIcon.getSystemIcon(GalleryActivity.this, PointerIcon.TYPE_ARROW)), 100);
            return false;
        });

        mRootLayout.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int oldWidth = oldRight - oldLeft;
            int oldHeight = oldBottom - oldTop;
            if( v.getWidth() != oldWidth || v.getHeight() != oldHeight )
            {
                refreshGrid(mItemsCache, mRecentItemsCache);
            }
        });

        // Get the ROM path if it was passed from another activity/app
        if (getIntent() != null)
        {
            boolean launchedFromHistory = (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0;
            if (!launchedFromHistory) {
                final Bundle extras = getIntent().getExtras();
                loadGameFromExtras(extras);
            }
        }

        if(ActivityHelper.isServiceRunning(this, ActivityHelper.coreServiceProcessName)) {
            Log.i("GalleryActivity", "CoreService is running");
        }

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isQuestGameDetailShown())
                {
                    hideQuestGameDetail();
                }
                else if (mQuestSection != QuestSection.LIBRARY)
                {
                    showQuestSection(QuestSection.LIBRARY);
                }
                else if(mSearchView != null && !TextUtils.isEmpty(mSearchQuery)) {
                    mSearchQuery = "";
                    mSearchView.setQuery( mSearchQuery, true );
                }
            }
        });

    }

    @Override
    public void onPause() {
        Log.i("GalleryActivity", "onPause");

        super.onPause();

        GridLayoutManager layoutManager = (GridLayoutManager)mGridView.getLayoutManager();
        if (layoutManager != null) {
            mCurrentVisiblePosition = ((GridLayoutManager)mGridView.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
        }
    }

    @Override
    public void onResume()
    {
        Log.i("GalleryActivity", "onResume");

        super.onResume();

        //mRefreshNeeded will be set to true whenever a game is launched
        if(mRefreshNeeded)
        {
            mRefreshNeeded = false;
            reloadCacheAndRefreshGrid();
        }

        // This is called here rather than onCreate otherwise onQueryTextChange is called on creation
        createSearchMenu();

        // A game that did not exist in the gallery was requested to be searched for
        if (!TextUtils.isEmpty(mScanForGameOnResume)) {
            mCacheRomInfoFragment.refreshRoms(mScanForGameOnResume, true, true, false, false,
                    true, mAppData, mGlobalPrefs);
        }
    }

    @Override
    public void onSaveInstanceState( @NonNull Bundle savedInstanceState )
    {
        Log.i("GalleryActivity", "onSaveInstanceState");

        if( mSearchView != null )
            savedInstanceState.putString( STATE_QUERY, mSearchView.getQuery().toString() );
        if( mSelectedItem != null )
            savedInstanceState.putString( STATE_SIDEBAR, mSelectedItem.md5 );
        savedInstanceState.putBoolean(STATE_GALLERY_REFRESH_NEEDED, mRefreshNeeded);
        savedInstanceState.putBoolean(STATE_GAME_STARTED_EXTERNALLY, mGameStartedExternally);
        savedInstanceState.putString(STATE_FILE_TO_DELETE, mPathToDelete);
        savedInstanceState.putInt(STATE_SCROLL_TO_POSITION, mCurrentVisiblePosition);
        savedInstanceState.putString(STATE_LAUNCH_GAME_AFTER_SCAN, mLaunchGameAfterScan);

        super.onSaveInstanceState( savedInstanceState );
    }

    private void tagForRefreshNeeded()
    {
        mRefreshNeeded = true;
        GridLayoutManager layoutManager = (GridLayoutManager)mGridView.getLayoutManager();
        if (layoutManager != null) {
            mCurrentVisiblePosition = ((GridLayoutManager)mGridView.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
        }
    }

    public void hideSoftKeyboard()
    {
        // Hide the soft keyboard if needed
        if( mSearchView == null )
            return;

        final InputMethodManager imm = (InputMethodManager) getSystemService( Context.INPUT_METHOD_SERVICE );

        if (imm != null) {
            imm.hideSoftInputFromWindow( mSearchView.getWindowToken(), 0 );
        }
    }

    public void createSearchMenu()
    {
        mSearchView = findViewById(R.id.menuItem_search);
        mSearchView.setOnQueryTextListener( new OnQueryTextListener()
        {
            @Override
            public boolean onQueryTextSubmit( String query )
            {
                return false;
            }

            @Override
            public boolean onQueryTextChange( String query )
            {
                if (!mSearchView.isIconified()) {
                    mSearchQuery = query;
                    refreshGridAsync();
                }

                return false;
            }
        } );

        if( !"".equals( mSearchQuery ) )
        {
            mSearchView.setQuery( mSearchQuery, true );
        }

        mSearchView.setOnQueryTextFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                showInputMethod(view.findFocus());
            }
        });
    }

    private void showInputMethod(View view) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(view, 0);
        }
    }

    private void launchGameOnCreation(String givenRomPath, boolean scanOnFailure)
    {
        if (givenRomPath == null) {
            return;
        }

        Log.i("GalleryActivity", "Rom path = " + givenRomPath);

        boolean isUri = !new File(givenRomPath).exists();

        mGameStartedExternally = true;

        Uri romPathUri;

        if (isUri) {
            romPathUri = Uri.parse(givenRomPath);
        } else {
            romPathUri = Uri.fromFile(new File(givenRomPath));
        }

        // Check if we have a cache of this game first
        GalleryItem foundItem = null;
        for (GalleryItem item : mAllItems) {

            try {
                String decodedPath = URLDecoder.decode(romPathUri.toString(), "UTF-8");
                String decodedItemZip = item.zipUri != null ? URLDecoder.decode(item.zipUri, "UTF-8") : null;
                String decodedItemRom = item.romUri != null ? URLDecoder.decode(item.romUri, "UTF-8") : null;

                if ((decodedItemZip != null && decodedItemZip.equals(decodedPath)) ||
                        (decodedItemRom != null && decodedItemRom.equals(decodedPath)))
                {
                    foundItem = item;
                    break;
                }

            } catch (UnsupportedEncodingException|java.lang.IllegalArgumentException e) {
                e.printStackTrace();
            }
        }

        // We found the item, use the pre-existing one
        if (foundItem != null)
        {
            launchGameActivity(foundItem.romUri,
                    foundItem.zipUri,
                    foundItem.md5, foundItem.crc,
                    foundItem.headerName, foundItem.countryCode.getValue(), foundItem.artPath,
                    foundItem.goodName, foundItem.displayName, true,
                    false, false);
            finishAffinity();
        } else if (scanOnFailure){
            // We want to launch the game after scan completes
            mLaunchGameAfterScan = givenRomPath;
            mScanForGameOnResume = romPathUri.toString();
        }
    }

    private enum GameAction { RESUME, RESTART, NETPLAY_CONNECT, NETPLAY_SERVER, SETTINGS, REMOVE }

    private void handleGameAction(GameAction action)
    {
        final GalleryItem item = mSelectedItem;
        if( item == null || item.romUri == null)
            return;

        if (action == GameAction.RESUME) {
            launchGameActivity( item.romUri,
                    item.zipUri,
                    item.md5, item.crc, item.headerName,
                    item.countryCode.getValue(), item.artPath, item.goodName, item.displayName, false,
                    false, false);
        } else if (action == GameAction.RESTART) {
            launchGameActivity(item.romUri,
                    item.zipUri,
                    item.md5, item.crc,
                    item.headerName, item.countryCode.getValue(), item.artPath,
                    item.goodName, item.displayName, true,
                    false, false);
        } else if (action == GameAction.NETPLAY_CONNECT) {
            launchGameActivity(item.romUri,
                    item.zipUri,
                    item.md5, item.crc,
                    item.headerName, item.countryCode.getValue(), item.artPath,
                    item.goodName, item.displayName, true,
                    true, false);
        } else if (action == GameAction.NETPLAY_SERVER) {
            launchGameActivity(item.romUri,
                    item.zipUri,
                    item.md5, item.crc,
                    item.headerName, item.countryCode.getValue(), item.artPath,
                    item.goodName, item.displayName, true,
                    true, true);
        } else if (action == GameAction.SETTINGS) {
            tagForRefreshNeeded();
            ActivityHelper.startGamePrefsActivity( GalleryActivity.this, item.romUri,
                    item.md5, item.crc, item.headerName, item.goodName, item.displayName, item.countryCode.getValue());
        } else if (action == GameAction.REMOVE) {
            final CharSequence title = getText( R.string.confirm_title );
            final CharSequence message = getText( R.string.confirmRemoveFromLibrary_message );

            final ConfirmationDialog confirmationDialog =
                    ConfirmationDialog.newInstance(REMOVE_FROM_LIBRARY_DIALOG_ID, title.toString(), message.toString());

            final FragmentManager fm = getSupportFragmentManager();
            confirmationDialog.show(fm, STATE_REMOVE_FROM_LIBRARY_DIALOG);
        }
    }

    @Override
    public void onPromptDialogClosed(int id, int which)
    {
        Log.i( "GalleryActivity", "onPromptDialogClosed" );

        if( which == DialogInterface.BUTTON_POSITIVE )
        {
            if(id == REMOVE_FROM_LIBRARY_DIALOG_ID && mSelectedItem != null)
            {
                mConfig.remove(mSelectedItem.md5);
                mConfig.save();
                hideQuestGameDetail();
                refreshGridAsync();
            }

            if (id == CLEAR_SHADER_CACHE_DIALOG_ID) {
                FileUtil.deleteFolder(new File(mGlobalPrefs.shaderCacheDir));
            }
        }
    }

    public void onGalleryItemClick(GalleryItem item)
    {
        showQuestGameDetail(item);
    }

    /** Quest: the game opens as a plate inside the library slab instead of swapping the drawer. */
    private void showQuestGameDetail(GalleryItem item)
    {
        mSelectedItem = item;

        final ImageView art = findViewById(R.id.questGameArt);
        if (item.artPath != null && FileUtil.isFileImage(new File(item.artPath))) {
            item.loadBitmap(this);
            art.setImageDrawable(item.artBitmap);
        } else {
            art.setImageResource(R.drawable.default_coverart);
        }
        ((TextView) findViewById(R.id.questGameName)).setText(item.displayName);
        ((TextView) findViewById(R.id.questGameMeta)).setText(
                (item.headerName != null ? item.headerName.trim() : "") + " \u00B7 " + item.countryCode.toString());

        final TextView resume = findViewById(R.id.questGameResume);
        final TextView restart = findViewById(R.id.questGameRestart);
        resume.setOnClickListener(v -> handleGameAction(GameAction.RESUME));
        restart.setOnClickListener(v -> handleGameAction(GameAction.RESTART));

        // Resume would silently start a new game when the auto save folder is still empty
        final boolean canResume = hasAutoSave(item);
        resume.setVisibility(canResume ? View.VISIBLE : View.GONE);
        restart.setText(canResume ? R.string.quest_game_restart : R.string.quest_game_start);
        restart.setBackgroundResource(canResume ? R.drawable.quest_button_secondary : R.drawable.quest_button_accent);
        restart.setTextColor(canResume ? ContextCompat.getColor(this, R.color.quest_text_primary) : 0xFF06201F);
        final ViewGroup.MarginLayoutParams restartParams = (ViewGroup.MarginLayoutParams) restart.getLayoutParams();
        restartParams.setMarginStart(canResume ? getResources().getDimensionPixelSize(R.dimen.quest_gap_min) : 0);
        restart.setLayoutParams(restartParams);
        ((TextView) findViewById(R.id.questGameResumeHint)).setText(
                canResume ? R.string.quest_game_resumeHint : R.string.quest_game_startHint);

        final LinearLayout actions = findViewById(R.id.questGameActions);
        actions.removeAllViews();
        addQuestActionRow(actions, R.drawable.ic_sliders, getString(R.string.menuItem_settings),
                getString(R.string.quest_game_settingsSummary), GameAction.SETTINGS);
        addQuestActionRow(actions, R.drawable.ic_users, getString(R.string.actionStartNetplay_title),
                getString(R.string.actionStartNetplay_summary), GameAction.NETPLAY_SERVER);
        addQuestActionRow(actions, R.drawable.ic_users, getString(R.string.actionConnectNetplay_title),
                getString(R.string.actionConnectNetplay_summary), GameAction.NETPLAY_CONNECT);
        addQuestActionRow(actions, R.drawable.ic_undo, getString(R.string.actionRemove_title),
                getString(R.string.actionRemove_summary), GameAction.REMOVE);

        ((TextView) findViewById(R.id.questTitle)).setText(R.string.quest_game_title);
        ((TextView) findViewById(R.id.questSubtitle)).setText(item.displayName);
        findViewById(R.id.questSearchPill).setVisibility(View.GONE);
        final View back = findViewById(R.id.questBackToLibrary);
        back.setVisibility(View.VISIBLE);
        back.setOnClickListener(v -> hideQuestGameDetail());
        findViewById(R.id.gallery_empty_icon).setVisibility(View.INVISIBLE);
        findViewById(R.id.questSection).setVisibility(View.GONE);
        mQuestSection = QuestSection.LIBRARY;
        updateQuestRailSelection();
        mGridView.setVisibility(View.INVISIBLE);
        findViewById(R.id.questGameDetail).setVisibility(View.VISIBLE);
        (canResume ? resume : restart).requestFocus();
    }

    /**
     * True when the game has at least one auto save to resume from. Mirrors the paths built by
     * GamePrefs.setGameDirs and the file filter of GameDataManager.getLatestAutoSave.
     */
    private boolean hasAutoSave(GalleryItem item)
    {
        final String[] dataDirs = {
                GamePrefs.getGameDataPath(item.md5, item.headerName != null ? item.headerName : "",
                        item.countryCode.toString()),
                GamePrefs.getAlternateGameDataPath(item.md5)
        };
        for (String dataDir : dataDirs) {
            final File[] saves = new File(mAppData.gameDataDir + "/" + dataDir + "/" + GamePrefs.AUTO_SAVES_DIR)
                    .listFiles(pathname -> pathname.getName().matches(AUTO_SAVE_MATCHER));
            if (saves != null) {
                for (File save : saves) {
                    // V2 saves are only usable once their ".complete" marker exists
                    if (!save.getPath().contains("v2")
                            || new File(save.getPath() + "." + CoreService.COMPLETE_EXTENSION).exists()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isQuestGameDetailShown()
    {
        final View detail = findViewById(R.id.questGameDetail);
        return detail != null && detail.getVisibility() == View.VISIBLE;
    }

    private void hideQuestGameDetail()
    {
        mSelectedItem = null;
        mQuestSection = QuestSection.LIBRARY;
        updateQuestRailSelection();
        findViewById(R.id.questGameDetail).setVisibility(View.GONE);
        mGridView.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.questTitle)).setText(R.string.galleryLibrary);
        findViewById(R.id.questBackToLibrary).setVisibility(View.GONE);
        findViewById(R.id.questSearchPill).setVisibility(View.VISIBLE);
        if (mGridView.getAdapter() != null) {
            refreshGrid(mItemsCache, mRecentItemsCache);
        }
    }

    private void addQuestActionRow(LinearLayout parent, int icon, String title, String summary, GameAction action)
    {
        final View row = getLayoutInflater().inflate(R.layout.quest_action_row, parent, false);
        ((ImageView) row.findViewById(R.id.rowIcon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.rowTitle)).setText(title);
        ((TextView) row.findViewById(R.id.rowSummary)).setText(summary);
        row.setOnClickListener(v -> handleGameAction(action));
        parent.addView(row);
    }

    public boolean onGalleryItemLongClick( GalleryItem item )
    {
        if (item.romUri == null) {
            return false;
        }

        launchGameActivity( item.romUri, item.zipUri,
            item.md5, item.crc, item.headerName, item.countryCode.getValue(),
            item.artPath, item.goodName, item.displayName, false, false, false );
        return true;
    }

    private void refreshRoms(final String searchUri, boolean searchZips, boolean downloadArt, boolean clearGallery, boolean searchSubdirectories,
                             boolean searchSingleFile)
    {
        // Don't let the activity sleep in the middle of scan
        getWindow().setFlags( WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );

        mCacheRomInfoFragment.refreshRoms(searchUri, searchZips, downloadArt, clearGallery, searchSubdirectories,
                searchSingleFile, mAppData, mGlobalPrefs);
    }

    void refreshGrid()
    {
        Log.i("GalleryActivity", "refreshGrid");

        //Reload global prefs
        mAppData = new AppData( this );
        mGlobalPrefs = new GlobalPrefs( this, mAppData );

        GalleryRefreshTask galleryRefreshTask = new GalleryRefreshTask(this, this, mGlobalPrefs, mSearchQuery, mConfig);
        
        galleryRefreshTask.generateGridItemsAndSaveConfig(mItemsCache, mAllItems, mRecentItemsCache);
        refreshGrid(mItemsCache, mRecentItemsCache);

        SyncProgramsJobService.syncProgramsForChannel(this, mAppData.getChannelId());
    }

    void refreshGridAsync()
    {
        Log.i("GalleryActivity", "refreshGridAsync");

        //Reload global prefs
        mAppData = new AppData( this );
        mGlobalPrefs = new GlobalPrefs( this, mAppData );

        GalleryRefreshTask galleryRefreshTask = new GalleryRefreshTask(this, this, mGlobalPrefs, mSearchQuery, mConfig);
        galleryRefreshTask.doInBackground();

        SyncProgramsJobService.syncProgramsForChannel(this, mAppData.getChannelId());
    }

    void reloadCacheAndRefreshGrid()
    {
        // This is called once ROM scan is finished, so no longer require the screen to remain on at this point
        getWindow().setFlags( 0, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON );

        mConfig = new ConfigFile(mGlobalPrefs.romInfoCacheCfg);

        refreshGridAsync();
    }

    @Override
    public void onGalleryRefreshFinished(List<GalleryItem> items, List<GalleryItem> allItems, List<GalleryItem> recentItems)
    {
        mItemsCache = items;
        mAllItems = allItems;
        mRecentItemsCache = recentItems;
        runOnUiThread(() -> refreshGrid(mItemsCache, mRecentItemsCache));
    }

    synchronized void refreshGrid(List<GalleryItem> items, List<GalleryItem> recentItems)
    {
        if( mGlobalPrefs.isRecentShown && TextUtils.isEmpty(mSearchQuery) && recentItems.size() > 0 )
        {
            List<GalleryItem> combinedItems = new ArrayList<>();

            combinedItems.add( new GalleryItem( this, getString( R.string.galleryRecentlyPlayed ) ) );
            combinedItems.addAll( recentItems );

            combinedItems.add( new GalleryItem( this, getString( R.string.galleryLibrary ) ) );
            combinedItems.addAll( items );

            items = combinedItems;
        }

        // Allow the headings to take up the entire width of the layout
        final List<GalleryItem> finalItems = items;
        final GridLayoutManager layoutManager = new GridLayoutManager( this, galleryColumns );
        layoutManager.setSpanSizeLookup( new GridLayoutManager.SpanSizeLookup()
        {
            @Override
            public int getSpanSize( int position )
            {
                // Headings will take up every span (column) in the grid
                if( finalItems.get( position ).isHeading )
                    return galleryColumns;

                // Games will fit in a single column
                return 1;
            }
        } );

        mGridView.setLayoutManager( layoutManager );

        // Update the grid layout
        galleryMaxWidth = (int) (getResources().getDimension( R.dimen.galleryImageWidth ) * mGlobalPrefs.coverArtScale);
        galleryHalfSpacing = (int) getResources().getDimension( R.dimen.galleryHalfSpacing );
        galleryAspectRatio = galleryMaxWidth * 1.0f
                / getResources().getDimension( R.dimen.galleryImageHeight )/mGlobalPrefs.coverArtScale;

        // The rail takes part of the window, measure the grid itself once it is laid out
        int widthPixels = mGridView.getWidth() > 0 ?
                mGridView.getWidth() - mGridView.getPaddingLeft() - mGridView.getPaddingRight() + galleryHalfSpacing * 2 :
                mRootLayout.getWidth();

        int width = widthPixels - galleryHalfSpacing * 2;
        width = Math.max(width, galleryHalfSpacing*4);
        galleryColumns = (int) Math
                .ceil( width * 1.0 / ( galleryMaxWidth + galleryHalfSpacing * 2 ) );
        galleryWidth = width / galleryColumns - galleryHalfSpacing * 2;

        layoutManager.setSpanCount( galleryColumns );

        mGridView.setFocusable(false);
        mGridView.setFocusableInTouchMode(false);

        List<GalleryItem> galleryItems = items;
        mGridView.setAdapter( new GalleryItem.Adapter( this, items ) );

        if (mSelectedItem != null) {
            // Repopulate the game sidebar
            for (final GalleryItem item : galleryItems) {
                if (mSelectedItem.md5.equals( item.md5 )) {
                    onGalleryItemClick( item );
                    break;
                }
            }
        }

        if (mGridView.getAdapter() != null) {
            mGridView.getAdapter().notifyDataSetChanged();
        }

        if (!isQuestGameDetailShown()) {
            updateQuestLibraryCount(galleryItems);
        }

        if(galleryItems.size() > 0) {
            findViewById(R.id.gallery_empty_icon).setVisibility(View.INVISIBLE);
        } else {
            findViewById(R.id.gallery_empty_icon).setVisibility(View.VISIBLE);
        }

        if (mGridView.getLayoutManager() != null) {
            mGridView.getLayoutManager().scrollToPosition(mCurrentVisiblePosition);
        }
        mCurrentVisiblePosition = 0;

        // We were asked to launch a game after a scan completes, so do it here
        if (!TextUtils.isEmpty(mLaunchGameAfterScan)) {
            launchGameOnCreation(mLaunchGameAfterScan, false);
        }
    }

    public void launchGameActivity( String romPath, String zipPath, String romMd5, String romCrc,
            String romHeaderName, byte romCountryCode, String romArtPath, String romGoodName, String romDisplayName,
            boolean isRestarting, boolean isNetplayEnabled, boolean isNetplayServer)
    {
        Log.i( "GalleryActivity", "launchGameActivity" );

        // Make sure that the storage is accessible
        if( !ExtractAssetsOrCleanupTask.areAllAssetsPresent(SplashActivity.SOURCE_DIR, mAppData.coreSharedDataDir))
        {
            Log.e( "GalleryActivity", "SD Card not accessible" );
            Notifier.showToast( this, R.string.toast_sdInaccessible );

            mAppData.putAssetCheckNeeded(true);
            ActivityHelper.startSplashActivity(this);
            finishAffinity();
            return;
        }

        // Update the ConfigSection with the new value for lastPlayed
        final String lastPlayed = Integer.toString( (int) ( new Date().getTime() / 1000 ) );

        mConfig.put(romMd5, "lastPlayed", lastPlayed);
        mConfig.save();

        tagForRefreshNeeded();

        mSelectedItem = null;
        // Launch the game activity
        startGameActivity(romPath, zipPath, romMd5, romCrc, romHeaderName, romCountryCode,
                romArtPath, romGoodName, romDisplayName, isRestarting, isNetplayEnabled, isNetplayServer);
    }

    void startGameActivity(String romPath, String zipPath, String romMd5, String romCrc,
                                  String romHeaderName, byte romCountryCode, String romArtPath, String romGoodName, String romDisplayName,
                                  boolean doRestart, boolean isNetplayEnabled, boolean isNetplayServer) {
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra( ActivityHelper.Keys.ROM_PATH, romPath );
        intent.putExtra( ActivityHelper.Keys.ZIP_PATH, zipPath );
        intent.putExtra( ActivityHelper.Keys.ROM_MD5, romMd5 );
        intent.putExtra( ActivityHelper.Keys.ROM_CRC, romCrc );
        intent.putExtra( ActivityHelper.Keys.ROM_HEADER_NAME, romHeaderName );
        intent.putExtra( ActivityHelper.Keys.ROM_COUNTRY_CODE, romCountryCode );
        intent.putExtra( ActivityHelper.Keys.ROM_ART_PATH, romArtPath );
        intent.putExtra( ActivityHelper.Keys.ROM_GOOD_NAME, romGoodName );
        intent.putExtra( ActivityHelper.Keys.ROM_DISPLAY_NAME, romDisplayName );
        intent.putExtra( ActivityHelper.Keys.DO_RESTART, doRestart );
        intent.putExtra( ActivityHelper.Keys.NETPLAY_ENABLED, isNetplayEnabled );
        intent.putExtra( ActivityHelper.Keys.NETPLAY_SERVER, isNetplayServer );
        mLaunchGame.launch(intent);
    }

    private enum QuestSection { LIBRARY, SETTINGS, PROFILES, TOOLS, ABOUT }

    private QuestSection mQuestSection = QuestSection.LIBRARY;

    /** An entry of a section: opens a settings screen, a tool or a link. */
    private static class QuestEntry
    {
        final int icon;
        final String title;
        final String summary;
        final Runnable action;

        QuestEntry(int icon, String title, String summary, Runnable action)
        {
            this.icon = icon;
            this.title = title;
            this.summary = summary;
            this.action = action;
        }
    }

    /** Quest rail: every top-level destination stays visible, nothing is nested in a drawer. */
    private void setupQuestRail()
    {
        setupQuestRailItem(R.id.railLibrary, R.drawable.ic_controller, R.string.galleryLibrary,
                v -> showQuestSection(QuestSection.LIBRARY));
        setupQuestRailItem(R.id.railSettings, R.drawable.ic_settings, R.string.menuItem_settings,
                v -> showQuestSection(QuestSection.SETTINGS));
        setupQuestRailItem(R.id.railProfiles, R.drawable.ic_sliders, R.string.menuItem_profiles,
                v -> showQuestSection(QuestSection.PROFILES));
        setupQuestRailItem(R.id.railAddRoms, R.drawable.ic_refresh, R.string.quest_rail_addRoms,
                this::onFabRefreshRomsClick);
        setupQuestRailItem(R.id.railTools, R.drawable.ic_circuit, R.string.menuItem_Tools,
                v -> showQuestSection(QuestSection.TOOLS));
        setupQuestRailItem(R.id.railAbout, R.drawable.ic_about, R.string.menuItem_about,
                v -> showQuestSection(QuestSection.ABOUT));
        updateQuestRailSelection();
    }

    private void setupQuestRailItem(int id, int icon, int label, View.OnClickListener listener)
    {
        final View item = findViewById(id);
        ((ImageView) item.findViewById(R.id.railIcon)).setImageResource(icon);
        ((TextView) item.findViewById(R.id.railLabel)).setText(label);
        item.setOnClickListener(listener);
    }

    private void updateQuestRailSelection()
    {
        findViewById(R.id.railLibrary).setSelected(mQuestSection == QuestSection.LIBRARY);
        findViewById(R.id.railSettings).setSelected(mQuestSection == QuestSection.SETTINGS);
        findViewById(R.id.railProfiles).setSelected(mQuestSection == QuestSection.PROFILES);
        findViewById(R.id.railTools).setSelected(mQuestSection == QuestSection.TOOLS);
        findViewById(R.id.railAbout).setSelected(mQuestSection == QuestSection.ABOUT);
    }

    private void showQuestSection(QuestSection section)
    {
        if (isQuestGameDetailShown()) {
            hideQuestGameDetail();
        }
        mQuestSection = section;
        updateQuestRailSelection();

        final View sectionView = findViewById(R.id.questSection);
        final TextView title = findViewById(R.id.questTitle);
        final TextView subtitle = findViewById(R.id.questSubtitle);
        hideSoftKeyboard();

        if (section == QuestSection.LIBRARY) {
            sectionView.setVisibility(View.GONE);
            mGridView.setVisibility(View.VISIBLE);
            findViewById(R.id.questSearchPill).setVisibility(View.VISIBLE);
            title.setText(R.string.galleryLibrary);
            if (mGridView.getLayoutManager() != null) {
                mGridView.getLayoutManager().scrollToPosition(0);
            }
            refreshGrid(mItemsCache, mRecentItemsCache);
            return;
        }

        final List<QuestEntry> entries = new ArrayList<>();
        switch (section) {
            case SETTINGS:
                title.setText(R.string.menuItem_settings);
                subtitle.setText(R.string.quest_settings_subtitle);
                buildQuestSettings(entries);
                break;
            case PROFILES:
                title.setText(R.string.menuItem_profiles);
                subtitle.setText(R.string.quest_profiles_subtitle);
                buildQuestProfiles(entries);
                break;
            case TOOLS:
                title.setText(R.string.menuItem_Tools);
                subtitle.setText(R.string.quest_tools_subtitle);
                buildQuestTools(entries);
                break;
            case ABOUT:
                title.setText(R.string.menuItem_about);
                subtitle.setText(R.string.quest_about_subtitle);
                buildQuestAbout(entries);
                break;
            default:
                break;
        }

        // Two columns of tiles
        final LinearLayout tiles = findViewById(R.id.questSectionTiles);
        tiles.removeAllViews();
        final int gap = getResources().getDimensionPixelSize(R.dimen.quest_gap_min);
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); ++i) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    rowParams.topMargin = gap;
                }
                tiles.addView(row, rowParams);
            }
            final QuestEntry entry = entries.get(i);
            final View tile = getLayoutInflater().inflate(R.layout.quest_category_tile, row, false);
            ((ImageView) tile.findViewById(R.id.tileIcon)).setImageResource(entry.icon);
            ((TextView) tile.findViewById(R.id.tileTitle)).setText(entry.title);
            final TextView summary = tile.findViewById(R.id.tileSummary);
            summary.setText(entry.summary);
            summary.setVisibility(entry.summary.isEmpty() ? View.GONE : View.VISIBLE);
            tile.setOnClickListener(v -> entry.action.run());
            if (i % 2 == 1) {
                ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginStart(gap);
            }
            row.addView(tile);
        }
        if (entries.size() % 2 == 1 && row != null) {
            // Keep the last tile at half width
            final View spacer = new View(this);
            final LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 1, 1);
            spacerParams.setMarginStart(gap);
            row.addView(spacer, spacerParams);
        }

        findViewById(R.id.questSearchPill).setVisibility(View.GONE);
        findViewById(R.id.gallery_empty_icon).setVisibility(View.INVISIBLE);
        mGridView.setVisibility(View.INVISIBLE);
        sectionView.scrollTo(0, 0);
        sectionView.setVisibility(View.VISIBLE);
    }

    private void buildQuestSettings(List<QuestEntry> entries)
    {
        entries.add(new QuestEntry(R.drawable.ic_display, getString(R.string.categoryDisplay_title),
                getString(R.string.quest_settings_display), () -> {
            tagForRefreshNeeded();
            ActivityHelper.startDisplayPrefsActivity(this);
        }));
        entries.add(new QuestEntry(R.drawable.ic_picture, getString(R.string.categoryShaders_title),
                getString(R.string.quest_settings_shaders), () -> {
            tagForRefreshNeeded();
            ActivityHelper.startShadersPrefsActivity(this);
        }));
        entries.add(new QuestEntry(R.drawable.ic_speaker, getString(R.string.categoryAudio_title),
                getString(R.string.quest_settings_audio), () -> ActivityHelper.startAudioPrefsActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_gamepad, getString(R.string.categoryInput_title),
                getString(R.string.quest_settings_input), () -> ActivityHelper.startInputPrefsActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_folder, getString(R.string.categoryLibrary_title),
                getString(R.string.quest_settings_library), () -> {
            tagForRefreshNeeded();
            ActivityHelper.startLibraryPrefsActivity(this);
        }));
        entries.add(new QuestEntry(R.drawable.ic_storage, getString(R.string.categoryData_title),
                getString(R.string.quest_settings_data), () -> {
            tagForRefreshNeeded();
            ActivityHelper.startDataPrefsActivity(this);
        }));
        entries.add(new QuestEntry(R.drawable.ic_users, getString(R.string.categoryNetplay_title),
                getString(R.string.quest_settings_netplay), () -> ActivityHelper.startNetplayPrefsActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_check, getString(R.string.categoryRetroAchievements_title),
                getString(R.string.quest_settings_achievements),
                () -> ActivityHelper.startRetroAchievementsPrefsActivity(this)));
        // The touchscreen category has no use on Meta Quest
        if (!QuestXr.isQuestDevice() && !mGlobalPrefs.isBigScreenMode) {
            entries.add(new QuestEntry(R.drawable.ic_phone, getString(R.string.categoryTouchscreen_title), "", () -> {
                tagForRefreshNeeded();
                ActivityHelper.startTouchscreenPrefsActivity(this);
            }));
        }
    }

    private void buildQuestProfiles(List<QuestEntry> entries)
    {
        entries.add(new QuestEntry(R.drawable.ic_check, getString(R.string.menuItem_select_profiles),
                getString(R.string.quest_profiles_defaults), () -> ActivityHelper.startDefaultPrefsActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_sliders, getString(R.string.menuItem_emulationProfiles),
                getString(R.string.quest_profiles_emulation),
                () -> ActivityHelper.startManageEmulationProfilesActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_gamepad, getString(R.string.menuItem_controllerProfiles),
                getString(R.string.quest_profiles_controller),
                () -> ActivityHelper.startManageControllerProfilesActivity(this)));
        if (!QuestXr.isQuestDevice() && !mGlobalPrefs.isBigScreenMode) {
            entries.add(new QuestEntry(R.drawable.ic_phone, getString(R.string.menuItem_touchscreenProfiles), "",
                    () -> ActivityHelper.startManageTouchscreenProfilesActivity(this)));
        }
    }

    private void buildQuestTools(List<QuestEntry> entries)
    {
        entries.add(new QuestEntry(R.drawable.ic_box, getString(R.string.menuItem_Extract),
                getString(R.string.quest_tools_extract), () -> ActivityHelper.starExtractTextureActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_undo, getString(R.string.menuItem_Clear),
                getString(R.string.quest_tools_clear), () -> ActivityHelper.startDeleteTextureActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_picture, getString(R.string.menuItem_ClearShaderCache),
                getString(R.string.quest_tools_clearShaderCache), () -> {
            final ConfirmationDialog confirmationDialog = ConfirmationDialog.newInstance(CLEAR_SHADER_CACHE_DIALOG_ID,
                    getText(R.string.confirm_title).toString(),
                    getText(R.string.menuItem_ConfirmationClearShaderCache).toString());
            confirmationDialog.show(getSupportFragmentManager(), STATE_CLEAR_SHADERCACHE_DIALOG);
        }));
        entries.add(new QuestEntry(R.drawable.ic_save, getString(R.string.importExportActivity_title),
                getString(R.string.quest_tools_importExport), () -> ActivityHelper.startImportExportActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_debug, getString(R.string.menuItem_logcat),
                getString(R.string.quest_tools_logcat), () -> ActivityHelper.startLogcatActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_circuit, getString(R.string.menuItem_hardwareInfo),
                getString(R.string.quest_tools_hardwareInfo), () -> Popups.newInstance(this, STATE_HARDWARE_INFO_POPUP)
                .show(getSupportFragmentManager(), STATE_HARDWARE_INFO_POPUP)));
    }

    private void buildQuestAbout(List<QuestEntry> entries)
    {
        entries.add(new QuestEntry(R.drawable.ic_about, getString(R.string.menuItem_appVersion),
                getString(R.string.quest_about_version), () -> Popups.newInstance(this, STATE_SHOW_APP_VERSION_POPUP)
                .show(getSupportFragmentManager(), STATE_SHOW_APP_VERSION_POPUP)));
        entries.add(new QuestEntry(R.drawable.ic_users, getString(R.string.menuItem_credits),
                getString(R.string.quest_about_credits), () -> ActivityHelper.launchUri(this, R.string.uri_credits)));
        entries.add(new QuestEntry(R.drawable.ic_location, getString(R.string.menuItem_localeOverride),
                getString(R.string.quest_about_language), () -> LocaleDialog.newInstance(
                getText(R.string.menuItem_localeOverride).toString())
                .show(getSupportFragmentManager(), STATE_LOCALE_DIALOG)));
        entries.add(new QuestEntry(R.drawable.ic_help, getString(R.string.menuItem_faq),
                getString(R.string.quest_about_faq), () -> Popups.newInstance(this, STATE_FAQ_POPUP)
                .show(getSupportFragmentManager(), STATE_FAQ_POPUP)));
        entries.add(new QuestEntry(R.drawable.ic_users, getString(R.string.menuItem_helpForum),
                getString(R.string.quest_about_forum), () -> ActivityHelper.launchUri(this, R.string.uri_forum)));
        entries.add(new QuestEntry(R.drawable.ic_gamepad, getString(R.string.menuItem_controllerDiagnostics),
                getString(R.string.quest_about_diagnostics), () -> ActivityHelper.startDiagnosticActivity(this)));
        entries.add(new QuestEntry(R.drawable.ic_debug, getString(R.string.menuItem_reportBug),
                getString(R.string.quest_about_reportBug), () -> ActivityHelper.launchUri(this, R.string.uri_bugReport)));
        entries.add(new QuestEntry(R.drawable.ic_controller, getString(R.string.quest_about_model),
                getString(R.string.quest_about_modelSummary), () -> ActivityHelper.launchUri(this,
                R.string.quest_about_modelUri)));
    }

    private void updateQuestLibraryCount(List<GalleryItem> shownItems)
    {
        final TextView subtitle = findViewById(R.id.questSubtitle);
        if (subtitle == null) {
            return;
        }
        int shown = 0;
        for (GalleryItem item : shownItems) {
            if (!item.isHeading) {
                ++shown;
            }
        }
        final int total = mAllItems != null && !mAllItems.isEmpty() ? mAllItems.size() : shown;
        subtitle.setText(getString(R.string.quest_library_count, Math.min(shown, total), total));
    }

    public void onFabRefreshRomsClick(View view)
    {
        Intent intent = new Intent(this, ScanRomsActivity.class);
        mLaunchScanRoms.launch(intent);
    }
}
