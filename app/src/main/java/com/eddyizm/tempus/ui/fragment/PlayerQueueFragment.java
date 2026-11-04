package com.eddyizm.tempus.ui.fragment;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.Observer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaBrowser;
import androidx.media3.common.MediaItem;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.eddyizm.tempus.R;
import com.eddyizm.tempus.databinding.InnerFragmentPlayerQueueBinding;
import com.eddyizm.tempus.interfaces.ClickCallback;
import com.eddyizm.tempus.service.DownloaderManager;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.service.MediaService;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.subsonic.models.PlayQueue;
import com.eddyizm.tempus.ui.adapter.PlayerSongQueueAdapter;
import com.eddyizm.tempus.ui.dialog.PlaylistChooserDialog;
import com.eddyizm.tempus.util.Constants;
import com.eddyizm.tempus.util.DownloadUtil;
import com.eddyizm.tempus.util.ExternalAudioReader;
import com.eddyizm.tempus.util.ExternalAudioWriter;
import com.eddyizm.tempus.util.MappingUtil;
import com.eddyizm.tempus.util.Preferences;
import com.eddyizm.tempus.viewmodel.PlaybackViewModel;
import com.eddyizm.tempus.viewmodel.PlayerBottomSheetViewModel;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@UnstableApi
public class PlayerQueueFragment extends Fragment implements ClickCallback {
    private static final String TAG = "PlayerQueueFragment";

    private InnerFragmentPlayerQueueBinding bind;

    private com.google.android.material.floatingactionbutton.FloatingActionButton fabMenuToggle;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fabClearQueue;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fabShuffleQueue;

    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fabSaveToPlaylist;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fabDownloadAll;
    private com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton fabLoadQueue;

    private boolean isMenuOpen = false;
    private final int ANIMATION_DURATION = 250; 
    private final float FAB_VERTICAL_SPACING_DP = 70f;

    private PlayerBottomSheetViewModel playerBottomSheetViewModel;
    private PlaybackViewModel playbackViewModel;
    private ListenableFuture<MediaBrowser> mediaBrowserListenableFuture;

    private PlayerSongQueueAdapter playerSongQueueAdapter;
    private List<Child> localQueue = new ArrayList<>();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        bind = InnerFragmentPlayerQueueBinding.inflate(inflater, container, false);
        View view = bind.getRoot();

        playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);
        playbackViewModel = new ViewModelProvider(requireActivity()).get(PlaybackViewModel.class);

        fabMenuToggle = bind.fabMenuToggle;
        fabClearQueue = bind.fabClearQueue;
        fabShuffleQueue = bind.fabShuffleQueue;

        fabSaveToPlaylist = bind.fabSaveToPlaylist;
        fabDownloadAll = bind.fabDownloadAll;
        fabLoadQueue = bind.fabLoadQueue;

        fabMenuToggle.setOnClickListener(v -> toggleFabMenu());
        fabClearQueue.setOnClickListener(v -> handleClearQueueClick());
        fabShuffleQueue.setOnClickListener(v -> handleShuffleQueueClick());

        fabSaveToPlaylist.setOnClickListener(v -> handleSaveToPlaylistClick());
        fabDownloadAll.setOnClickListener(v -> handleDownloadAllClick());
        fabLoadQueue.setOnClickListener(v -> handleLoadQueueClick());

        // Hide Load Queue FAB if sync is disabled
        if (!Preferences.isSyncronizationEnabled()) {
            fabLoadQueue.setVisibility(View.GONE);
        }

        initQueueRecyclerView();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        initializeBrowser();
        MediaManager.registerPlaybackObserver(mediaBrowserListenableFuture, playbackViewModel);
        observePlayback();
    }

    @Override
    public void onResume() {
        super.onResume();
        setMediaBrowserListenableFuture();
        updateNowPlayingItem();
        mediaBrowserListenableFuture.addListener(() -> {
            try {
                long position = com.eddyizm.tempus.lan.LanRemoteSession.isActive()
                        ? com.eddyizm.tempus.lan.LanRemoteSession.current().getIndex()
                        : mediaBrowserListenableFuture.get().getCurrentMediaItemIndex();
                requireActivity().runOnUiThread(() -> {
                    bind.playerQueueRecyclerView.scrollToPosition((int) position);
                });
            } catch (Exception e) {
                Log.e("PlayerQueueFragment", "Failed to get mediaBrowserListenableFuture in onResume", e);
            }
        }, MoreExecutors.directExecutor());
    }

    @Override
    public void onStop() {
        releaseBrowser();
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind = null;
    }

    private void initializeBrowser() {
        mediaBrowserListenableFuture = new MediaBrowser.Builder(requireContext(), new SessionToken(requireContext(), new ComponentName(requireContext(), MediaService.class))).buildAsync();
    }

    private void releaseBrowser() {
        MediaBrowser.releaseFuture(mediaBrowserListenableFuture);
    }

    private void setMediaBrowserListenableFuture() {
        playerSongQueueAdapter.setMediaBrowserListenableFuture(mediaBrowserListenableFuture);
    }

    private void initQueueRecyclerView() {
        bind.playerQueueRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        bind.playerQueueRecyclerView.setHasFixedSize(true);

        // #883: the queue list draws to the bottom of the screen under the navigation bar (the
        // player sheet never gets a bottom inset). Pad the list by the bottom system bar inset,
        // read from the decor view, and let items scroll past it so the last row clears the bar.
        bind.playerQueueRecyclerView.setClipToPadding(false);
        bind.playerQueueRecyclerView.post(() -> {
            if (bind == null) return;
            WindowInsetsCompat rootInsets = ViewCompat.getRootWindowInsets(
                    requireActivity().getWindow().getDecorView());
            int navBottom = rootInsets == null ? 0
                    : rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            bind.playerQueueRecyclerView.setPadding(
                    bind.playerQueueRecyclerView.getPaddingLeft(),
                    bind.playerQueueRecyclerView.getPaddingTop(),
                    bind.playerQueueRecyclerView.getPaddingRight(),
                    navBottom);
        });

        playerSongQueueAdapter = new PlayerSongQueueAdapter(this);
        playerSongQueueAdapter.setQueueAction(this::queueAction);
        com.eddyizm.tempus.util.PiterfyLibrary.refresh(ignored -> { if(bind!=null)playerSongQueueAdapter.notifyDataSetChanged(); });
        bind.playerQueueRecyclerView.setAdapter(playerSongQueueAdapter);
        reapplyPlayback();

        playerBottomSheetViewModel.getQueueSong().observe(getViewLifecycleOwner(), queue -> {
            if (queue != null) {
                localQueue = queue.stream().map(item -> (Child) item).collect(Collectors.toList());
                if (!com.eddyizm.tempus.lan.LanRemoteSession.isActive()) playerSongQueueAdapter.setItems(new ArrayList<>(localQueue));
                reapplyPlayback();
            }
        });
        com.eddyizm.tempus.lan.LanRemoteSession.queueState().observe(getViewLifecycleOwner(), queue -> {
            if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) {
                playerSongQueueAdapter.setItems(new ArrayList<>(queue));
                reapplyPlayback();
            }
        });
        com.eddyizm.tempus.lan.LanRemoteSession.state().observe(getViewLifecycleOwner(), state -> {
            if (!state.getActive()) playerSongQueueAdapter.setItems(new ArrayList<>(localQueue));
            reapplyPlayback();
        });

        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
            int originalPosition = -1;
            int fromPosition = -1;
            int toPosition = -1;

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                if (originalPosition == -1) {
                    originalPosition = viewHolder.getBindingAdapterPosition();
                }

                fromPosition = viewHolder.getBindingAdapterPosition();
                toPosition = target.getBindingAdapterPosition();
                if(fromPosition<0||toPosition<0)return false;
                List<Child> items=playerSongQueueAdapter.getItems();
                items.add(toPosition,items.remove(fromPosition));
                recyclerView.getAdapter().notifyItemMoved(fromPosition, toPosition);

                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);

                if (originalPosition != -1 && fromPosition != -1 && toPosition != -1) {
                    MediaManager.swap(mediaBrowserListenableFuture, playerSongQueueAdapter.getItems(), originalPosition, toPosition);
                }

                originalPosition = -1;
                fromPosition = -1;
                toPosition = -1;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                queueAction("remove",viewHolder.getBindingAdapterPosition());
            }
        }).attachToRecyclerView(bind.playerQueueRecyclerView);
    }

    private void updateNowPlayingItem() {
        playerSongQueueAdapter.notifyDataSetChanged();
    }

    private void queueAction(String action,int index) {
        if(bind==null||index<0||index>=playerSongQueueAdapter.getItems().size())return;
        Child song=playerSongQueueAdapter.getItem(index);
        if(action.equals("pin")){
            com.eddyizm.tempus.util.PiterfyLibrary.pin(song.getId(),!com.eddyizm.tempus.util.PiterfyLibrary.isPinned(song.getId()),ignored->{if(bind!=null)playerSongQueueAdapter.notifyDataSetChanged();},message->{if(bind!=null)Toast.makeText(requireContext(),message,Toast.LENGTH_LONG).show();});return;
        }
        MediaManager.getCurrentIndex(mediaBrowserListenableFuture,new com.eddyizm.tempus.interfaces.MediaIndexCallback(){ @Override public void onRecovery(int current){
            if(bind==null||index>=playerSongQueueAdapter.getItems().size()||playerSongQueueAdapter.getItem(index)!=song)return;
            List<Child> items=playerSongQueueAdapter.getItems();
            if(index==current){playerSongQueueAdapter.notifyDataSetChanged();Toast.makeText(requireContext(),"Bieżący utwór pozostaje w kolejce. Wybierz następny utwór, aby go usunąć.",Toast.LENGTH_SHORT).show();return;}
            if(action.equals("next")){
                int target=index<current?current:current+1;
                if(target<0||target>=items.size())return;
                items.add(target,items.remove(index));MediaManager.swap(mediaBrowserListenableFuture,items,index,target);playerSongQueueAdapter.notifyDataSetChanged();return;
            }
            boolean remote=com.eddyizm.tempus.lan.LanRemoteSession.isActive();
            if(remote&&com.eddyizm.tempus.lan.LanRemoteSession.isConnect()){
                com.eddyizm.tempus.connect.ConnectSession.removeUndoable(index,token->{if(bind!=null)com.google.android.material.snackbar.Snackbar.make(bind.getRoot(),"Usunięto: "+song.getTitle(),com.google.android.material.snackbar.Snackbar.LENGTH_LONG).setAction("Cofnij",v->com.eddyizm.tempus.connect.ConnectSession.restoreRemoved(token)).show();});return;
            }
            List<String> expected=items.stream().map(Child::getId).collect(Collectors.toCollection(ArrayList::new));expected.remove(index);
            MediaManager.remove(mediaBrowserListenableFuture,items,index);playerSongQueueAdapter.notifyDataSetChanged();
            if(!remote)com.google.android.material.snackbar.Snackbar.make(bind.getRoot(),"Usunięto: "+song.getTitle(),com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Cofnij",v->MediaManager.restoreQueueItem(mediaBrowserListenableFuture,song,index,expected,()->{if(bind!=null)Toast.makeText(requireContext(),"Kolejka zmieniła się. Nie przywrócono starego układu.",Toast.LENGTH_SHORT).show();})).show();
        }});
    }

    @Override
    public void onMediaClick(Bundle bundle) {
        MediaManager.startQueue(mediaBrowserListenableFuture, bundle.getParcelableArrayList(Constants.TRACKS_OBJECT), bundle.getInt(Constants.ITEM_POSITION));
    }

    private void observePlayback() {
        playbackViewModel.getCurrentSongId().observe(getViewLifecycleOwner(), id -> {
            if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) { reapplyPlayback(); return; }
            if (playerSongQueueAdapter != null) {
                Boolean playing = playbackViewModel.getIsPlaying().getValue();
                playerSongQueueAdapter.setPlaybackState(id, playing != null && playing);
            }
        });
        playbackViewModel.getIsPlaying().observe(getViewLifecycleOwner(), playing -> {
            if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) { reapplyPlayback(); return; }
            if (playerSongQueueAdapter != null) {
                String id = playbackViewModel.getCurrentSongId().getValue();
                playerSongQueueAdapter.setPlaybackState(id, playing != null && playing);
            }
        });
    }

    private void reapplyPlayback() {
        if (playerSongQueueAdapter != null) {
            if (com.eddyizm.tempus.lan.LanRemoteSession.isActive()) {
                com.eddyizm.tempus.lan.LanRemoteState state = com.eddyizm.tempus.lan.LanRemoteSession.current();
                List<Child> queue = com.eddyizm.tempus.lan.LanRemoteSession.queueItems();
                String id = state.getIndex() >= 0 && state.getIndex() < queue.size() ? queue.get(state.getIndex()).getId() : null;
                playerSongQueueAdapter.setPlaybackState(id, state.getPlaying());
                return;
            }
            String id = playbackViewModel.getCurrentSongId().getValue();
            Boolean playing = playbackViewModel.getIsPlaying().getValue();
            playerSongQueueAdapter.setPlaybackState(id, playing != null && playing);
        }
    }

    /**
     * Toggles the visibility and animates all six secondary FABs.
     */
    private void toggleFabMenu() {
        if (isMenuOpen) {
            // CLOSE MENU (Reverse order for visual effect)
            if (Preferences.isSyncronizationEnabled()) {
                closeFab(fabLoadQueue, 4);
            }
            closeFab(fabSaveToPlaylist, 3); 
            closeFab(fabClearQueue, 2);
            closeFab(fabDownloadAll, 1);
            closeFab(fabShuffleQueue, 0);
            
            fabMenuToggle.animate().rotation(0f).setDuration(ANIMATION_DURATION).start();
        } else {
            // OPEN MENU (lowest index at bottom)
            openFab(fabShuffleQueue, 0);
            openFab(fabDownloadAll, 1);
            openFab(fabClearQueue, 2);
            openFab(fabSaveToPlaylist, 3);
            if (Preferences.isSyncronizationEnabled()) {
                openFab(fabLoadQueue, 4);
            }
            fabMenuToggle.animate().rotation(45f).setDuration(ANIMATION_DURATION).start();
        }
        isMenuOpen = !isMenuOpen;
    }

    private void openFab(View fab, int index) {
        final float displacement = getResources().getDisplayMetrics().density * (FAB_VERTICAL_SPACING_DP * (index + 1));
        
        fab.setVisibility(View.VISIBLE);
        fab.setAlpha(0f);
        fab.setTranslationY(displacement); // Start at the hidden (closed) position
        
        fab.animate()
        .translationY(0f)
        .alpha(1f)
        .setDuration(ANIMATION_DURATION)
        .start();
    }

    private void closeFab(View fab, int index) {
        final float displacement = getResources().getDisplayMetrics().density * (FAB_VERTICAL_SPACING_DP * (index + 1));

        fab.animate()
        .translationY(displacement)
        .alpha(0f)
        .setDuration(ANIMATION_DURATION)
        .withEndAction(() -> fab.setVisibility(View.GONE))
        .start();
    }

    private void handleShuffleQueueClick() {
        Log.d(TAG, "Shuffle Queue Clicked!");

        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();
                int startPosition = (com.eddyizm.tempus.lan.LanRemoteSession.isActive()
                        ? com.eddyizm.tempus.lan.LanRemoteSession.current().getIndex() : mediaBrowser.getCurrentMediaItemIndex()) + 1;
                int endPosition = playerSongQueueAdapter.getItems().size() - 1;

                if (startPosition < endPosition) {
                    ArrayList<Integer> pool = new ArrayList<>();

                    for (int i = startPosition; i <= endPosition; i++) {
                        pool.add(i);
                    }

                    while (pool.size() >= 2) {
                        int fromPosition = (int) (Math.random() * (pool.size()));
                        int positionA = pool.get(fromPosition);
                        pool.remove(fromPosition);

                        int toPosition = (int) (Math.random() * (pool.size()));
                        int positionB = pool.get(toPosition);
                        pool.remove(toPosition);

                        Collections.swap(playerSongQueueAdapter.getItems(), positionA, positionB);
                        bind.playerQueueRecyclerView.getAdapter().notifyItemMoved(positionA, positionB);
                    }

                    MediaManager.shuffle(mediaBrowserListenableFuture, playerSongQueueAdapter.getItems(), startPosition, endPosition);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error shuffling queue", e);
            }

            toggleFabMenu();
        }, MoreExecutors.directExecutor());
    }

    private void handleClearQueueClick() {
        Log.d(TAG, "Clear Queue Clicked!");

        mediaBrowserListenableFuture.addListener(() -> {
            try {
                MediaBrowser mediaBrowser = mediaBrowserListenableFuture.get();
                int startPosition = (com.eddyizm.tempus.lan.LanRemoteSession.isActive()
                        ? com.eddyizm.tempus.lan.LanRemoteSession.current().getIndex() : mediaBrowser.getCurrentMediaItemIndex()) + 1;
                int endPosition = playerSongQueueAdapter.getItems().size();

                MediaManager.removeRange(mediaBrowserListenableFuture, playerSongQueueAdapter.getItems(), startPosition, endPosition);
                bind.playerQueueRecyclerView.getAdapter().notifyItemRangeRemoved(startPosition, endPosition - startPosition);

            } catch (Exception e) {
                Log.e(TAG, "Error clearing queue", e);
            }

            toggleFabMenu();
        }, MoreExecutors.directExecutor());
    }

    private void handleSaveToPlaylistClick() {
        Log.d(TAG, "Save to Playlist Clicked!");

        List<Child> queueSongs = playerSongQueueAdapter.getItems();

        if (queueSongs == null || queueSongs.isEmpty()) {
            Toast.makeText(requireContext(), "Queue is empty", Toast.LENGTH_SHORT).show();
            toggleFabMenu();
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putParcelableArrayList(Constants.TRACKS_OBJECT, new ArrayList<>(queueSongs));

        PlaylistChooserDialog dialog = new PlaylistChooserDialog();
        dialog.setArguments(bundle);
        dialog.show(requireActivity().getSupportFragmentManager(), null);

        toggleFabMenu();
    }

    private void handleDownloadAllClick() {
        Log.d(TAG, "Download All Clicked!");

        List<Child> queueSongs = playerSongQueueAdapter.getItems();

        if (queueSongs == null || queueSongs.isEmpty()) {
            Toast.makeText(requireContext(), "Queue is empty", Toast.LENGTH_SHORT).show();
            toggleFabMenu();
            return;
        }

        int downloadCount = 0;
        
        if (Preferences.getDownloadDirectoryUri() == null) {
            List<MediaItem> mediaItemsToDownload = MappingUtil.mapMediaItems(queueSongs);
            List<com.eddyizm.tempus.model.Download> downloadModels = new ArrayList<>();

            for (Child child : queueSongs) {
                com.eddyizm.tempus.model.Download downloadModel =
                        new com.eddyizm.tempus.model.Download(child);
                downloadModel.setArtist(child.getArtist());
                downloadModel.setAlbum(child.getAlbum());
                downloadModel.setCoverArtId(child.getCoverArtId());
                downloadModels.add(downloadModel);
            }

            DownloaderManager downloaderManager = DownloadUtil.getDownloadTracker(requireContext());

            if (downloaderManager != null) {
                downloaderManager.download(mediaItemsToDownload, downloadModels);
                downloadCount = queueSongs.size();
                Toast.makeText(requireContext(), 
                    getResources().getQuantityString(R.plurals.songs_download_started, downloadCount, downloadCount), 
                    Toast.LENGTH_SHORT).show();
                    
                new Handler().postDelayed(() -> {
                    if (playerSongQueueAdapter != null) {
                        playerSongQueueAdapter.notifyDataSetChanged();
                    }
                }, 1000);
            } else {
                Log.e(TAG, "DownloaderManager not initialized. Check DownloadUtil.");
                Toast.makeText(requireContext(), "Download service unavailable.", Toast.LENGTH_SHORT).show();
            }
        } else {
            for (Child song : queueSongs) {
                if (ExternalAudioReader.getUri(song) == null) {
                    ExternalAudioWriter.downloadToUserDirectory(requireContext(), song);
                    downloadCount++;
                }
            }
            
            if (downloadCount > 0) {
                Toast.makeText(requireContext(),
                    getResources().getQuantityString(R.plurals.songs_download_started, downloadCount, downloadCount),
                    Toast.LENGTH_SHORT).show();
                    
                new Handler().postDelayed(() -> {
                    if (playerSongQueueAdapter != null) {
                        playerSongQueueAdapter.notifyDataSetChanged();
                    }
                }, 2000);
            } else {
                Toast.makeText(requireContext(), "All songs already downloaded", Toast.LENGTH_SHORT).show();
            }
        }
        
        toggleFabMenu();
    }

    private void handleLoadQueueClick() {
        Log.d(TAG, "Load Queue Clicked!");
        if (!Preferences.isSyncronizationEnabled()) {
            toggleFabMenu();
            return;
        }

        PlayerBottomSheetViewModel playerBottomSheetViewModel = new ViewModelProvider(requireActivity()).get(PlayerBottomSheetViewModel.class);
        
        playerBottomSheetViewModel.getPlayQueue().observe(getViewLifecycleOwner(), new Observer<PlayQueue>() {
            @Override
            public void onChanged(PlayQueue playQueue) {
                playerBottomSheetViewModel.getPlayQueue().removeObserver(this);
                
                if (playQueue != null && playQueue.getEntries() != null && !playQueue.getEntries().isEmpty()) {
                    int currentIndex = 0;
                    for (int i = 0; i < playQueue.getEntries().size(); i++) {
                        if (playQueue.getEntries().get(i).getId().equals(playQueue.getCurrent())) {
                            currentIndex = i;
                            break;
                        }
                    }
                    
                    MediaManager.startQueue(mediaBrowserListenableFuture, playQueue.getEntries(), currentIndex);
                    
                    Toast.makeText(requireContext(), "Queue loaded", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), "No saved queue found", Toast.LENGTH_SHORT).show();
                }
                
                toggleFabMenu();
            }
        });

        new Handler().postDelayed(() -> {
            if (isMenuOpen) {
                toggleFabMenu();
            }
        }, 1000);
    }
}
