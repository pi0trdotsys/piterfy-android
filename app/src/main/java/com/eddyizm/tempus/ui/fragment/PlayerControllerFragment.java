package com.eddyizm.tempus.ui.fragment;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.Format;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.RepeatModeUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.transition.ChangeBounds;
import androidx.transition.Slide;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;
import androidx.viewpager2.widget.ViewPager2;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.InnerFragmentPlayerControllerBinding;
import com.eddyizm.tempus.service.MediaService;
import com.eddyizm.tempus.ui.activity.MainActivity;
import com.eddyizm.tempus.ui.dialog.PlaybackSpeedDialog;
import com.eddyizm.tempus.ui.dialog.PlaylistChooserDialog;
import com.eddyizm.tempus.ui.dialog.SleepTimerDialog;
import com.eddyizm.tempus.util.SleepTimerManager;

import androidx.core.widget.ImageViewCompat;
import android.content.res.ColorStateList;
import com.eddyizm.tempus.ui.dialog.RatingDialog;
import com.eddyizm.tempus.ui.dialog.TrackInfoDialog;
import com.eddyizm.tempus.ui.fragment.pager.PlayerControllerHorizontalPager;
import com.eddyizm.tempus.util.AssetLinkUtil;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.upnp.UpnpPlayer;
import com.eddyizm.tempus.util.MusicUtil;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.viewmodel.PlayerBottomSheetViewModel;
import com.eddyizm.tempus.viewmodel.RatingViewModel;
import com.eddyizm.tempus.util.FavoriteRegistry;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.elevation.SurfaceColors;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@UnstableApi
public class PlayerControllerFragment extends Fragment {
    private static final String TAG = "PlayerCoverFragment";

    private InnerFragmentPlayerControllerBinding bind;
    private com.eddyizm.tempus.lan.LanPlayerAdapter remotePlayer;
    private boolean renderingRemote;
    private ViewPager2 playerMediaCoverViewPager;
    private ToggleButton buttonFavorite;
    private ImageButton playerOverflowButton;
    private RatingViewModel ratingViewModel;
    private RatingBar songRatingBar;
    private TextView playerMediaTitleLabel;
    private TextView playerArtistNameLabel;
    private Button playbackSpeedButton;
    private ToggleButton skipSilenceToggleButton;
    private Chip playerMediaExtension;
    private TextView playerMediaBitrate;
    private ConstraintLayout playerQuickActionView;
    private View playerOpenQueueButton;
    private View playerOpenLyricsButton;
    private ImageButton playerTrackInfo;
    private LinearLayout ratingContainer;
    private LinearLayout sleepTimerContainer;
    private ImageButton sleepTimerButton;
    private android.widget.TextView sleepTimerLabel;
    private ChipGroup assetLinkChipGroup;
    private Chip playerSongLinkChip;
    private Chip playerAlbumLinkChip;
    private Chip playerArtistLinkChip;
    private android.widget.ImageView tvBackdrop;
    private View tvPlayerShade;
    private android.net.Uri tvArtwork;
    private boolean tvOled;

    private MainActivity activity;
    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private MediaService.LocalBinder mediaServiceBinder;
    private boolean isServiceBound = false;
    // Piterfy: Liquid Glass — szklana karta sterowania w pelnym odtwarzaczu (null, gdy szklo wylaczone albo telewizor)
    private com.eddyizm.tempus.ui.components.PiterfyGlassPlayer.Handle glassPlayer;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        activity = (MainActivity) getActivity();

        bind = InnerFragmentPlayerControllerBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);
        ratingViewModel = new ViewModelProvider(requireActivity()).get(RatingViewModel.class);

        init();
        initTvPlayer();
        android.view.View glassRoot = view.findViewById(R.id.now_playing_media_controller_layout);
        if (glassRoot instanceof ViewGroup) {
            glassPlayer = com.eddyizm.tempus.ui.components.PiterfyGlassPlayer.install(getViewLifecycleOwner(), (ViewGroup) glassRoot);
        }
        if (glassPlayer == null) attachPressHaptics();   // przy Liquid Glass dotyk (i haptykę) obsługuje szklany przycisk
        setupWaveform();
        remotePlayer = new com.eddyizm.tempus.lan.LanPlayerAdapter();
        com.eddyizm.tempus.lan.LanRemoteSession.state().observe(getViewLifecycleOwner(), state -> refreshPlaybackDestination());
        com.eddyizm.tempus.lan.LanRemoteSession.queueState().observe(getViewLifecycleOwner(), queue -> refreshPlaybackDestination());
        bind.playerRemoteToggle.setOnClickListener(v -> com.eddyizm.tempus.connect.ConnectDevicePicker.show(requireActivity()));
        com.eddyizm.tempus.util.Flavors.setUpPlayerCastButton(bind.playerCastButton);
        localOutputTint = bind.playerRemoteToggle.getBackgroundTintList();
        localOutputText = bind.playerRemoteToggle.getTextColors();
        outputMonitor = new com.eddyizm.tempus.util.PiterfyOutputMonitor(requireContext(), label -> renderLocalOutput(label));
        qualityClient = new com.eddyizm.tempus.util.PiterfyQualityClient();
        initOverflowButton();
        initQuickActionView();
        initCoverLyricsSlideView();
        initMediaListenable();
        initMediaLabelButton();
        initArtistLabelButton();

        // Sync UI immediately in case a timer survived a rotation.
        updateSleepTimerUI();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();
        bindMediaController();
        if (outputMonitor != null) outputMonitor.start();
    }

    @Override
    public void onStop() {
        if (outputMonitor != null) outputMonitor.stop();
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (tvBackdrop != null) com.bumptech.glide.Glide.with(this).clear(tvBackdrop);
        tvBackdrop = null;
        tvArtwork = null;
        if (qualityClient != null) qualityClient.close();
        qualityClient = null;
        outputMonitor = null;
        glassPlayer = null;
        if (peaksRequest != null) { peaksRequest.cancel(); peaksRequest = null; }
        peaksSongId = null;
        waveform = null;
        super.onDestroyView();
        if (bind != null) bind.nowPlayingMediaControllerView.setPlayer(null);
        if (remotePlayer != null) remotePlayer.release();
        remotePlayer = null;
        bind = null;
    }

    private android.content.res.ColorStateList localOutputTint, localOutputText;

    private void renderLocalOutput(String label) {
        if (bind == null || com.eddyizm.tempus.lan.LanRemoteSession.isActive()) return;
        boolean casting = com.eddyizm.tempus.util.Flavors.castDeviceName() != null;
        var output = bind.playerRemoteToggle;
        String text = !casting && com.eddyizm.tempus.connect.ConnectSession.INSTANCE.getEnabled()
                ? com.eddyizm.tempus.connect.ConnectSession.INSTANCE.outputLabel() : label;
        output.setText(text);
        output.setContentDescription(text);
        output.setBackgroundTintList(casting ? android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(output,
                        com.google.android.material.R.attr.colorSecondaryContainer)) : localOutputTint);
        output.setTextColor(casting ? android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(output,
                        com.google.android.material.R.attr.colorOnSecondaryContainer)) : localOutputText);
        output.setSelected(casting);
    }

    private com.eddyizm.tempus.util.PiterfyOutputMonitor outputMonitor;
    private com.eddyizm.tempus.util.PiterfyQualityClient qualityClient;

    private void refreshPlaybackDestination() {
        if (bind == null || remotePlayer == null) return;
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) {
            remotePlayer.refresh();
            bind.nowPlayingMediaControllerView.setPlayer(remotePlayer);
            com.eddyizm.tempus.lan.LanRemoteState state = com.eddyizm.tempus.lan.LanRemoteSession.current();
            bind.playerRemoteToggle.setBackgroundTintList(localOutputTint);
            bind.playerRemoteToggle.setTextColor(localOutputText);
            bind.playerRemoteToggle.setText(com.eddyizm.tempus.lan.LanRemoteSession.isConnect() ? "Gra na: " + state.getName() : getString(R.string.lan_operating_on, state.getName()));
            bind.playerRemoteToggle.setContentDescription(getString(R.string.lan_controlling, state.getName()));
            bind.playerRemoteToggle.setSelected(true);
            bind.nowPlayingMediaControllerView.setShowShuffleButton(true);
            bind.nowPlayingMediaControllerView.setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL | RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE);
            bind.nowPlayingMediaControllerView.setShowRewindButton(false);
            bind.nowPlayingMediaControllerView.setShowFastForwardButton(false);
            bind.nowPlayingMediaControllerView.setShowPreviousButton(true);
            bind.nowPlayingMediaControllerView.setShowNextButton(true);
            playbackSpeedButton.setVisibility(View.VISIBLE);
            skipSilenceToggleButton.setVisibility(View.GONE);
            buttonFavorite.setVisibility(View.VISIBLE);
            var displayedSong = playerBottomSheetViewModel.getLiveMedia().getValue();
            buttonFavorite.setEnabled(displayedSong != null && Objects.equals(displayedSong.getId(), state.getMediaId()));
            renderingRemote = true;
            try {
                MediaMetadata metadata = com.eddyizm.tempus.lan.LanPlayerAdapter.metadata(com.eddyizm.tempus.lan.LanRemoteSession.currentSong());
                setMetadata(metadata);
                setMediaInfo(metadata);
                playbackSpeedButton.setText(getString(R.string.player_playback_speed, state.getSpeed()));
                updateSleepTimerUI();
            } finally { renderingRemote = false; }
        } else {
            renderLocalOutput(outputMonitor != null ? outputMonitor.label() : getString(R.string.piterfy_device));
            buttonFavorite.setEnabled(true);
            MediaBrowser browser = getBrowser();
            if (browser != null) {
                bind.nowPlayingMediaControllerView.setPlayer(browser);
                setMediaControllerUI(browser);
                setMetadata(browser.getMediaMetadata());
                setMediaInfo(browser.getMediaMetadata());
            }
        }
    }

    private void init() {
        playerMediaCoverViewPager = bind.getRoot().findViewById(R.id.player_media_cover_view_pager);
        buttonFavorite = bind.getRoot().findViewById(R.id.button_favorite);
        playerOverflowButton = bind.getRoot().findViewById(R.id.player_overflow_button);
        playerMediaTitleLabel = bind.getRoot().findViewById(R.id.player_media_title_label);
        playerArtistNameLabel = bind.getRoot().findViewById(R.id.player_artist_name_label);
        playbackSpeedButton = bind.getRoot().findViewById(R.id.player_playback_speed_button);
        skipSilenceToggleButton = bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button);
        playerMediaExtension = bind.getRoot().findViewById(R.id.player_media_extension);
        playerMediaBitrate = bind.getRoot().findViewById(R.id.player_media_bitrate);
        playerQuickActionView = bind.getRoot().findViewById(R.id.player_quick_action_view);
        playerOpenQueueButton = bind.getRoot().findViewById(R.id.player_open_queue_button);
        playerOpenLyricsButton = bind.getRoot().findViewById(R.id.player_open_lyrics_button);
        playerTrackInfo = bind.getRoot().findViewById(R.id.player_info_track);
        songRatingBar = bind.getRoot().findViewById(R.id.song_rating_bar);
        ratingContainer = bind.getRoot().findViewById(R.id.rating_container);
        assetLinkChipGroup = bind.getRoot().findViewById(R.id.asset_link_chip_group);
        playerSongLinkChip = bind.getRoot().findViewById(R.id.asset_link_song_chip);
        playerAlbumLinkChip = bind.getRoot().findViewById(R.id.asset_link_album_chip);
        playerArtistLinkChip = bind.getRoot().findViewById(R.id.asset_link_artist_chip);
        sleepTimerContainer = bind.getRoot().findViewById(R.id.player_sleep_timer_container);
        sleepTimerButton = bind.getRoot().findViewById(R.id.player_sleep_timer_button);
        sleepTimerLabel = bind.getRoot().findViewById(R.id.player_sleep_timer_label);
        checkAndSetRatingContainerVisibility();
    }

    // ── Piterfy: haptyka przycisków i pasek z falą dźwięku ──

    private com.eddyizm.tempus.ui.components.PiterfyWaveformTimeBar waveform;
    private com.eddyizm.tempus.util.PiterfyPeaksClient.Request peaksRequest;
    private String peaksSongId;

    /** Impuls przy samym dotknięciu przycisków sterowania (bez szkła nikt inny nie obsługuje dotyku tych widoków). */
    private void attachPressHaptics() {
        int[] ids = {R.id.exo_play_pause, R.id.exo_prev, R.id.exo_next, R.id.exo_shuffle, R.id.exo_repeat_toggle,
                R.id.exo_rew, R.id.exo_ffwd, R.id.player_overflow_button, R.id.player_open_lyrics_button, R.id.player_open_queue_button};
        for (int id : ids) {
            View button = bind.getRoot().findViewById(id);
            if (button != null) com.eddyizm.tempus.util.PiterfyHaptics.attachPress(button);
        }
    }

    /** Pasek postępu z falą (tylko w układzie telefonu); w pozostałych układach zostaje zwykły pasek. */
    private void setupWaveform() {
        View bar = bind.getRoot().findViewById(R.id.exo_progress);
        waveform = bar instanceof com.eddyizm.tempus.ui.components.PiterfyWaveformTimeBar
                ? (com.eddyizm.tempus.ui.components.PiterfyWaveformTimeBar) bar : null;
        if (waveform == null) return;
        // odtworzona część fali w kolorze okładki, rozjaśnionym tak, żeby była widoczna na ciemnej karcie
        com.eddyizm.tempus.util.PiterfyAmbient.observe(getViewLifecycleOwner(), color -> {
            if (waveform != null) waveform.setAccent(com.eddyizm.tempus.util.PiterfyCoverTheme.mix(color, 0xFFFFFFFF, 0.5f));
        });
    }

    /** Prosi serwer o falę granego utworu; radio, podcasty i brak identyfikatora zostawiają zwykły pasek. */
    private void updateWaveform(MediaMetadata metadata) {
        if (waveform == null) return;
        String type = metadata.extras != null ? metadata.extras.getString("type") : null;
        String id = metadata.extras != null ? metadata.extras.getString("id") : null;
        boolean music = id != null && !id.isEmpty() && (type == null || Objects.equals(type, Constants.MEDIA_TYPE_MUSIC));
        String wanted = music ? id : null;
        if (Objects.equals(wanted, peaksSongId)) return;
        peaksSongId = wanted;
        if (peaksRequest != null) { peaksRequest.cancel(); peaksRequest = null; }
        waveform.setPeaks(null);
        if (wanted == null) return;
        peaksRequest = com.eddyizm.tempus.util.PiterfyPeaksClient.load(wanted, peaks -> {
            if (waveform != null && Objects.equals(peaksSongId, wanted)) waveform.setPeaks(peaks);
        });
    }

    private void initOverflowButton() {
        // Not available on sw600dp
        if (playerOverflowButton != null) {
            playerOverflowButton.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(requireContext(), v);
                popup.inflate(R.menu.player_overflow_menu);
                  boolean localSong = !com.eddyizm.tempus.lan.LanRemoteSession.isActive();
                  boolean currentPodcast = false;
                  try {
                      var selected = mediaBrowserListenableFuture.isDone() ? mediaBrowserListenableFuture.get().getCurrentMediaItem() : null;
                      localSong = localSong && selected != null;
                      if (selected != null) currentPodcast = com.eddyizm.tempus.util.PiterfyQueues.podcast(com.eddyizm.tempus.util.MappingUtil.mapToChild(selected));
                  } catch (Exception ignored) { localSong = false; }
                  popup.getMenu().findItem(R.id.action_more_like).setVisible(localSong && !currentPodcast);
                  popup.getMenu().findItem(R.id.action_less_like).setVisible(localSong && !currentPodcast);
                  popup.getMenu().findItem(R.id.action_podcast_subscribe).setVisible(localSong && currentPodcast);
                  // Piterfy: radio z utworu - start dla biezacego utworu albo wylaczenie dzialajacego radia
                  var radioItem = popup.getMenu().findItem(R.id.action_piterfy_radio);
                  boolean radioOn = com.eddyizm.tempus.service.PiterfyRadioManager.isActive();
                  radioItem.setVisible(radioOn || (localSong && !currentPodcast));
                  radioItem.setTitle(radioOn
                          ? getString(R.string.piterfy_radio_menu_stop, com.eddyizm.tempus.service.PiterfyRadioManager.label())
                          : getString(R.string.piterfy_radio_menu_start));

                int selectedEq = Preferences.getSelectedEqualizer();
                if (selectedEq == 0 || selectedEq == 2) {
                    popup.getMenu().removeItem(R.id.action_open_equalizer);
                }

                popup.setOnMenuItemClickListener(item -> {
                      if (item.getItemId() == R.id.action_music_queue || item.getItemId() == R.id.action_podcast_queue) {
                          boolean found = com.eddyizm.tempus.util.PiterfyQueues.restore(item.getItemId() == R.id.action_podcast_queue, mediaBrowserListenableFuture);
                          if (!found) Toast.makeText(requireContext(), "Ta kolejka jest jeszcze pusta", Toast.LENGTH_SHORT).show();
                          return true;
                      }
                      if (item.getItemId() == R.id.action_piterfy_radio) {
                          if (com.eddyizm.tempus.service.PiterfyRadioManager.isActive()) com.eddyizm.tempus.service.PiterfyRadioManager.stop();
                          else com.eddyizm.tempus.service.PiterfyRadioManager.startFromCurrent(mediaBrowserListenableFuture);
                          return true;
                      }
                      if (item.getItemId() == R.id.action_more_like || item.getItemId() == R.id.action_less_like || item.getItemId() == R.id.action_podcast_subscribe) {
                          try {
                              var current = mediaBrowserListenableFuture.get().getCurrentMediaItem();
                              if (current == null) return true;
                              var song = com.eddyizm.tempus.util.MappingUtil.mapToChild(current);
                              boolean podcast = com.eddyizm.tempus.util.PiterfyQueues.podcast(song);
                              java.util.function.Consumer<String> message = text -> { if (isAdded()) Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show(); };
                              if (item.getItemId() == R.id.action_podcast_subscribe) {
                                  if (!podcast || song.getAlbumId() == null) { message.accept("Wybierz odcinek podcastu"); return true; }
                                  var subscriptions = com.eddyizm.tempus.util.PiterfyLibrary.cached().optJSONArray("subscriptions");
                                  boolean enabled = false;
                                  if (subscriptions != null) for (int i = 0; i < subscriptions.length(); i++) if (song.getAlbumId().equals(subscriptions.optString(i))) enabled = true;
                                  final boolean next = !enabled;
                                  com.eddyizm.tempus.util.PiterfyLibrary.request(new org.json.JSONObject().put("action", "subscribe").put("id", song.getAlbumId()).put("enabled", next),
                                      state -> { message.accept(next ? "Nowe odcinki będą pobierane automatycznie przez Wi-Fi" : "Wyłączono automatyczne pobieranie audycji");
                                          if (next && isAdded()) com.eddyizm.tempus.service.PiterfyPodcastDownloads.refreshSoon(requireContext()); }, message);
                              } else if (!podcast) {
                                  com.eddyizm.tempus.ui.dialog.PiterfyFeedback.show(requireContext(), song, item.getItemId() == R.id.action_more_like ? 1 : -1, state -> message.accept("Zapisano preferencję"), message);
                              }
                          } catch (Exception ignored) { }
                          return true;
                      }
                    if (item.getItemId() == R.id.action_lan_playback) {
                        com.eddyizm.tempus.connect.ConnectDevicePicker.show(requireActivity());
                        return true;
                    }
                    if (item.getItemId() == R.id.action_open_equalizer) {
                        navigateToEqualizerFragment();
                        return true;
                    } else if (item.getItemId() == R.id.action_add_to_playlist) {
                        openAddToPlaylistDialog();
                        return true;
                    } else {
                        return false;
                    }
                });
                popup.show();
            });
        }
    }

    private void initQuickActionView() {
        playerQuickActionView.setVisibility(tvBackdrop != null || Preferences.getQuickActionVisible()
                ? View.VISIBLE : View.GONE);

        playerOpenQueueButton.setOnClickListener(view -> {
            PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) requireActivity()
                    .getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");
            if (playerBottomSheetFragment != null) {
                playerBottomSheetFragment.goToQueuePage();
            }
        });
        playerOpenLyricsButton.setOnClickListener(view -> {
            int currentItem = playerMediaCoverViewPager.getCurrentItem();
            if (currentItem == 0) {
                playerMediaCoverViewPager.setCurrentItem(1, true);
            } else if (currentItem == 1) {
                playerMediaCoverViewPager.setCurrentItem(0, true);
            }
        });
    }

    private void initTvPlayer() {
        tvBackdrop = bind.getRoot().findViewById(R.id.tv_player_backdrop);
        if (tvBackdrop == null) return;
        tvPlayerShade = bind.getRoot().findViewById(R.id.tv_player_shade);

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            tvBackdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                    30f, 30f, android.graphics.Shader.TileMode.CLAMP));
        }
        var preferences = requireContext().getSharedPreferences("piterfy_tv", android.content.Context.MODE_PRIVATE);
        tvOled = preferences.getBoolean("oled", false);
        Button displayMode = bind.getRoot().findViewById(R.id.tv_player_display_mode);
        displayMode.setOnClickListener(v -> {
            tvOled = !tvOled;
            preferences.edit().putBoolean("oled", tvOled).apply();
            renderTvMode(displayMode);
        });
        renderTvMode(displayMode);

        bind.getRoot().findViewById(R.id.tv_player_playlists).setOnClickListener(v -> {
            activity.collapseBottomSheetDelayed();
            activity.getNavigationController().getNavController().navigate(R.id.playlistCatalogueFragment);
        });
        installTvScrub();
    }

    /**
     * Przewijanie pilotem: pasek Media3 skacze domyślnie o 1/20 utworu na każde zdarzenie klawisza, a przytrzymana strzałka
     * generuje ich kilkanaście na sekundę, więc cały utwór przelatywał w sekundę. Przed każdym zdarzeniem ustawiamy krok z
     * {@link com.eddyizm.tempus.util.PiterfyTvPlan#scrubStepMs}: pierwsze naciśnięcie 5 s, a przytrzymanie przyspiesza stopniowo.
     * Sam ruch i zatwierdzenie pozycji (po krótkiej pauzie) robi nadal pasek.
     */
    private void installTvScrub() {
        View bar = bind.getRoot().findViewById(R.id.exo_progress);
        if (!(bar instanceof androidx.media3.ui.DefaultTimeBar)) return;
        androidx.media3.ui.DefaultTimeBar timeBar = (androidx.media3.ui.DefaultTimeBar) bar;
        final long[] lastEvent = {0L};
        timeBar.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT && keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            long now = event.getEventTime();
            long since = event.getRepeatCount() == 0 ? 0L : now - lastEvent[0];
            lastEvent[0] = now;
            androidx.media3.common.Player player = bind.nowPlayingMediaControllerView.getPlayer();
            long duration = player != null ? player.getDuration() : androidx.media3.common.C.TIME_UNSET;
            if (duration == androidx.media3.common.C.TIME_UNSET) duration = 0L;
            timeBar.setKeyTimeIncrement(com.eddyizm.tempus.util.PiterfyTvPlan.scrubStepMs(
                    event.getRepeatCount(), now - event.getDownTime(), since, duration));
            return false;   // resztę (ruch i zatwierdzenie) robi pasek
        });
    }

    private void renderTvMode(Button displayMode) {
        displayMode.setText(tvOled ? R.string.piterfy_tv_oled : R.string.piterfy_tv_ambient);
        tvBackdrop.setVisibility(tvOled ? View.GONE : View.VISIBLE);
        if (tvPlayerShade != null) tvPlayerShade.setVisibility(tvOled ? View.GONE : View.VISIBLE);
        ((View) tvBackdrop.getParent()).setBackgroundColor(tvOled ? 0xff000000 : 0xff080d0b);
        bind.getRoot().setBackgroundColor(tvOled ? 0xff000000 : 0xff080d0b);
    }

    private void updateTvMetadata(MediaMetadata metadata) {
        if (tvBackdrop == null) return;
        TextView album = bind.getRoot().findViewById(R.id.tv_player_album);
        album.setText(metadata.albumTitle);
        album.setVisibility(TextUtils.isEmpty(metadata.albumTitle) ? View.GONE : View.VISIBLE);
        if (!Objects.equals(tvArtwork, metadata.artworkUri)) {
            tvArtwork = metadata.artworkUri;
            com.bumptech.glide.Glide.with(this).load(tvArtwork).override(320, 180).into(tvBackdrop);
            final android.net.Uri requestedArtwork = tvArtwork;
            // The artwork itself remains a soft, atmospheric backdrop.  A sampled
            // palette gives each song a distinct TV-sized gradient, including on
            // Android versions where RenderEffect blur is unavailable.
            com.bumptech.glide.Glide.with(this).asBitmap().load(requestedArtwork).override(48, 48)
                    .into(new CustomTarget<Bitmap>() {
                        @Override public void onResourceReady(@NonNull Bitmap bitmap, Transition<? super Bitmap> transition) {
                            if (bind != null && Objects.equals(tvArtwork, requestedArtwork)) {
                                applyTvArtworkGradient(dominantArtworkColor(bitmap));
                            }
                        }

                        @Override public void onLoadCleared(android.graphics.drawable.Drawable placeholder) { }
                    });
        }
    }

    private void applyTvArtworkGradient(int color) {
        if (tvPlayerShade == null) return;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(.38f, hsv[1]);
        hsv[2] = Math.max(.32f, Math.min(.72f, hsv[2]));
        int vivid = Color.HSVToColor(hsv);
        hsv[2] = Math.max(.08f, hsv[2] * .34f);
        int shadow = Color.HSVToColor(hsv);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(205, Color.red(vivid), Color.green(vivid), Color.blue(vivid)),
                        Color.argb(238, Color.red(shadow), Color.green(shadow), Color.blue(shadow)),
                        0xff080d0b});
        gradient.setCornerRadius(0f);
        tvPlayerShade.setBackground(gradient);
    }

    /** Extracts a stable, reasonably vibrant colour without adding another dependency. */
    private int dominantArtworkColor(Bitmap bitmap) {
        long red = 0, green = 0, blue = 0, weightTotal = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < bitmap.getHeight(); y += 2) {
            for (int x = 0; x < bitmap.getWidth(); x += 2) {
                int pixel = bitmap.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);
                if (hsv[1] < .16f || hsv[2] < .12f) continue;
                int weight = Math.max(1, Math.round(hsv[1] * hsv[2] * 100));
                red += Color.red(pixel) * (long) weight;
                green += Color.green(pixel) * (long) weight;
                blue += Color.blue(pixel) * (long) weight;
                weightTotal += weight;
            }
        }
        if (weightTotal == 0) return 0xff278355;
        return Color.rgb((int) (red / weightTotal), (int) (green / weightTotal),
                (int) (blue / weightTotal));
    }

    boolean closeTvLyrics() {
        if (tvBackdrop == null || playerMediaCoverViewPager.getCurrentItem() != 1) return false;
        playerMediaCoverViewPager.setCurrentItem(0, false);
        playerOpenLyricsButton.requestFocus();
        return true;
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(),
                new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class)))
                .buildAsync();
    }

    private void releaseBrowser() {
        SleepTimerManager.getInstance().setTickListener(null);
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void bindMediaController() {
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();

                bind.nowPlayingMediaControllerView.setPlayer(com.eddyizm.tempus.lan.LanRemoteSession.isActive() ? remotePlayer : mediaBrowser);
                mediaBrowser.setShuffleModeEnabled(Preferences.isShuffleModeEnabled());
                mediaBrowser.setRepeatMode(Preferences.getRepeatMode());
                setMediaControllerListener(mediaBrowser);
                initSleepTimerButton(mediaBrowser);
                initPlaybackSpeedButton(mediaBrowser);
                refreshPlaybackDestination();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, MoreExecutors.directExecutor());
    }

    private void setMediaControllerListener(MediaBrowser mediaBrowser) {
        if (waveform != null) waveform.setPlaying(mediaBrowser.isPlaying());
        setMediaControllerUI(mediaBrowser);
        setMetadata(mediaBrowser.getMediaMetadata());
        setMediaInfo(mediaBrowser.getMediaMetadata());

        mediaBrowser.addListener(new Player.Listener() {
            @Override
            public void onMediaMetadataChanged(@NonNull MediaMetadata mediaMetadata) {
                setMediaControllerUI(mediaBrowser);
                setMetadata(mediaMetadata);
                setMediaInfo(mediaMetadata);
            }

            @Override
            public void onTracksChanged(@NonNull Tracks tracks) {
                setMediaFormatFromFileReturnedByServer();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (glassPlayer != null) glassPlayer.onPlaying(isPlaying);
                if (waveform != null) waveform.setPlaying(isPlaying);
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    setMediaFormatFromFileReturnedByServer();
                }
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                Preferences.setShuffleModeEnabled(shuffleModeEnabled);
                tintModeButtons(shuffleModeEnabled, Preferences.getRepeatMode());
            }

            @Override
            public void onRepeatModeChanged(int repeatMode) {
                Preferences.setRepeatMode(repeatMode);
                tintModeButtons(Preferences.isShuffleModeEnabled(), repeatMode);
            }
        });
    }

    /** Piterfy: jak w Spotify - wlaczone losowanie/powtarzanie swieci na zielono, wylaczone jest biale. */
    private void tintModeButtons(boolean shuffle, int repeatMode) {
        if (bind == null) return;
        int green = 0xFF1ED760, white = 0xFFFFFFFF;
        android.widget.ImageView shuffleButton = bind.nowPlayingMediaControllerView.findViewById(R.id.exo_shuffle);
        android.widget.ImageView repeatButton = bind.nowPlayingMediaControllerView.findViewById(R.id.exo_repeat_toggle);
        if (shuffleButton != null) shuffleButton.setColorFilter(shuffle ? green : white);
        if (repeatButton != null) repeatButton.setColorFilter(repeatMode != Player.REPEAT_MODE_OFF ? green : white);
    }

    private void setMetadata(MediaMetadata mediaMetadata) {
        if (bind == null || (com.eddyizm.tempus.lan.LanRemoteSession.isActive() && !renderingRemote)) return;
        updateWaveform(mediaMetadata);
        updateTvMetadata(mediaMetadata);
        String mode = mediaMetadata.extras != null ? mediaMetadata.extras.getString("piterfySource", "") : "";
        if (mode.isEmpty()) mode = com.eddyizm.tempus.service.PiterfyDJSession.sourceLabel(mediaMetadata.extras != null ? mediaMetadata.extras.getString("id") : null);
        TextView badge = bind.getRoot().findViewById(R.id.piterfy_playback_mode);
        if (badge != null) {
            badge.setText(mode.equals("DJ") ? "PITERFY DJ" : mode.toUpperCase(java.util.Locale.ROOT));
            badge.setContentDescription("Tryb odtwarzania: " + mode);
            badge.setVisibility(mode.isEmpty() ? View.GONE : View.VISIBLE);
        }
        tintModeButtons(Preferences.isShuffleModeEnabled(), Preferences.getRepeatMode());
        String type = mediaMetadata.extras != null ? mediaMetadata.extras.getString("type") : null;

        if (Objects.equals(type, Constants.MEDIA_TYPE_RADIO)) {
            // For radio: always read from extras first (radioArtist, radioTitle,
            // stationName)
            // MediaMetadata.title/artist are formatted for notification
            String stationName = mediaMetadata.extras != null
                    ? mediaMetadata.extras.getString("stationName",
                            mediaMetadata.artist != null ? String.valueOf(mediaMetadata.artist) : "")
                    : mediaMetadata.artist != null ? String.valueOf(mediaMetadata.artist) : "";

            String artist = mediaMetadata.extras != null
                    ? mediaMetadata.extras.getString("radioArtist", "")
                    : "";

            String title = mediaMetadata.extras != null
                    ? mediaMetadata.extras.getString("radioTitle", "")
                    : "";

            // Format: "Artist - Song" or fallback to title or station name
            String mainTitle;
            if (!TextUtils.isEmpty(artist) && !TextUtils.isEmpty(title)) {
                mainTitle = artist + " - " + title;
            } else if (!TextUtils.isEmpty(title)) {
                mainTitle = title;
            } else if (!TextUtils.isEmpty(artist)) {
                mainTitle = artist;
            } else {
                mainTitle = stationName;
            }

            setTextIfChanged(playerMediaTitleLabel, mainTitle);
            setTextIfChanged(playerArtistNameLabel, stationName);

            playerMediaTitleLabel.setSelected(true);
            playerArtistNameLabel.setSelected(true);

            playerMediaTitleLabel.setVisibility(!TextUtils.isEmpty(mainTitle) ? View.VISIBLE : View.GONE);
            playerArtistNameLabel.setVisibility(!TextUtils.isEmpty(stationName) ? View.VISIBLE : View.GONE);

            updateAssetLinkChips(mediaMetadata);
            return;
        }

        setTextIfChanged(playerMediaTitleLabel,
                Preferences.getTrackNumberVisible()
                        && mediaMetadata.trackNumber != null
                        && !String.valueOf(mediaMetadata.trackNumber).isEmpty()
                                ? String.format("%02d", mediaMetadata.trackNumber) + ". "
                                        + String.valueOf(mediaMetadata.title)
                                : String.valueOf(mediaMetadata.title));
        setTextIfChanged(playerArtistNameLabel,
                (mediaMetadata.artist != null
                        ? String.valueOf(mediaMetadata.artist)
                        : ""));

        playerMediaTitleLabel.setSelected(true);
        playerArtistNameLabel.setSelected(true);

        playerMediaTitleLabel.setVisibility(
                mediaMetadata.title != null && !Objects.equals(mediaMetadata.title, "") ? View.VISIBLE : View.GONE);
        playerArtistNameLabel.setVisibility(
                (mediaMetadata.artist != null && !Objects.equals(mediaMetadata.artist, ""))
                        || mediaMetadata.extras != null
                                && Objects.equals(mediaMetadata.extras.getString("type"), Constants.MEDIA_TYPE_RADIO)
                                && mediaMetadata.extras.getString("uri") != null
                                        ? View.VISIBLE
                                        : View.GONE);

        updateAssetLinkChips(mediaMetadata);
    }

    private void setTextIfChanged(TextView label, CharSequence text) {
        // Remote position updates arrive every second. Reassigning the same
        // title restarts Android's marquee before it can begin scrolling.
        if (!TextUtils.equals(label.getText(), text)) label.setText(text);
    }

    private void setMediaInfo(MediaMetadata mediaMetadata) {
        if (bind == null || (com.eddyizm.tempus.lan.LanRemoteSession.isActive() && !renderingRemote)) return;
        if (qualityClient != null && mediaMetadata.extras != null) {
            String id = mediaMetadata.extras.getString("id");
            if (id == null && getBrowser() != null && getBrowser().getCurrentMediaItem() != null) id = getBrowser().getCurrentMediaItem().mediaId;
            qualityClient.show(id, bind.piterfySourceQuality, mediaMetadata.extras.getString("suffix"), mediaMetadata.extras.getInt("bitrate", 0));
        }
        boolean isLocal = !com.eddyizm.tempus.lan.LanRemoteSession.isActive() && MusicUtil.isCurrentTrackLocal(getBrowser());

        if (mediaMetadata.extras != null) {
            String extension = mediaMetadata.extras.getString("suffix", getString(R.string.player_unknown_format));
            int rawBitrate = mediaMetadata.extras.getInt("bitrate", 0);
            String bitrate = rawBitrate != 0 ? rawBitrate + "kbps" : "Original";
            String samplingRate = mediaMetadata.extras.getInt("samplingRate", 0) != 0
                    ? new java.text.DecimalFormat("0.#").format(mediaMetadata.extras.getInt("samplingRate", 0) / 1000.0)
                            + "kHz"
                    : "";
            String bitDepth = mediaMetadata.extras.getInt("bitDepth", 0) != 0
                    ? mediaMetadata.extras.getInt("bitDepth", 0) + "b"
                    : "";

            playerMediaExtension.setText(extension);

            if (bitrate.equals("Original") && !isLocal) {
                playerMediaBitrate.setVisibility(View.GONE);
            } else {
                List<String> items = new ArrayList<>();
                if (!bitrate.trim().isEmpty())
                    items.add(bitrate);
                if (!bitDepth.trim().isEmpty())
                    items.add(bitDepth);
                if (!samplingRate.trim().isEmpty())
                    items.add(samplingRate);
                String mediaQuality = TextUtils.join(" • ", items);

                playerMediaBitrate.setVisibility(Preferences.getBitrateVisible() ? View.VISIBLE : View.GONE);
                playerMediaBitrate.setText(isLocal ? mediaQuality : mediaQuality);
            }
        }

        if (!isLocal) {
            setMediaFormatFromFileReturnedByServer();
        }

        playerTrackInfo.setOnClickListener(view -> {
            TrackInfoDialog dialog = new TrackInfoDialog(mediaMetadata);
            dialog.show(activity.getSupportFragmentManager(), null);
        });

        playerMediaExtension.setOnClickListener(v -> toggleBitrateVisibility());
        playerMediaExtension.setOnLongClickListener(v -> toggleQuickActionVisiblity());

        playerMediaBitrate.setOnClickListener(v -> toggleBitrateVisibility());
        playerMediaBitrate.setOnLongClickListener(v -> toggleQuickActionVisiblity());
    }

    private MediaBrowser getBrowser() {
        if (mediaBrowserListenableFuture == null || !mediaBrowserListenableFuture.isDone()) return null;
        try {
            return mediaBrowserListenableFuture.get();
        } catch (Exception e) {
            Log.e(TAG, "Unable to resolve media browser", e);
            return null;
        }
    }

    private void setMediaFormatFromFileReturnedByServer() {
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) return;
        if (playerMediaExtension == null) return;

        MediaBrowser browser = getBrowser();
        // Guard against local files here too: onTracksChanged also calls this, and a local
        // file's format comes from its metadata in setMediaInfo, not the decoder label.
        if (MusicUtil.isCurrentTrackLocal(browser)) return;

        Format format = MusicUtil.getCurrentAudioFormat(browser);
        if (format == null) return;

        // A renderer's unknown format says nothing about the stream, and it carries no bitrate.
        boolean renderer = UpnpPlayer.FORMAT_ID.equals(format.id);
        if (renderer && MimeTypes.AUDIO_UNKNOWN.equals(format.sampleMimeType)) return;

        String actual = MusicUtil.audioFormatLabel(format.sampleMimeType);
        // A renderer plays the server's file, not a transcoded download.
        String original = renderer
                ? MusicUtil.sourceSuffix(browser.getMediaMetadata().extras)
                : MusicUtil.getCurrentOriginalSuffix(browser);
        boolean transcoded = MusicUtil.isTranscodedFormat(actual, original);

        if (actual != null && !actual.isEmpty()) {
            playerMediaExtension.setText(transcoded
                    ? actual + " (" + getString(R.string.player_transcoding) + ")"
                    : actual);
        }

        if (Objects.equals(browser.getMediaMetadata().extras.getString("type"), Constants.MEDIA_TYPE_RADIO)) {
            playerMediaBitrate.setText((format.bitrate / 1000) + "kbps");
            playerMediaBitrate.setVisibility(Preferences.getBitrateVisible() ? View.VISIBLE : View.GONE);
        } else if (format.bitrate != Format.NO_VALUE && format.bitrate > 0) {
            if (transcoded) {
                playerMediaBitrate.setText((format.bitrate / 1000) + "kbps");
                playerMediaBitrate.setVisibility(Preferences.getBitrateVisible() ? View.VISIBLE : View.GONE);
            }
        } else if (transcoded) {
            String targetBitrate = MusicUtil.getBitratePreference();
            if (!targetBitrate.equals("0")) {
                playerMediaBitrate.setText(targetBitrate + "kbps");
                playerMediaBitrate.setVisibility(Preferences.getBitrateVisible() ? View.VISIBLE : View.GONE);
            } else {
                playerMediaBitrate.setVisibility(View.GONE);
            }
        }

    }

    private void toggleBitrateVisibility() {
        ViewGroup parent = (ViewGroup) playerMediaBitrate.getParent();

        TransitionSet transition = new TransitionSet()
                .addTransition(new Slide(Gravity.START))
                .addTransition(new ChangeBounds())
                .setDuration(500)
                .setInterpolator(new AccelerateDecelerateInterpolator());
        TransitionManager.beginDelayedTransition(parent, transition);

        playerMediaBitrate.setVisibility(Preferences.getBitrateVisible() ? View.GONE : View.VISIBLE);
        Preferences.setBitrateVisible(!Preferences.getBitrateVisible());
    }

    private boolean toggleQuickActionVisiblity() {
        if (tvBackdrop != null) return true;
        ViewGroup parent = (ViewGroup) playerQuickActionView.getParent();

        TransitionSet transition = new TransitionSet()
                .addTransition(new Slide(Gravity.BOTTOM))
                .addTransition(new ChangeBounds())
                .setDuration(500)
                .setInterpolator(new AccelerateDecelerateInterpolator());
        TransitionManager.beginDelayedTransition(parent, transition);
        playerQuickActionView.setVisibility(Preferences.getQuickActionVisible() ? View.GONE : View.VISIBLE);
        Preferences.setQuickActionVisible(!Preferences.getQuickActionVisible());
        return true;
    }

    private void updateAssetLinkChips(MediaMetadata mediaMetadata) {
        if (assetLinkChipGroup == null)
            return;
        String mediaType = mediaMetadata.extras != null
                ? mediaMetadata.extras.getString("type", Constants.MEDIA_TYPE_MUSIC)
                : Constants.MEDIA_TYPE_MUSIC;
        if (!Constants.MEDIA_TYPE_MUSIC.equals(mediaType)) {
            clearAssetLinkChip(playerSongLinkChip);
            clearAssetLinkChip(playerAlbumLinkChip);
            clearAssetLinkChip(playerArtistLinkChip);
            syncAssetLinkGroupVisibility();
            return;
        }

        String songId = mediaMetadata.extras != null ? mediaMetadata.extras.getString("id") : null;
        String albumId = mediaMetadata.extras != null ? mediaMetadata.extras.getString("albumId") : null;
        String artistId = mediaMetadata.extras != null ? mediaMetadata.extras.getString("artistId") : null;

        AssetLinkUtil.AssetLink songLink = bindAssetLinkChip(playerSongLinkChip, AssetLinkUtil.TYPE_SONG, songId);
        AssetLinkUtil.AssetLink albumLink = bindAssetLinkChip(playerAlbumLinkChip, AssetLinkUtil.TYPE_ALBUM, albumId);
        AssetLinkUtil.AssetLink artistLink = bindAssetLinkChip(playerArtistLinkChip, AssetLinkUtil.TYPE_ARTIST,
                artistId);
        bindAssetLinkView(playerMediaTitleLabel, songLink);
        bindAssetLinkView(playerArtistNameLabel, artistLink != null ? artistLink : songLink);
        bindAssetLinkView(playerMediaCoverViewPager, songLink);
        syncAssetLinkGroupVisibility();
    }

    private AssetLinkUtil.AssetLink bindAssetLinkChip(Chip chip, String type, String id) {
        if (chip == null)
            return null;
        if (TextUtils.isEmpty(id)) {
            clearAssetLinkChip(chip);
            return null;
        }

        String label = getString(AssetLinkUtil.getLabelRes(type));
        AssetLinkUtil.AssetLink assetLink = AssetLinkUtil.buildAssetLink(type, id);
        if (assetLink == null) {
            clearAssetLinkChip(chip);
            return null;
        }

        chip.setText(getString(R.string.asset_link_chip_text, label, assetLink.id));
        chip.setVisibility(View.VISIBLE);

        chip.setOnClickListener(v -> {
            if (assetLink != null) {
                activity.openAssetLink(assetLink);
            }
        });

        chip.setOnLongClickListener(v -> {
            if (assetLink != null) {
                AssetLinkUtil.copyToClipboard(requireContext(), assetLink);
                Toast.makeText(requireContext(), getString(R.string.asset_link_copied_toast, id), Toast.LENGTH_SHORT)
                        .show();
            }
            return true;
        });

        return assetLink;
    }

    private void clearAssetLinkChip(Chip chip) {
        if (chip == null)
            return;
        chip.setVisibility(View.GONE);
        chip.setText("");
        chip.setOnClickListener(null);
        chip.setOnLongClickListener(null);
    }

    private void bindAssetLinkView(View view, AssetLinkUtil.AssetLink assetLink) {
        if (view == null)
            return;
        if (assetLink == null) {
            AssetLinkUtil.clearLinkAppearance(view);
            view.setOnClickListener(null);
            view.setOnLongClickListener(null);
            view.setClickable(false);
            view.setLongClickable(false);
            return;
        }

        view.setClickable(true);
        view.setLongClickable(true);
        AssetLinkUtil.applyLinkAppearance(view);
        view.setOnClickListener(v -> {
            boolean collapse = !AssetLinkUtil.TYPE_SONG.equals(assetLink.type);
            activity.openAssetLink(assetLink, collapse);
        });
        view.setOnLongClickListener(v -> {
            AssetLinkUtil.copyToClipboard(requireContext(), assetLink);
            Toast.makeText(requireContext(), getString(R.string.asset_link_copied_toast, assetLink.id),
                    Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void syncAssetLinkGroupVisibility() {
        if (assetLinkChipGroup == null)
            return;
        boolean hasVisible = false;
        for (int i = 0; i < assetLinkChipGroup.getChildCount(); i++) {
            View child = assetLinkChipGroup.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                hasVisible = true;
                break;
            }
        }
        assetLinkChipGroup.setVisibility(hasVisible ? View.VISIBLE : View.GONE);
    }

    private void setMediaControllerUI(MediaBrowser mediaBrowser) {
        if (bind == null || com.eddyizm.tempus.lan.LanRemoteSession.isActive()) return;
        initPlaybackSpeedButton(mediaBrowser);

        if (mediaBrowser.getMediaMetadata().extras != null) {
            switch (mediaBrowser.getMediaMetadata().extras.getString("type", Constants.MEDIA_TYPE_MUSIC)) {
                case Constants.MEDIA_TYPE_PODCAST:
                    bind.nowPlayingMediaControllerView.setShowShuffleButton(false);
                    bind.nowPlayingMediaControllerView.setShowRewindButton(true);
                    bind.nowPlayingMediaControllerView.setShowPreviousButton(false);
                    bind.nowPlayingMediaControllerView.setShowNextButton(false);
                    bind.nowPlayingMediaControllerView.setShowFastForwardButton(true);
                    bind.nowPlayingMediaControllerView.setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE);
                    bind.getRoot().findViewById(R.id.player_playback_speed_button).setVisibility(View.VISIBLE);
                    bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button).setVisibility(View.VISIBLE);
                    bind.getRoot().findViewById(R.id.button_favorite).setVisibility(View.GONE);
                    setPlaybackParameters(mediaBrowser);
                    break;
                case Constants.MEDIA_TYPE_RADIO:
                    bind.nowPlayingMediaControllerView.setShowShuffleButton(false);
                    bind.nowPlayingMediaControllerView.setShowRewindButton(false);
                    bind.nowPlayingMediaControllerView.setShowPreviousButton(false);
                    bind.nowPlayingMediaControllerView.setShowNextButton(false);
                    bind.nowPlayingMediaControllerView.setShowFastForwardButton(false);
                    bind.nowPlayingMediaControllerView.setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE);
                    bind.getRoot().findViewById(R.id.player_playback_speed_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.button_favorite).setVisibility(View.GONE);
                    setPlaybackParameters(mediaBrowser);
                    break;
                case Constants.MEDIA_TYPE_MUSIC:
                default:
                    bind.nowPlayingMediaControllerView.setShowShuffleButton(true);
                    bind.nowPlayingMediaControllerView.setShowRewindButton(false);
                    bind.nowPlayingMediaControllerView.setShowPreviousButton(true);
                    bind.nowPlayingMediaControllerView.setShowNextButton(true);
                    bind.nowPlayingMediaControllerView.setShowFastForwardButton(false);
                    bind.nowPlayingMediaControllerView.setRepeatToggleModes(
                            RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL | RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE);
                    bind.getRoot().findViewById(R.id.player_playback_speed_button).setVisibility(View.VISIBLE);
                    bind.getRoot().findViewById(R.id.player_skip_silence_toggle_button).setVisibility(View.GONE);
                    bind.getRoot().findViewById(R.id.button_favorite).setVisibility(View.VISIBLE);
                    setPlaybackParameters(mediaBrowser);
                    break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Sleep timer
    // -------------------------------------------------------------------------

    /**
     * Wire up the sleep timer button click listener and connect the
     * {@link SleepTimerManager} tick callback so that:
     * - the countdown label refreshes every second, and
     * - the player pauses automatically when the timer expires.
     */
    private void initSleepTimerButton(MediaBrowser mediaBrowser) {
        sleepTimerButton.setOnClickListener(v -> {
            SleepTimerDialog dialog = new SleepTimerDialog();
            dialog.setSleepTimerListener(new SleepTimerDialog.SleepTimerListener() {
                @Override
                public void onTimerSet(int minutes) {
                    if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) { com.eddyizm.tempus.lan.LanRemoteSession.sleepTimer("minutes", minutes); return; }
                    SleepTimerManager.getInstance().startTimer(minutes);
                    connectSleepTimerTick(mediaBrowser);
                }

                @Override
                public void onTimerCancelled() {
                    if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) { com.eddyizm.tempus.lan.LanRemoteSession.sleepTimer("cancel", 0); return; }
                    SleepTimerManager.getInstance().cancelTimer();
                    updateSleepTimerUI();
                }

                @Override
                public void onEndOfTrackSet() {
                    if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) { com.eddyizm.tempus.lan.LanRemoteSession.sleepTimer("end", 0); return; }
                    SleepTimerManager.getInstance().startEndOfTrack();
                    connectSleepTimerTick(mediaBrowser);
                }
            });
            dialog.show(requireActivity().getSupportFragmentManager(), null);
        });

        connectSleepTimerTick(mediaBrowser);
    }

    /**
     * (Re-)registers the UI tick listener with {@link SleepTimerManager}.
     * Called on first bind and whenever the fragment reconnects after rotation.
     * Fade-out and pause are now handled by BaseMediaService via its own
     * ServiceActionListener — this callback only refreshes the UI label.
     */
    private void connectSleepTimerTick(MediaBrowser mediaBrowser) {
        SleepTimerManager.getInstance().setTickListener(expired -> updateSleepTimerUI());
    }

    /**
     * Refreshes the sleep timer button tint and the countdown label to
     * reflect the current timer state. Safe to call from any thread because
     * {@link SleepTimerManager} always invokes the tick listener on the
     * main thread.
     */
    private void updateSleepTimerUI() {
        if (sleepTimerButton == null || sleepTimerLabel == null)
            return;

        boolean remote = com.eddyizm.tempus.lan.LanRemoteSession.isActive();
        com.eddyizm.tempus.lan.LanRemoteState remoteState = com.eddyizm.tempus.lan.LanRemoteSession.current();
        boolean active = remote ? remoteState.getTimerActive() : SleepTimerManager.getInstance().isActive();

        if (active) {
            boolean isEndOfTrack = remote ? remoteState.getTimerEndOfTrack() : SleepTimerManager.getInstance().isEndOfTrack();
            String label = isEndOfTrack
                    ? getString(R.string.sleep_timer_end_of_track_label)
                    : remote ? remoteState.getTimerRemaining() : SleepTimerManager.getInstance().getRemainingFormatted();
            sleepTimerLabel.setText(label);
            sleepTimerLabel.setVisibility(View.VISIBLE);
            int accentColor = com.google.android.material.color.MaterialColors.getColor(
                    sleepTimerButton, com.google.android.material.R.attr.colorPrimary);
            ImageViewCompat.setImageTintList(sleepTimerButton,
                    ColorStateList.valueOf(accentColor));
        } else {
            sleepTimerLabel.setVisibility(View.GONE);
            sleepTimerLabel.setText("");
            int defaultColor = com.google.android.material.color.MaterialColors.getColor(
                    sleepTimerButton, com.google.android.material.R.attr.colorOnSurface);
            ImageViewCompat.setImageTintList(sleepTimerButton,
                    ColorStateList.valueOf(defaultColor));
        }
    }

    private void initCoverLyricsSlideView() {
        playerMediaCoverViewPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
        playerMediaCoverViewPager.setAdapter(new PlayerControllerHorizontalPager(this));
        playerMediaCoverViewPager.setUserInputEnabled(false);

        playerMediaCoverViewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);

                PlayerBottomSheetFragment playerBottomSheetFragment = (PlayerBottomSheetFragment) requireActivity()
                        .getSupportFragmentManager().findFragmentByTag("PlayerBottomSheet");

                if (position == 0) {
                    activity.setBottomSheetDraggableState(true);
                    playerMediaCoverViewPager.setUserInputEnabled(false);

                    if (playerBottomSheetFragment != null) {
                        playerBottomSheetFragment.setPlayerControllerVerticalPagerDraggableState(true);
                    }
                    updateLyricsButtonTint(false);
                    if (glassPlayer != null) glassPlayer.setPage(0);
                } else if (position == 1) {
                    activity.setBottomSheetDraggableState(true);
                    playerMediaCoverViewPager.setUserInputEnabled(true);

                    if (playerBottomSheetFragment != null) {
                        playerBottomSheetFragment.setPlayerControllerVerticalPagerDraggableState(false);
                    }
                    updateLyricsButtonTint(true);
                    if (glassPlayer != null) glassPlayer.setPage(1);
                }
            }
        });
    }

    private void updateLyricsButtonTint(boolean isLyricsActive) {
        if (playerOpenLyricsButton == null)
            return;
        int colorAttr = isLyricsActive
                ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorOnSurface;
        int color = com.google.android.material.color.MaterialColors.getColor(playerOpenLyricsButton, colorAttr);
        if (playerOpenLyricsButton instanceof android.widget.ImageView) {
            ImageViewCompat.setImageTintList((android.widget.ImageView) playerOpenLyricsButton,
                    ColorStateList.valueOf(color));
        } else {
            // TV presents this action as a readable label rather than a tiny icon.
            ((TextView) playerOpenLyricsButton).setTextColor(color);
        }
    }

    private void initMediaListenable() {
        playerBottomSheetViewModel.getLiveMedia().observe(getViewLifecycleOwner(), media -> {
            if (media != null) {
                if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()
                        && !Objects.equals(media.getId(), com.eddyizm.tempus.lan.LanRemoteSession.current().getMediaId())) return;
                buttonFavorite.setEnabled(true);
                ratingViewModel.setSong(media);
                buttonFavorite.setChecked(FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, media.getId(), media.getStarred() != null));
                buttonFavorite.setOnClickListener(v -> {
                    playerBottomSheetViewModel.setFavorite(requireContext(), media);
                    // ToggleButton przełączył się już przed wywołaniem: isChecked() to nowy stan
                    if (buttonFavorite.isChecked()) {
                        com.eddyizm.tempus.util.PiterfyHaptics.confirm(v);
                        com.eddyizm.tempus.ui.components.PiterfyHeartBurst.play(v);
                    } else {
                        com.eddyizm.tempus.util.PiterfyHaptics.tick(v);
                    }
                });
                buttonFavorite.setOnLongClickListener(v -> {   // długie przytrzymanie daje haptykę systemową (LONG_PRESS)
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.TRACK_OBJECT, media);

                    RatingDialog dialog = new RatingDialog();
                    dialog.setArguments(bundle);
                    dialog.show(requireActivity().getSupportFragmentManager(), null);

                    return true;
                });

                Integer currentRating = media.getUserRating();

                if (currentRating != null) {
                    songRatingBar.setRating(currentRating);
                } else {
                    songRatingBar.setRating(0);
                }

                songRatingBar.setOnRatingBarChangeListener(new RatingBar.OnRatingBarChangeListener() {
                    @Override
                    public void onRatingChanged(RatingBar ratingBar, float rating, boolean fromUser) {
                        if (fromUser) {
                            Integer before = media.getUserRating();
                            ratingViewModel.rate((int) rating);
                            media.setUserRating((int) rating);
                            com.eddyizm.tempus.util.PiterfyHaptics.tick(ratingBar);
                            if ((int) rating == 1 && bind != null) {
                                // 1★ oznacza „wyrzuć” (sprzątanie na serwerze), więc dajemy chwilę na pomyłkę
                                com.eddyizm.tempus.ui.components.PiterfyUndo.show(bind.getRoot(), getString(R.string.piterfy_rating_one_hint), () -> {
                                    int restored = before == null ? 0 : before;
                                    ratingViewModel.rate(restored);
                                    media.setUserRating(restored);
                                    if (songRatingBar != null) songRatingBar.setRating(restored);
                                    if (bind != null) Toast.makeText(requireContext(), R.string.piterfy_rating_restored, Toast.LENGTH_SHORT).show();
                                });
                            }
                        }
                    }
                });

                if (getActivity() != null) {
                    playerBottomSheetViewModel.refreshMediaInfo(requireActivity(), media);
                }
            }
        });
    }

    private void initMediaLabelButton() {
        playerBottomSheetViewModel.getLiveAlbum().observe(getViewLifecycleOwner(), album -> {
            if (album != null) {
                playerMediaTitleLabel.setOnClickListener(view -> {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.ALBUM_OBJECT, album);
                    NavHostFragment.findNavController(this).navigate(R.id.albumPageFragment, bundle);
                    activity.collapseBottomSheetDelayed();
                });
                setupCopyTextOnLongTap(playerMediaTitleLabel);
            }
        });
    }

    private void initArtistLabelButton() {
        playerBottomSheetViewModel.getLiveArtist().observe(getViewLifecycleOwner(), artist -> {
            if (artist != null) {
                playerArtistNameLabel.setOnClickListener(view -> {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable(Constants.ARTIST_OBJECT, artist);
                    NavHostFragment.findNavController(this).navigate(R.id.artistPageFragment, bundle);
                    activity.collapseBottomSheetDelayed();
                });
                setupCopyTextOnLongTap(playerArtistNameLabel);
            }
        });
    }

    private void setupCopyTextOnLongTap(TextView textView) {
        if (textView == null) return;
        textView.setOnLongClickListener(v -> {
            CharSequence text = textView.getText();
            if (!TextUtils.isEmpty(text)) {
                ClipboardManager clipboardManager = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clipData = ClipData.newPlainText(getString(R.string.app_name), text);
                clipboardManager.setPrimaryClip(clipData);
                Toast.makeText(requireContext(), getString(R.string.asset_link_copied_toast, text), Toast.LENGTH_SHORT).show();
            }
            return true;
        });
    }

    private void initPlaybackSpeedButton(MediaBrowser mediaBrowser) {
        playbackSpeedButton.setOnClickListener(view -> {
            PlaybackSpeedDialog dialog = new PlaybackSpeedDialog();
            dialog.setPlaybackSpeedListener(speed -> {
                applyPlaybackSpeed(speed);
            });
            dialog.show(requireActivity().getSupportFragmentManager(), null);
        });

        skipSilenceToggleButton.setOnClickListener(view -> {
            Preferences.setSkipSilenceMode(!skipSilenceToggleButton.isChecked());
        });
    }

    private void openAddToPlaylistDialog() {
        var song = playerBottomSheetViewModel.getLiveMedia().getValue();
        if (song != null) {
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(Collections.singletonList(song)));
            PlaylistChooserDialog dialog = new PlaylistChooserDialog();
            dialog.setArguments(bundle);
            dialog.show(requireActivity().getSupportFragmentManager(), null);
        }
    }

    private void navigateToEqualizerFragment() {
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) {
            Toast.makeText(requireContext(), R.string.lan_receiver_audio_settings, Toast.LENGTH_LONG).show();
            return;
        }
        NavController navController = NavHostFragment.findNavController(this);
        NavOptions navOptions = new NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setPopUpTo(R.id.equalizerFragment, true)
                .build();
        navController.navigate(R.id.equalizerFragment, null, navOptions);
        if (activity != null)
            activity.collapseBottomSheetDelayed();
    }

    public void goToControllerPage() {
        playerMediaCoverViewPager.setCurrentItem(0, false);
    }

    public void goToLyricsPage() {
        playerMediaCoverViewPager.setCurrentItem(1, true);
    }

    private void checkAndSetRatingContainerVisibility() {
        if (ratingContainer == null)
            return;

        if (Preferences.showItemStarRating()) {
            ratingContainer.setVisibility(View.VISIBLE);
        } else {
            ratingContainer.setVisibility(View.GONE);
        }
    }

    private void setPlaybackParameters(MediaBrowser mediaBrowser) {
        Button playbackSpeedButton = bind.getRoot().findViewById(R.id.player_playback_speed_button);
        float currentSpeed = Preferences.getPlaybackSpeed();
        boolean skipSilence = Preferences.isSkipSilenceMode();

        mediaBrowser.setPlaybackParameters(getPlaybackParameters(currentSpeed));
        playbackSpeedButton.setText(getString(R.string.player_playback_speed, currentSpeed));

        // TODO Skippare il silenzio
        skipSilenceToggleButton.setChecked(skipSilence);
    }

    private void resetPlaybackParameters(MediaBrowser mediaBrowser) {
        mediaBrowser.setPlaybackParameters(getPlaybackParameters(1.0f));
        // TODO Resettare lo skip del silenzio
    }

    private void applyPlaybackSpeed(float speed) {
        playbackSpeedButton.setText(getString(R.string.player_playback_speed, speed));
        if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) {
            com.eddyizm.tempus.lan.LanRemoteSession.playbackParameters(speed, getPlaybackParameters(speed).pitch);
            return;
        }

        if (mediaBrowserListenableFuture == null) {
            return;
        }

        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();
                mediaBrowser.setPlaybackParameters(getPlaybackParameters(speed));
            } catch (Exception e) {
                Log.e(TAG, "Error applying playback speed", e);
            }
        }, MoreExecutors.directExecutor());
    }

    private PlaybackParameters getPlaybackParameters(float speed) {
        float pitch = Preferences.isPlaybackSpeedPitchEnabled()
                ? getAdjustedPitch(speed)
                : 1.0f;
        return new PlaybackParameters(speed, pitch);
    }

    private float getAdjustedPitch(float speed) {
        return Preferences.isPlaybackSpeedManualPitchEnabled()
                ? Preferences.getPlaybackSpeedManualPitch()
                : speed;
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mediaServiceBinder = (MediaService.LocalBinder) service;
            isServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mediaServiceBinder = null;
            isServiceBound = false;
        }
    };

    private void bindMediaService() {
        Intent intent = new Intent(requireActivity(), MediaService.class);
        intent.setAction(MediaService.ACTION_BIND_EQUALIZER);
        requireActivity().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        isServiceBound = true;
    }

    @Override
    public void onResume() {
        super.onResume();
        bindMediaService();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isServiceBound) {
            requireActivity().unbindService(serviceConnection);
            isServiceBound = false;
        }
    }
}
