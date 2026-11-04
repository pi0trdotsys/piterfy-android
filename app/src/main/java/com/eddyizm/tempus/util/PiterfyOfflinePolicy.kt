package com.eddyizm.tempus.util

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import com.eddyizm.tempus.service.DownloaderService
import com.eddyizm.tempus.service.PiterfyDJSession
import java.util.concurrent.Executors

/** Device-owned offline storage. Cleanup never touches user-selected external folders. */
@UnstableApi
object PiterfyOfflinePolicy {
    private val io=Executors.newSingleThreadExecutor()
    private fun prefs(context:Context)=context.getSharedPreferences("piterfy_offline_policy",0)
    @JvmStatic fun noteDownload(context:Context,id:String){prefs(context).edit().putString("owner:"+id,PiterfyLibrary.account()).apply()}
    @JvmStatic fun limit(context:Context)=prefs(context).getLong("limit",0)
    @JvmStatic fun setLimit(context:Context,bytes:Long){prefs(context).edit().putLong("limit",bytes).apply();enforce(context)}
    @JvmStatic fun cleanupEnabled(context:Context)=prefs(context).getBoolean("completed_podcasts",false)
    @JvmStatic fun setCleanup(context:Context,enabled:Boolean){prefs(context).edit().putBoolean("completed_podcasts",enabled).apply();if(enabled)cleanup(context)}
    @JvmStatic fun allow(context:Context):Boolean=limit(context).let{it==0L||DownloadUtil.getOfflineBytes(context)<it}
    @JvmStatic fun enforce(context:Context){
        if(!allow(context)&&!DownloadUtil.getDownloadManager(context).downloadsPaused)
            DownloadService.sendPauseDownloads(context,DownloaderService::class.java,false)
    }
    @JvmStatic fun cleanup(context:Context){
        if(!cleanupEnabled(context)||Preferences.getDownloadDirectoryUri()!=null)return
        val account=PiterfyLibrary.account();val episodes=PiterfyLibrary.cached().optJSONObject("episodes")?:return
        io.execute{
            if(account!=PiterfyLibrary.account())return@execute
            val repository=com.eddyizm.tempus.repository.DownloadRepository()
            val tracker=DownloadUtil.getDownloadTracker(context)
            for(id in episodes.keys()){
                if(account!=PiterfyLibrary.account())return@execute
                val progress=episodes.optJSONObject(id)?:continue
                val song=repository.getDownload(id)?:continue
                if(PiterfyOfflineRules.canClean(prefs(context).getString("owner:"+id,"").orEmpty(),account,
                    progress.optBoolean("completed"),progress.optLong("updatedAt"),System.currentTimeMillis(),
                    PiterfyQueues.podcast(song),PiterfyLibrary.isPinned(id),id==com.eddyizm.tempus.service.PiterfyPodcastSync.activeEpisodeId()))tracker.removeById(id)
            }
        }
    }
}
