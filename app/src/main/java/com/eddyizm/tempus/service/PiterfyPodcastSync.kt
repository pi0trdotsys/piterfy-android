package com.eddyizm.tempus.service

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.PiterfyMedia
import org.json.JSONObject

/** Podcast progress shared with the web player; optimistic revisions reject stale devices. */
@UnstableApi
class PiterfyPodcastSync(private val player: Player) : Player.Listener {
    private val handler=Handler(Looper.getMainLooper())
    private var closed=false
    private var seekVersion=0
    private var saving=false
    private val tick=object:Runnable {
        override fun run(){if(closed)return; if(player.isPlaying)save(player.currentMediaItem,player.currentPosition);handler.postDelayed(this,15000)}
    }
    init{player.addListener(this);handler.postDelayed(tick,15000)}
    private fun podcast(item:MediaItem?)=item?.mediaMetadata?.extras?.getString("type")=="podcast"||PiterfyMedia.isPodcast(item?.mediaMetadata?.extras?.getString("genre"))
    private fun save(item:MediaItem?,position:Long){
        if(!podcast(item)||item==null||saving||position<3000||com.eddyizm.tempus.lan.LanRemoteSession.isActive())return
        val duration=(item.mediaMetadata.extras?.getInt("duration")?:0)*1000L
        if(duration<=0)return
        val revision=PiterfyLibrary.cached().optJSONObject("episodes")?.optJSONObject(item.mediaId)?.optInt("revision",0)?:0
        saving=true
        PiterfyLibrary.request(JSONObject().put("action","progress").put("id",item.mediaId)
            .put("position",position.coerceIn(0,duration)).put("duration",duration).put("revision",revision),
            {result->saving=false;if(result.optBoolean("conflict"))PiterfyLibrary.refresh(null)}, {saving=false})
    }
    override fun onMediaItemTransition(item:MediaItem?,reason:Int){
        if(!podcast(item)||item==null)return
        val identity=PiterfyLibrary.account();val id=item.mediaId;val guard=++seekVersion
        PiterfyLibrary.refresh {state->
            if(closed||identity!=PiterfyLibrary.account()||guard!=seekVersion||player.currentMediaItem?.mediaId!=id)return@refresh
            val progress=state.optJSONObject("episodes")?.optJSONObject(id)
            if(progress!=null&&!progress.optBoolean("completed")&&player.currentPosition<5000){
                val position=progress.optLong("position")
                if(position>5000)player.seekTo(position)
            }
        }
    }
    override fun onPositionDiscontinuity(old:Player.PositionInfo,next:Player.PositionInfo,reason:Int){
        seekVersion++
        if(old.mediaItem?.mediaId!=next.mediaItem?.mediaId)save(old.mediaItem,old.positionMs)
    }
    override fun onIsPlayingChanged(playing:Boolean){if(!playing)save(player.currentMediaItem,player.currentPosition)}
    fun close(){save(player.currentMediaItem,player.currentPosition);closed=true;handler.removeCallbacks(tick);player.removeListener(this)}
}
