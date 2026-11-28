package com.eddyizm.tempus.ui.activity;

import static com.eddyizm.tempus.navigation.ManualEdgeToEdgeKt.setUpEdgeToEdge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.eddyizm.tempus.App;
import com.eddyizm.tempus.BuildConfig;
import com.eddyizm.tempus.R;
import com.eddyizm.tempus.broadcast.receiver.ConnectivityStatusBroadcastReceiver;
import com.eddyizm.tempus.databinding.ActivityMainBinding;
import com.eddyizm.tempus.github.utils.PiterfyUpdateLogic;
import com.eddyizm.tempus.github.utils.PiterfyUpdateManager;
import com.eddyizm.tempus.helper.ThemeHelper;
import com.eddyizm.tempus.navigation.NavigationController;
import com.eddyizm.tempus.navigation.NavigationHelper;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.subsonic.models.SubsonicResponse;
import com.eddyizm.tempus.ui.activity.base.BaseActivity;
import com.eddyizm.tempus.navigation.BottomSheetController;
import com.eddyizm.tempus.navigation.BottomSheetHelper;
import com.eddyizm.tempus.ui.dialog.ConnectionAlertDialog;
import com.eddyizm.tempus.ui.dialog.PiterfyUpdateDialog;
import com.eddyizm.tempus.ui.dialog.ServerUnreachableDialog;
import com.eddyizm.tempus.ui.fragment.PlayerBottomSheetFragment;
import com.eddyizm.tempus.ui.fragment.SettingsFragment;
import com.eddyizm.tempus.ui.login.LoginActivity;
import com.eddyizm.tempus.util.AssetLinkNavigator;
import com.eddyizm.tempus.util.AssetLinkUtil;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.AlbumArtistBackfill;
import com.eddyizm.tempus.util.DownloadRepair;
import com.eddyizm.tempus.repository.PlaylistRepository;
import com.eddyizm.tempus.util.ConnectionUtil;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.viewmodel.MainViewModel;
import com.eddyizm.tempus.util.FavoriteRegistry;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.navigation.NavigationView;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

@UnstableApi
public class MainActivity extends BaseActivity {
    private static final String TAG = "MainActivityLogs";

    // The probe whose answer is still wanted, identified by the request itself. A flag shared by
    // every probe cannot tell a held answer from a fresh one, since a held one arrives later.
    private LiveData<SubsonicResponse> outstandingProbe = null;

    public ActivityMainBinding bind;
    private MainViewModel mainViewModel;

    private FragmentManager fragmentManager;
    private NavHostFragment navHostFragment;
    private BottomNavigationView bottomNavigationView;
    private FrameLayout bottomNavigationViewFrame;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    public NavController navController;
    private NavigationController navigationController;
    private BottomSheetController bottomSheetController;
    public BottomSheetBehavior bottomSheetBehavior;
    private boolean isLandscape = false;
    private boolean isTelevision;
    private AssetLinkNavigator assetLinkNavigator;
    private AssetLinkUtil.AssetLink pendingAssetLink;

    ConnectivityStatusBroadcastReceiver connectivityStatusBroadcastReceiver;
    private Intent pendingDownloadPlaybackIntent;
    private boolean pendingOpenNowPlaying = false;

    public ActivityMainBinding getBinding() {
        return bind;
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (com.eddyizm.tempus.lan.LanRemoteVolume.dispatch(event)) return true;
        if (isTelevision && bind != null && handleTvRemoteKey(event)) return true;
        if (isTelevision && event.getKeyCode() == android.view.KeyEvent.KEYCODE_MENU
                && bind != null && !isNavigationDrawerLocked() && bind.bottomNavigation.isShown()) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) {
                bind.drawerLayout.openDrawer(bind.navView);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Piterfy TV: klawisze pilota, których nie obsługuje samo Media3. MENU w rozwiniętym odtwarzaczu otwiera jego menu
     * (opcje utworu), a klawisz SZUKAJ (jest na części pilotów) otwiera wyszukiwarkę, także z rozwiniętego odtwarzacza.
     * Obie akcje wykonujemy po puszczeniu klawisza, a samo naciśnięcie połykamy, żeby nie zadziałało dwa razy.
     */
    private boolean handleTvRemoteKey(android.view.KeyEvent event) {
        int key = event.getKeyCode();
        boolean up = event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled();
        if (key == android.view.KeyEvent.KEYCODE_MENU && bottomSheetBehavior != null
                && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            if (up) {
                View overflow = bind.playerBottomSheet.findViewById(R.id.player_overflow_button);
                if (overflow != null) overflow.performClick();
            }
            return true;
        }
        if (key == android.view.KeyEvent.KEYCODE_SEARCH && navController != null) {
            var destination = navController.getCurrentDestination();
            boolean signedOut = destination == null || destination.getId() == R.id.loginFragment
                    || destination.getId() == R.id.landingFragment;
            if (!signedOut && up && destination.getId() != R.id.searchFragment) {
                if (bottomSheetBehavior != null && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
                    collapseBottomSheetDelayed();
                }
                navController.navigate(R.id.searchFragment);
            }
            return !signedOut;
        }
        return false;
    }

    /** Piterfy TV: 5% bezpiecznego marginesu (overscan) z lewej, prawej i u góry, liczone z rzeczywistego rozmiaru ekranu. */
    private void applyTvSafeArea() {
        View content = (View) bind.navHostFragment.getParent();
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int horizontal = Math.round(com.eddyizm.tempus.util.PiterfyTvPlan.safeHorizontalDp(metrics.widthPixels / metrics.density) * metrics.density);
        int top = Math.round(com.eddyizm.tempus.util.PiterfyTvPlan.safeVerticalDp(metrics.heightPixels / metrics.density) * metrics.density);
        content.setPadding(horizontal, top, horizontal, content.getPaddingBottom());
    }

    /**
     * Piterfy TV: po zmianie ekranu widok, który miał fokus, znika razem z fragmentem i pilot „gubi” fokus (strzałki nic nie robią).
     * Gdy nic nie ma fokusu, ustawiamy go na pierwszym elemencie listy nowego ekranu; lista ładuje się z opóźnieniem, więc próbujemy
     * kilka razy. Fokus, który użytkownik już ma (np. na dolnej nawigacji przy przełączaniu zakładek), zostaje nietknięty.
     */
    private void installTvFocusRecovery() {
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> focusContentWhenLost(0));
    }

    private void focusContentWhenLost(int attempt) {
        Long delay = com.eddyizm.tempus.util.PiterfyTvPlan.focusRetryDelayMs(attempt);
        if (delay == null) return;
        bind.getRoot().postDelayed(() -> {
            if (isFinishing() || isDestroyed() || bind == null) return;
            if (bottomSheetBehavior != null && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) return;
            View focused = bind.getRoot().findFocus();
            if (focused != null && focused != bind.getRoot()) return;
            if (!focusFirstContentItem()) focusContentWhenLost(attempt + 1);
        }, delay);
    }

    /** Pierwszy element pierwszej niepustej listy na ekranie; bez listy: najwyższy, lewy użyteczny element (PiterfyTvPlan). */
    private boolean focusFirstContentItem() {
        View root = bind.navHostFragment;
        androidx.recyclerview.widget.RecyclerView list = firstFilledList(root);
        if (list != null) {
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i).requestFocus()) return true;
            }
        }
        java.util.ArrayList<View> focusables = root.getFocusables(View.FOCUS_DOWN);
        java.util.ArrayList<com.eddyizm.tempus.util.PiterfyTvPlan.FocusCandidate> candidates = new java.util.ArrayList<>();
        int[] where = new int[2];
        for (View candidate : focusables) {
            candidate.getLocationOnScreen(where);
            boolean usable = candidate.isShown() && candidate.isEnabled() && candidate.getWidth() > 0 && candidate.getHeight() > 0;
            candidates.add(new com.eddyizm.tempus.util.PiterfyTvPlan.FocusCandidate(where[1], where[0], usable));
        }
        Integer pick = com.eddyizm.tempus.util.PiterfyTvPlan.pickDefaultFocus(candidates);
        return pick != null && focusables.get(pick).requestFocus();
    }

    @Nullable
    private static androidx.recyclerview.widget.RecyclerView firstFilledList(View view) {
        if (view instanceof androidx.recyclerview.widget.RecyclerView rv && rv.isShown() && rv.getChildCount() > 0) return rv;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                androidx.recyclerview.widget.RecyclerView found = firstFilledList(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // #688: Deep navigation accumulates fragment + back-stack state in the saved
    // instance Bundle. When the activity is stopped (app sent to background) the
    // platform persists it over a Binder transaction that throws
    // TransactionTooLargeException once it passes the buffer limit (~1MB), crashing
    // the app on background. Cap it: if the saved state is dangerously large, drop the
    // restorable fragment state so backgrounding can never crash — worst case the app
    // reopens at the start destination instead of dying.
    private static final int MAX_SAVED_STATE_BYTES = 400 * 1024;

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(outState);
            int size = parcel.dataSize();
            if (size > MAX_SAVED_STATE_BYTES) {
                Log.w(TAG, "Saved instance state is " + size + " bytes (limit "
                        + MAX_SAVED_STATE_BYTES + "); clearing it to avoid TransactionTooLargeException on background (#688)");
                outState.clear();
            }
        } finally {
            parcel.recycle();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);

        setUpEdgeToEdge(this);
        fixUpEdgeToEdge();
        super.onCreate(savedInstanceState);

        bind = ActivityMainBinding.inflate(getLayoutInflater());
        View view = bind.getRoot();
        setContentView(view);
        isTelevision = TvFocus.install(this);
        if (isTelevision) applyTvSafeArea();

        mainViewModel = new ViewModelProvider(this).get(MainViewModel.class);
        assetLinkNavigator = new AssetLinkNavigator(this);

        connectivityStatusBroadcastReceiver = new ConnectivityStatusBroadcastReceiver(this);
        connectivityStatusReceiverManager(true);

        isLandscape = !isTelevision
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        init();
        checkConnectionType();
        getOpenSubsonicExtensions();
        checkTempusUpdate();

        DownloadRepair.repairIfNeeded(this);
        AlbumArtistBackfill.backfillIfNeeded();

        maybeSchedulePlaybackIntent(getIntent());
        openUpdateFromIntent(getIntent());
        setupLoginActivity();
    }

    @Override
    protected void onStart() {
        super.onStart();
        com.eddyizm.tempus.service.PiterfyFavoriteDownloads.refreshSoon(this);
        pingServer();
        initService();
        consumePendingPlaybackIntent();
        consumePendingNowPlayingIntent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pingServer();
        checkTempusUpdate();  // Piterfy: pytanie o aktualizację najwyżej co 3 godziny
        toggleNavigationDrawerLockOnOrientationChange();
    }

    @Override
    protected void onStop() {
        // Abandon the probe, since its answer would be applied on whatever network we come back to.
        outstandingProbe = null;
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        boolean wasOpen = isLocalNetworkRequestOpen();
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // On Android 17 a request the user denied for good is answered with no prompt, after onResume.
        if (wasOpen && !isLocalNetworkRequestOpen() && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) pingServer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        connectivityStatusReceiverManager(false);
        if (isFinishing()) PlaylistRepository.resetKeptSync();
        if (!isChangingConfigurations()) ConnectionUtil.expirePingDeferral();
        bind = null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openUpdateFromIntent(intent);
        maybeSchedulePlaybackIntent(intent);
        consumePendingPlaybackIntent();
        consumePendingNowPlayingIntent();
    }

    public void init() {

        initBottomSheet();
        initNavigation();
        initBackPressedDispatcher();
        initGlass();

        if (Preferences.getPassword() != null || (Preferences.getToken() != null && Preferences.getSalt() != null)) {
            goFromLogin();
        } else {
            goToLogin();
        }

        toggleNavigationDrawerLockOnOrientationChange();
        if (isTelevision) installTvFocusRecovery();

    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        com.eddyizm.tempus.ui.components.PiterfyTransitions.recordTouch(ev); // Piterfy: która karta została kliknięta (morf do strony szczegółów)
        return super.dispatchTouchEvent(ev);
    }

    private void initNavigation() {
        // We link the nav_graph.xml with our navigationController
        NavHostFragment navHostFragment = (NavHostFragment) this
                .getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        navController = Objects.requireNonNull(navHostFragment).getNavController();
        // Piterfy: kazdy ekran wchodzi lagodnie (zanik + lekki wjazd), niezaleznie od tego, jak do niego nawigujemy
        navHostFragment.getChildFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentCreated(@NonNull FragmentManager fm, @NonNull Fragment f, @Nullable Bundle savedInstanceState) {
                        // Piterfy: morf karta -> strona albumu/wykonawcy/playlisty (MaterialContainerTransform)
                        com.eddyizm.tempus.ui.components.PiterfyTransitions.onCreated(f, savedInstanceState);
                    }

                    @Override
                    public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                                                      @NonNull View v, @Nullable Bundle savedInstanceState) {
                        // ekran biorący udział w morfie ma własne wejście, więc zwykłe (zanik + wjazd) pomijamy
                        boolean morph = !(f instanceof DialogFragment)
                                && com.eddyizm.tempus.ui.components.PiterfyTransitions.onViewCreated(f, v, savedInstanceState);
                        if (!(f instanceof DialogFragment) && !morph) com.eddyizm.tempus.util.PiterfyMotion.screen(v);
                    }
                }, false);
        com.eddyizm.tempus.ui.components.PiterfyTransitions.install(navController, isTelevision);
        /*
        navController is currently global since some legacy code still invokes it directly
        the MainActivity methods that use it must be converted to NavigationHelper methods
        */

        // Helper
        NavigationHelper navigationHelper =
                new NavigationHelper(
                        findViewById(R.id.bottom_navigation),
                        findViewById(R.id.bottom_navigation_frame),
                        findViewById(R.id.drawer_layout),
                        findViewById(R.id.nav_view),
                        navHostFragment,
                        navController
                );

        // Controller
        navigationController = new NavigationController(navigationHelper);
        navigationController.syncWithBottomSheetBehavior(bottomSheetBehavior, navController);
    }

    public NavigationController getNavigationController() {
        return navigationController;
    }

    private void initBottomSheet() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        View bottomSheetView = findViewById(R.id.player_bottom_sheet);
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetView);
        /*
        bottomSheetBehavior is currently global since some legacy code still invokes it directly
        the MainActivity methods that use it must be converted to BottomSheetHelper methods
        */

        // Helper
        BottomSheetHelper bottomSheetHelper =
                new BottomSheetHelper(
                        bottomSheetBehavior,
                        bottomSheetView,
                        fragmentManager
                );

        // Controller
        bottomSheetController = new BottomSheetController(bottomSheetHelper);
        bottomSheetController.addCallback(bottomSheetCallback);
        bottomSheetController.replaceFragment(R.id.player_bottom_sheet);
        bottomSheetController.checkAfterStateChanged(mainViewModel);
    }

    public void initBackPressedDispatcher() {
        OnBackPressedCallback callback = new OnBackPressedCallback(bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            @Override
            public void handleOnBackStarted(@NonNull androidx.activity.BackEventCompat backEvent) {
                playerBackPreview(0f);
                com.eddyizm.tempus.util.PiterfyHaptics.gestureStart(bind.playerBottomSheet);
            }

            @Override
            public void handleOnBackProgressed(@NonNull androidx.activity.BackEventCompat backEvent) {
                playerBackPreview(backEvent.getProgress());
            }

            @Override
            public void handleOnBackCancelled() {
                resetPlayerBackPreview();
            }

            @Override
            public void handleOnBackPressed() {
                resetPlayerBackPreview();
                if (isTelevision && getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet")
                        instanceof PlayerBottomSheetFragment player && player.closeTvPlayerPanel()) return;
                collapseBottomSheetDelayed();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                callback.setEnabled(newState == BottomSheetBehavior.STATE_EXPANDED);
            }
            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) { }
        });
    }

    // Piterfy: gest „wstecz” z podglądem (Android 14+): rozwinięty odtwarzacz kurczy się ku mini odtwarzaczowi razem z palcem,
    // a po puszczeniu albo wraca na miejsce (anulowanie), albo zwija się naprawdę (handleOnBackPressed). Wygląd liczy
    // PiterfyGesturePlan; bez Androida 14 system nie podaje postępu, więc zostaje zwykłe zwinięcie.
    private void playerBackPreview(float progress) {
        if (isTelevision || bind == null || !com.eddyizm.tempus.util.PiterfyMotion.enabled()) return;
        View sheet = bind.playerBottomSheet;
        sheet.setPivotX(sheet.getWidth() / 2f);
        sheet.setPivotY(sheet.getHeight());
        float scale = com.eddyizm.tempus.util.PiterfyGesturePlan.backScale(progress);
        sheet.setScaleX(scale);
        sheet.setScaleY(scale);
        sheet.setAlpha(1f - 0.08f * Math.min(1f, Math.max(0f, progress)));
    }

    private void resetPlayerBackPreview() {
        if (bind == null) return;
        View sheet = bind.playerBottomSheet;
        if (sheet.getScaleX() == 1f && sheet.getScaleY() == 1f && sheet.getAlpha() == 1f) return;
        if (!com.eddyizm.tempus.util.PiterfyMotion.enabled()) {
            sheet.setScaleX(1f); sheet.setScaleY(1f); sheet.setAlpha(1f);
            return;
        }
        sheet.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    public BottomSheetController getBottomSheetController() {
        return bottomSheetController;
    }

    public void setBottomSheetInPeek(Boolean isVisible) {
        bottomSheetController.setStateInPeek(isVisible);
    }

    public void setBottomSheetVisibility(boolean visibility) {
        bottomSheetController.setVisibility(visibility);
    }

    @Nullable
    public View getSnackbarAnchor() {
        int state = bottomSheetBehavior.getState();
        if (bind.playerBottomSheet.getVisibility() == View.VISIBLE && state == BottomSheetBehavior.STATE_COLLAPSED) {
            return bind.playerBottomSheet;
        }
        if (state != BottomSheetBehavior.STATE_EXPANDED && !isLandscape && bind.bottomNavigation.getVisibility() == View.VISIBLE) {
            return bind.bottomNavigation;
        }
        return null;
    }

    public void collapseBottomSheetDelayed() {
        bottomSheetController.collapseDelayed();
    }

    public void expandBottomSheet() {
        if (isFinishing() || isDestroyed()) return;
        if (bottomSheetController != null) {
            bottomSheetController.expand();
        }
    }

    public boolean isBottomSheetExpanded() {
        return bottomSheetController != null && bottomSheetController.isExpanded();
    }

    public void setBottomSheetDraggableState(Boolean isDraggable) {
        bottomSheetController.setDraggable(isDraggable);
    }

    private final BottomSheetBehavior.BottomSheetCallback bottomSheetCallback =
            new BottomSheetBehavior.BottomSheetCallback() {
                int navigationHeight;

                @Override
                public void onStateChanged(@NonNull View view, int state) {
                    PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");
                    if (playerBottomSheetFragment != null) playerBottomSheetFragment.onSheetStateChanged(state);  // Piterfy: Canvas

                    switch (state) {
                        case BottomSheetBehavior.STATE_HIDDEN:
                            if (isTelevision) {
                                bind.navHostFragment.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                            }
                            resetMusicSession(); // I can't put the callback inside BottomSheetHelper because of this line
                            break;
                        case BottomSheetBehavior.STATE_COLLAPSED:
                            if (isTelevision) {
                                bind.navHostFragment.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
                                // pełny odtwarzacz otwiera się z mini paska: po zwinięciu fokus wraca na niego, nie ginie
                                View header = playerBottomSheetFragment != null ? playerBottomSheetFragment.getPlayerHeader() : null;
                                if (header != null) header.post(header::requestFocus);
                            }
                            animateBottomSheet(0.0f);
                            if (!isLandscape) {
                                animateBottomNavigation(0.0f, navigationHeight);
                            }
                            if (playerBottomSheetFragment != null)
                                playerBottomSheetFragment.goBackToFirstPage();
                            break;
                        case BottomSheetBehavior.STATE_EXPANDED:
                            if (isTelevision) {
                                bind.navHostFragment.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                                View play = bind.playerBottomSheet.findViewById(androidx.media3.ui.R.id.exo_play_pause);
                                if (play != null) play.requestFocus();
                            }
                            animateBottomSheet(1.0f);
                            if (!isLandscape) {
                                animateBottomNavigation(1.0f, navigationHeight);
                            }
                            break;
                        case BottomSheetBehavior.STATE_SETTLING:
                        case BottomSheetBehavior.STATE_DRAGGING:
                        case BottomSheetBehavior.STATE_HALF_EXPANDED:
                            break;
                    }
                }

                @Override
                public void onSlide(@NonNull View view, float slideOffset) {
                    animateBottomSheet(slideOffset);
                    if (!isLandscape) {
                         animateBottomNavigation(slideOffset, navigationHeight);
                    }
                }
            };

    private void animateBottomSheet(float slideOffset) {
        bottomSheetController.animate(slideOffset);
        // Piterfy: Liquid Glass — przezroczysty panel i szklany nagłówek mini odtwarzacza, znikające w miarę rozwijania
        com.eddyizm.tempus.ui.components.PiterfyGlass.onSheetSlide(bind.playerBottomSheet, bind.navHostFragment, slideOffset);
        // Piterfy: okładka „leci” z mini odtwarzacza na miejsce dużej okładki
        PlayerBottomSheetFragment player = (PlayerBottomSheetFragment) getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");
        if (player != null) player.onSheetSlide(slideOffset);
    }

    /**
     * Piterfy: Liquid Glass — tło z poświatami, szkło dolnej nawigacji (z pigułką zaznaczenia) oraz dialogi i arkusze ze szklanym
     * tłem; ekrany z nieprzezroczystym tłem dostają przezroczystość, żeby poświaty było widać. Bez włączonej opcji nic się nie zmienia.
     */
    private void initGlass() {
        com.eddyizm.tempus.ui.components.PiterfyGlass.install(bind.glassBackdrop, bind.bottomNavigation, bind.navHostFragment);
        if (!com.eddyizm.tempus.appearance.PiterfyAppearance.glassEnabled()) return;
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentViewCreated(@NonNull FragmentManager fm, @NonNull Fragment f, @NonNull View v,
                                              @Nullable Bundle savedInstanceState) {
                if (!(f instanceof DialogFragment)) com.eddyizm.tempus.ui.components.PiterfyGlass.relaxBackground(v);
            }

            @Override
            public void onFragmentStarted(@NonNull FragmentManager fm, @NonNull Fragment f) {
                if (f instanceof DialogFragment) {
                    com.eddyizm.tempus.ui.components.PiterfyGlass.styleDialog(((DialogFragment) f).getDialog());
                }
            }
        }, true);
    }

    private void animateBottomNavigation(float slideOffset, int navigationHeight) {
        if (slideOffset < 0) return;

        if (navigationHeight == 0) {
            navigationHeight = bind.bottomNavigation.getHeight();
        }

        float slideY = navigationHeight - navigationHeight * (1 - slideOffset);

        bind.bottomNavigation.setTranslationY(slideY);
    }

    public void setBottomNavigationBarVisibility(boolean visibility) {
        navigationController.setNavbarVisibility(visibility);
    }

    public void toggleBottomNavigationBarVisibilityOnOrientationChange() {
        float displayDensity = getResources().getDisplayMetrics().density;
        if (isTelevision) {
            var destination = navController.getCurrentDestination();
            boolean showNavigation = destination != null && destination.getId() != R.id.loginFragment
                    && destination.getId() != R.id.landingFragment;
            bottomSheetController.setPeekHeight(showNavigation ? 136 : 56, displayDensity);
            navigationController.setNavbarVisibility(showNavigation);
            navigationController.setSystemBarsVisibility(this, false);
            return;
        }
        // Ignore orientation change, bottom navbar always hidden
        if (Preferences.getHideBottomNavbarOnPortrait()) {
            navigationController.setNavbarVisibility(false);
            bottomSheetController.setPeekHeight(56, displayDensity);
            navigationController.setSystemBarsVisibility(this, !isLandscape);
            return;
        }

        if (!isLandscape) {
            // Show app navbar + show system bars
            bottomSheetController.setPeekHeight(136, displayDensity);
            navigationController.setNavbarVisibility(true);
            navigationController.setSystemBarsVisibility(this, true);
        } else {
            // Hide app navbar + hide system bars
            bottomSheetController.setPeekHeight(56, displayDensity);
            navigationController.setNavbarVisibility(false);
            navigationController.setSystemBarsVisibility(this, false);
        }
    }

    public void setNavigationDrawerLock(boolean locked) {
        navigationController.setDrawerLock(locked);
    }

    public boolean isNavigationDrawerLocked() {
        return navigationController.isNavigationDrawerLocked();
    }

    public void toggleNavigationDrawerLockOnOrientationChange() {
        navigationController.toggleDrawerLockOnOrientation(this);
    }

    public void setSystemBarsVisibility(boolean visibility) {
        navigationController.setSystemBarsVisibility(this, visibility);
    }

    /*
    There are only 4 init functions that must exist up to here
    1. init()
    2. initNavigation()
    3. initBottomSheet()
    4. bottomSheetCallback = new BottomSheetBehavior.BottomSheetCallback() { ... }
     */

    private void initService() {
        MediaManager.check(getMediaBrowserListenableFuture());

        getMediaBrowserListenableFuture().addListener(() -> {
            try {
                getMediaBrowserListenableFuture().get().addListener(new Player.Listener() {
                    @Override
                    public void onIsPlayingChanged(boolean isPlaying) {
                        if (isPlaying && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
                            setBottomSheetInPeek(true);
                        }
                    }
                });
                // If a session is already active on (re)connect — e.g. radio still playing after
                // the app was swiped away — peek the bar. Radio isn't persisted to the queue DB,
                // so the queue-count check (isQueueLoaded) misses it on reopen. onIsPlayingChanged
                // also won't fire here since playback didn't change state.
                if (getMediaBrowserListenableFuture().get().getCurrentMediaItem() != null
                        && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
                    setBottomSheetInPeek(true);
                }
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void goToLogin() {
        bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        setBottomNavigationBarVisibility(false);
        setBottomSheetVisibility(false);

        NavController nc = navigationController.getNavController();
        int id = nc.getCurrentDestination().getId();

        if (id == R.id.landingFragment) {
           nc.navigate(R.id.action_landingFragment_to_loginFragment);
        } else if (id == R.id.settingsFragment) {
            nc.navigate(R.id.action_settingsFragment_to_loginFragment);
        } else if (id == R.id.homeFragment) {
            nc.navigate(R.id.action_homeFragment_to_loginFragment);
        }
    }

    private void goToHome() {
        setBottomNavigationBarVisibility(true);

        NavController nc = navigationController.getNavController();
        int id = nc.getCurrentDestination().getId();

        if (id == R.id.landingFragment) {
            nc.navigate(R.id.action_landingFragment_to_homeFragment);
        } else if (id == R.id.loginFragment) {
            nc.navigate(R.id.action_loginFragment_to_homeFragment);
        }
    }

    public void goFromLogin() {
        setBottomSheetInPeek(mainViewModel.isQueueLoaded());
        new PlaylistRepository().syncKeptPlaylists(getApplicationContext());
        goToHome();
        consumePendingAssetLink();
        consumePendingNowPlayingIntent();
    }

    public void openAssetLink(@NonNull AssetLinkUtil.AssetLink assetLink) {
        openAssetLink(assetLink, true);
    }

    public void openAssetLink(@NonNull AssetLinkUtil.AssetLink assetLink, boolean collapsePlayer) {
        if (!isUserAuthenticated()) {
            pendingAssetLink = assetLink;
            return;
        }
        if (collapsePlayer) {
            setBottomSheetInPeek(true);
        }
        if (assetLinkNavigator != null) {
            assetLinkNavigator.open(assetLink);
        }
    }

    public void quit() {
        com.eddyizm.tempus.lan.LanRemoteSession.disconnect();
        stopService(new Intent(this, com.eddyizm.tempus.lan.LanReceiverService.class));
        resetUserSession();
        resetMusicSession();
        resetViewModel();
        goToLogin();
    }

    private void resetUserSession() {
        FavoriteRegistry.clear();

        PlaylistRepository.resetKeptSync();
        Preferences.setServerId(null);
        Preferences.setSalt(null);
        Preferences.setToken(null);
        Preferences.setPassword(null);
        Preferences.setServer(null);
        Preferences.setLocalAddress(null);
        Preferences.setUser(null);
        Preferences.setClientCert(null);

        // TODO Enter all settings to be reset
        Preferences.setOpenSubsonic(false);
        Preferences.setPlaybackSpeed(1.0f);
        Preferences.setSkipSilenceMode(false);
        Preferences.setDataSavingMode(false);
        Preferences.setStarredSyncEnabled(false);
        Preferences.setStarredAlbumsSyncEnabled(false);
    }

    private void resetMusicSession() {
        com.eddyizm.tempus.lan.LanRemoteSession.disconnect();
        MediaManager.reset(getMediaBrowserListenableFuture());
    }

    private void hideMusicSession() {
        MediaManager.hide(getMediaBrowserListenableFuture());
    }

    private void resetViewModel() {
        this.getViewModelStore().clear();
    }

    // CONNECTION
    private void connectivityStatusReceiverManager(boolean isActive) {
        if (isActive) {
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(connectivityStatusBroadcastReceiver, filter);
        } else {
            unregisterReceiver(connectivityStatusBroadcastReceiver);
        }
    }

    private void pingServer() {
        if (Preferences.getToken() == null && Preferences.getPassword() == null) return;
        if (isLocalNetworkRequestOpen()) {
            ConnectionUtil.deferPing();
            return;
        }

        ConnectionUtil.markPingIssued();
        ConnectionUtil.endPingDeferral();

        if (Preferences.isInUseServerAddressLocal()) {
            mainViewModel.ping().observe(this, subsonicResponse -> {
                if (subsonicResponse == null) {
                    // onStart and onResume each ping, so two failures arrive for one unreachable
                    // address, and the toggle below would put the second one straight back on it.
                    ConnectionUtil.markPingAnswered();
                    if (!Preferences.isInUseServerAddressLocal()) return;

                    // Switching only helps when remote and local are different addresses. When
                    // they're equal (issue #242) switchInUseServerAddress() is a no-op, so we'd
                    // re-enter this branch forever (ping/refresh/resetView loop). Treat that case
                    // like an unreachable server instead of looping.
                    String server = Preferences.getServer();
                    if (server != null && !server.equals(Preferences.getLocalAddress())) {
                        Preferences.setServerSwitchableTimer();
                        Preferences.switchInUseServerAddress();
                        App.refreshSubsonicClient();
                        pingServer();
                        resetView();
                    } else if (Preferences.showServerUnreachableDialog()) {
                        ServerUnreachableDialog dialog = new ServerUnreachableDialog();
                        dialog.show(getSupportFragmentManager(), null);
                    }
                } else {
                    ConnectionUtil.markPingAnswered();
                    Preferences.setOpenSubsonic(subsonicResponse.getOpenSubsonic() != null && subsonicResponse.getOpenSubsonic());
                }
            });
        } else {
            if (outstandingProbe != null) {
                // A probe is deciding the address, and it falls back to this ping when none answers.
                ConnectionUtil.markPingAnswered();
            } else if (Preferences.isServerSwitchable()) {
                probeLocalAddress();
            } else {
                mainViewModel.ping().observe(this, subsonicResponse -> {
                    ConnectionUtil.markPingAnswered();
                    if (subsonicResponse == null) {
                        // A local address answered since, so this failure is stale. It matters
                        // because one of the dialog's buttons clears the session and the queue.
                        if (Preferences.isInUseServerAddressLocal() || outstandingProbe != null) return;

                        if (Preferences.showServerUnreachableDialog()) {
                            ServerUnreachableDialog dialog = new ServerUnreachableDialog();
                            dialog.show(getSupportFragmentManager(), null);
                        }
                    } else {
                        Preferences.setOpenSubsonic(subsonicResponse.getOpenSubsonic() != null && subsonicResponse.getOpenSubsonic());
                    }
                });
            }
        }
    }

    // Probed on a client of its own, so the app is never moved onto an address that has not
    // answered. A probe that fails changes nothing.
    private void probeLocalAddress() {
        // Not stamped here on purpose. The window outlives the activity and the probe does not, so
        // stamping at send left a recreated activity unable to probe for fifteen seconds.
        String probedAddress = Preferences.getLocalAddress();
        LiveData<SubsonicResponse> probe = mainViewModel.pingLocalAddress();
        outstandingProbe = probe;

        probe.observe(this, subsonicResponse -> {
            // A held answer is delivered after onStart has issued the next probe.
            boolean isCurrentProbe = probe == outstandingProbe;
            if (isCurrentProbe) outstandingProbe = null;

            // A server change moves the local address, so a late answer would carry the wrong credentials.
            if (!isCurrentProbe
                    || subsonicResponse == null
                    || !probedAddress.equals(Preferences.getLocalAddress())) {
                ConnectionUtil.markPingAnswered();

                // No local address answered, so ask the public one. The window is stamped first, or
                // a probe slower than the window sends this call into another probe.
                if (isCurrentProbe && subsonicResponse == null) {
                    Preferences.setServerSwitchableTimer();
                    pingServer();
                }
                return;
            }

            Preferences.setInUseServerAddress(probedAddress);
            App.refreshSubsonicClient();

            // Released only once the client points at the new address, or a mapping waking in
            // between reads the new address and the old client.
            ConnectionUtil.markPingAnswered();

            // The screens were built against the address just left, so they are built again. The
            // old code did this on every switch, and this runs only when the probe succeeded.
            resetView();
        });
    }

    private void resetView() {
        resetViewModel();

        NavController nc = navigationController.getNavController();
        int id = nc.getCurrentDestination().getId();
        
        nc.popBackStack(id, true);
        nc.navigate(id);
    }

    private void getOpenSubsonicExtensions() {
        if (Preferences.getToken() != null || Preferences.getPassword() != null) {
            mainViewModel.getOpenSubsonicExtensions().observe(this, openSubsonicExtensions -> {
                if (openSubsonicExtensions != null) {
                    Preferences.setOpenSubsonicExtensions(openSubsonicExtensions);
                }
            });
        }
    }

    private static String promptedUpdateVersion = "";

    /**
     * Piterfy: aktualizacja z poziomu aplikacji (PiterfyUpdateManager, jak w Szpili i Kropi). Pyta GitHuba przy starcie
     * i powrocie do aplikacji (najwyżej co 3 godziny), a okno z propozycją pokazuje raz na wersję w jednym uruchomieniu.
     */
    private void checkTempusUpdate() {
        if (!Preferences.isGithubUpdateEnabled() || !PiterfyUpdateManager.INSTANCE.supported()) return;
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            PiterfyUpdateLogic.Update update = PiterfyUpdateManager.INSTANCE.autoCheck(getApplicationContext());
            if (update == null || update.getVersion().equals(promptedUpdateVersion)) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                promptedUpdateVersion = update.getVersion();
                PiterfyUpdateDialog.show(this, update);
            });
        });
    }

    /** Dotknięcie powiadomienia o aktualizacji otwiera okno aktualizacji. */
    private void openUpdateFromIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(PiterfyUpdateManager.EXTRA_OPEN_UPDATE, false)) return;
        intent.removeExtra(PiterfyUpdateManager.EXTRA_OPEN_UPDATE);
        PiterfyUpdateLogic.Update update = PiterfyUpdateManager.INSTANCE.hint(getApplicationContext());
        if (update != null) PiterfyUpdateDialog.show(this, update);
    }

    private void checkConnectionType() {
        if (Preferences.isWifiOnly()) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();

            if (networkInfo != null && networkInfo.getType() != ConnectivityManager.TYPE_WIFI) {
                ConnectionAlertDialog dialog = new ConnectionAlertDialog();
                dialog.show(getSupportFragmentManager(), null);
            }
        }
    }

    private void maybeSchedulePlaybackIntent(Intent intent) {
        if (intent == null) return;
        if (Constants.ACTION_OPEN_NOW_PLAYING.equals(intent.getAction())) {
            pendingOpenNowPlaying = true;
            intent.setAction(null);
        }
        if (Constants.ACTION_PLAY_EXTERNAL_DOWNLOAD.equals(intent.getAction())
                || intent.hasExtra(Constants.EXTRA_DOWNLOAD_URI)) {
            pendingDownloadPlaybackIntent = new Intent(intent);
        }
        handleAssetLinkIntent(intent);
    }

    private void consumePendingPlaybackIntent() {
        if (pendingDownloadPlaybackIntent == null) return;
        Intent intent = pendingDownloadPlaybackIntent;
        pendingDownloadPlaybackIntent = null;
        playDownloadedMedia(intent);
    }

    private void consumePendingNowPlayingIntent() {
        if (!pendingOpenNowPlaying) return;
        if (!isUserAuthenticated()) return;
        pendingOpenNowPlaying = false;
        if (navController != null && navController.getCurrentDestination() != null
                && navController.getCurrentDestination().getId() == R.id.settingsFragment) {
            navController.popBackStack(R.id.settingsFragment, true);
        }
        expandNowPlaying();
    }

    private void expandNowPlaying() {
        View bottomSheetView = findViewById(R.id.player_bottom_sheet);
        if (bottomSheetView != null && !bottomSheetView.isLaidOut()) {
            // On cold start, wait for CoordinatorLayout to complete its initial layout pass
            bottomSheetView.post(this::expandBottomSheet);
        } else {
            expandBottomSheet();
        }
    }

    private void handleAssetLinkIntent(Intent intent) {
        AssetLinkUtil.AssetLink assetLink = AssetLinkUtil.parse(intent);
        if (assetLink == null) {
            return;
        }
        if (!isUserAuthenticated()) {
            pendingAssetLink = assetLink;
            intent.setData(null);
            return;
        }
        if (assetLinkNavigator != null) {
            assetLinkNavigator.open(assetLink);
        }
        intent.setData(null);
    }

    private boolean isUserAuthenticated() {
        return Preferences.getPassword() != null
                || (Preferences.getToken() != null && Preferences.getSalt() != null);
    }

    private void consumePendingAssetLink() {
        if (pendingAssetLink == null || assetLinkNavigator == null) {
            return;
        }
        assetLinkNavigator.open(pendingAssetLink);
        pendingAssetLink = null;
    }

    private void playDownloadedMedia(Intent intent) {
        String uriString = intent.getStringExtra(Constants.EXTRA_DOWNLOAD_URI);
        if (TextUtils.isEmpty(uriString)) {
            return;
        }

        Uri uri = Uri.parse(uriString);
        String mediaId = intent.getStringExtra(Constants.EXTRA_DOWNLOAD_MEDIA_ID);
        if (TextUtils.isEmpty(mediaId)) {
            mediaId = uri.toString();
        }

        String title = intent.getStringExtra(Constants.EXTRA_DOWNLOAD_TITLE);
        String artist = intent.getStringExtra(Constants.EXTRA_DOWNLOAD_ARTIST);
        String album = intent.getStringExtra(Constants.EXTRA_DOWNLOAD_ALBUM);
        int duration = intent.getIntExtra(Constants.EXTRA_DOWNLOAD_DURATION, 0);

        Bundle extras = new Bundle();
        extras.putString("id", mediaId);
        extras.putString("title", title);
        extras.putString("artist", artist);
        extras.putString("album", album);
        extras.putString("uri", uri.toString());
        extras.putString("type", Constants.MEDIA_TYPE_MUSIC);
        extras.putInt("duration", duration);

        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                .setExtras(extras)
                .setIsBrowsable(false)
                .setIsPlayable(true);

        if (!TextUtils.isEmpty(title)) metadataBuilder.setTitle(title);
        if (!TextUtils.isEmpty(artist)) metadataBuilder.setArtist(artist);
        if (!TextUtils.isEmpty(album)) metadataBuilder.setAlbumTitle(album);

        MediaItem mediaItem = new MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(metadataBuilder.build())
                .setUri(uri)
                .setMimeType(MimeTypes.BASE_TYPE_AUDIO)
                .setRequestMetadata(new MediaItem.RequestMetadata.Builder()
                        .setMediaUri(uri)
                        .setExtras(extras)
                        .build())
                .build();

        MediaManager.playDownloadedMediaItem(getMediaBrowserListenableFuture(), mediaItem);
    }

    private void fixUpEdgeToEdge() {
        View rootView = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());

            int topPadding = Math.max(systemBars.top, displayCutout.top);

            rootView.setPadding(
                    systemBars.left,
                    topPadding,
                    systemBars.right,
                    0
            );

            return new WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(systemBars.left, 0, systemBars.right, systemBars.bottom))
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.NONE)
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(displayCutout.left, 0, displayCutout.right, displayCutout.bottom))
                    .build();
        });
    }

    private void setupLoginActivity() {
        String triggerArg = getIntent().getStringExtra("LOGIN_ACTIVITY_INTENT");
        if ("open_legacy_login_fragment".equals(triggerArg)) {
            goToLogin();
        } else if ("open_legacy_settings_fragment".equals(triggerArg)) {
            goToHome();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            ShortcutManager shortcutManager = getSystemService(ShortcutManager.class);

            Intent intent = new Intent(this, LoginActivity.class);
            intent.setAction(Intent.ACTION_VIEW);

            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "login_activity_shortcut")
                    .setShortLabel(getString(R.string.la_shortcut_label))
                    .setLongLabel(getString(R.string.la_shortcut_label))
                    .setIcon(Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntents(new Intent[]{intent})
                    .build();

            if (shortcutManager != null) {
                shortcutManager.setDynamicShortcuts(Collections.singletonList(shortcut));
            }
        }
    }
}
