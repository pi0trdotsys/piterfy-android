package com.eddyizm.tempus.service;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadIndex;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;

import com.eddyizm.tempus.repository.DownloadRepository;
import com.eddyizm.tempus.util.DownloadUtil;
import com.eddyizm.tempus.util.MusicUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@UnstableApi
public class DownloaderManager {
    private static final String TAG = "DownloaderManager";

    private final Context context;
    private final DataSource.Factory dataSourceFactory;
    private final DownloadIndex downloadIndex;

    private static HashMap<String, Download> downloads;
    // Handed to the service and not yet finished, the map above only learns of a download once it completes.
    private static final Set<String> requested = ConcurrentHashMap.newKeySet();

    public DownloaderManager(Context context, DataSource.Factory dataSourceFactory, DownloadManager downloadManager) {
        this.context = context.getApplicationContext();
        this.dataSourceFactory = dataSourceFactory;

        downloads = new HashMap<>();
        downloadIndex = downloadManager.getDownloadIndex();

        loadDownloads();
    }

    private DownloadRequest buildDownloadRequest(MediaItem mediaItem) {
        return DownloadHelper
                .forMediaItem(
                        context,
                        mediaItem,
                        DownloadUtil.buildRenderersFactory(context, false),
                        dataSourceFactory)
                .getDownloadRequest(Util.getUtf8Bytes(checkNotNull(mediaItem.mediaId)))
                .copyWithId(mediaItem.mediaId);
    }

    public boolean isDownloaded(String mediaId) {
        @Nullable Download download = downloads.get(mediaId);
        return download != null && download.state != Download.STATE_FAILED;
    }

    public boolean isDownloaded(MediaItem mediaItem) {
        return isDownloaded(mediaItem.mediaId);
    }

    public boolean areDownloaded(List<MediaItem> mediaItems) {
        return mediaItems.stream().anyMatch(this::isDownloaded);
    }

    public boolean isRequested(String mediaId) {
        return requested.contains(mediaId);
    }

    public boolean download(MediaItem mediaItem, com.eddyizm.tempus.model.Download download) {
        return download(mediaItem, download, false);
    }

    public boolean download(MediaItem mediaItem, com.eddyizm.tempus.model.Download download, boolean foreground) {
        if (!com.eddyizm.tempus.util.PiterfyOfflinePolicy.allow(context)) return false;
        MusicUtil.applyTranscodedDownloadMetadata(download);
        download.setDownloadUri(mediaItem.requestMetadata.mediaUri.toString());
        // Marked before the send so a completion racing this call cannot leave the id behind.
        DownloadRequest request = buildDownloadRequest(mediaItem);
        requested.add(mediaItem.mediaId);
        try {
            DownloadService.sendAddDownload(context, DownloaderService.class, request, foreground);
        } catch (IllegalStateException e) {
            requested.remove(mediaItem.mediaId);
            Log.w(TAG, "Download service not started for " + mediaItem.mediaId, e);
            return false;
        }
        insertDatabase(download);
        com.eddyizm.tempus.util.PiterfyOfflinePolicy.noteDownload(context, mediaItem.mediaId);
        return true;
    }

    public int download(List<MediaItem> mediaItems, List<com.eddyizm.tempus.model.Download> downloads) {
        return download(mediaItems, downloads, false);
    }

    public int download(List<MediaItem> mediaItems, List<com.eddyizm.tempus.model.Download> downloads, boolean foreground) {
        int sent = 0;
        for (int counter = 0; counter < mediaItems.size(); counter++) {
            if (download(mediaItems.get(counter), downloads.get(counter), foreground)) sent++;
        }
        return sent;
    }

    public void remove(MediaItem mediaItem, com.eddyizm.tempus.model.Download download) {
        DownloadService.sendRemoveDownload(context, DownloaderService.class, buildDownloadRequest(mediaItem).id, false);
        deleteDatabase(download.getId());
        downloads.remove(download.getId());
    }

    public void remove(List<MediaItem> mediaItems, List<com.eddyizm.tempus.model.Download> downloads) {
        for (int counter = 0; counter < mediaItems.size(); counter++) {
            remove(mediaItems.get(counter), downloads.get(counter));
        }
    }

    public void removeAll() {
        DownloadService.sendRemoveAllDownloads(context, DownloaderService.class, false);
        deleteAllDatabase();
        DownloadUtil.eraseDownloadFolder(context);
    }
    /** Remove only managed offline data through Media3 and Room, without resolving a stream. */
    public void removeById(String id) {
        DownloadService.sendRemoveDownload(context, DownloaderService.class, id, false);
        deleteDatabase(id); downloads.remove(id); requested.remove(id);
    }

    private void loadDownloads() {
        try (DownloadCursor loadedDownloads = downloadIndex.getDownloads()) {
            while (loadedDownloads.moveToNext()) {
                Download download = loadedDownloads.getDownload();
                downloads.put(download.request.id, download);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to query downloads", e);
        }
    }

    public static String getDownloadNotificationMessage(String id) {
        com.eddyizm.tempus.model.Download download = getDownloadRepository().getDownload(id);
        return download != null ? download.getTitle() : null;
    }

    public static void updateRequestDownload(Download download) {
        updateDatabase(download.request.id);
        downloads.put(download.request.id, download);
        requested.remove(download.request.id);
    }

    public static void removeRequestDownload(Download download) {
        deleteDatabase(download.request.id);
        downloads.remove(download.request.id);
        requested.remove(download.request.id);
    }

    public static void forgetRequest(Download download) {
        requested.remove(download.request.id);
    }

    private static DownloadRepository getDownloadRepository() {
        return new DownloadRepository();
    }

    private static void insertDatabase(com.eddyizm.tempus.model.Download download) {
        getDownloadRepository().insert(download);
    }

    private static void deleteDatabase(String id) {
        getDownloadRepository().delete(id);
    }

    private static void deleteAllDatabase() {
        getDownloadRepository().deleteAll();
    }

    private static void updateDatabase(String id) {
        getDownloadRepository().update(id);
    }
}
