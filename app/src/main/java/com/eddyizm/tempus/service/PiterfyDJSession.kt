package com.eddyizm.tempus.service

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.App
import com.eddyizm.tempus.connect.ConnectSession
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.repository.QueueRepository
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.PiterfyQueues
import com.google.gson.Gson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Speech is separate from the music timeline, scrobbles and persistent queue. */
@UnstableApi
class PiterfyDJSession(private val player: Player) : Player.Listener {
    companion object {
        private var instance:PiterfyDJSession?=null
        private var pendingState:JSONObject?=null
        private var pendingAccount=""
        @JvmStatic fun acceptState(state:JSONObject){
            val account=PiterfyLibrary.account()
            if(pendingAccount!=account)pendingState=JSONObject()
            pendingAccount=account
            if(pendingState==null)pendingState=JSONObject()
            pendingState!!.put(state.optString("mode","dj"),state)
            instance?.takeIf{it.account==account}?.states?.put(state.optString("mode","dj"),state)
        }
        @JvmStatic fun cancelNarration(){instance?.let{it.finishVoice(it.player.currentMediaItem?.mediaId.orEmpty())}}
        @JvmStatic fun stopNarration(){instance?.stopVoice()}
        @JvmStatic fun isNarrating(player:Player):Boolean=instance?.let{it.player===player&&it.speaking}==true
        @JvmStatic fun currentSong():Child? = instance?.takeIf{it.local()}?.player?.currentMediaItem
            ?.takeIf{it.mediaMetadata.extras?.getString("type") !in listOf("podcast","radio") && !com.eddyizm.tempus.util.PiterfyMedia.isPodcast(it.mediaMetadata.extras?.getString("genre"))}
            ?.let{MappingUtil.mapToChild(it)}
        @JvmStatic fun decode(rows: JSONArray): List<Child> = (0 until rows.length()).mapNotNull {
            runCatching { Gson().fromJson(rows.getJSONObject(it).toString(), Child::class.java) }.getOrNull()
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var states = if(pendingAccount==PiterfyLibrary.account())pendingState?:JSONObject() else JSONObject()
    private var account = PiterfyLibrary.account()
    private var closed = false
    private var busy = false
    private var lastPoll = 0L
    private var lastRefill = 0L
    private val announcements=DJAnnouncementGate()
    private var speech: MediaPlayer? = null
    private var speechFile: File? = null
    private var speaking = false
    private var generation = 0
    private val audioManager=App.getContext().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
    private var focus:android.media.AudioFocusRequest?=null
    private val focusListener=android.media.AudioManager.OnAudioFocusChangeListener{change->if(change<0)stopVoice()}
    private val noisy=object:android.content.BroadcastReceiver(){override fun onReceive(context:android.content.Context,intent:android.content.Intent){stopVoice()}}
    @Suppress("DEPRECATION") private fun focus():Boolean {
        return if(android.os.Build.VERSION.SDK_INT>=26){
            val request=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener(focusListener,main).build()
            focus=request;audioManager.requestAudioFocus(request)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }else audioManager.requestAudioFocus(focusListener,android.media.AudioManager.STREAM_MUSIC,android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun local(): Boolean {
        if (LanRemoteSession.isActive()) return false
        val state=ConnectSession.state()
        return !ConnectSession.enabled || state == null ||
            (state.isNull("pending") && (state.isNull("owner") || state.optString("owner") == ConnectSession.deviceId()))
    }
    private fun request(body: JSONObject, done: (JSONObject)->Unit) {
        body.put("device",ConnectSession.deviceId())
        PiterfyLibrary.dj(body, {if(!closed)done(it)}, {busy=false;android.util.Log.w("PiterfyDJ","DJ request unavailable: "+body.optString("action"))})
    }
    private val tick=object:Runnable {
        override fun run(){
            if(closed)return
            if(account!=PiterfyLibrary.account()){stopVoice();states=JSONObject();announcements.clear();account=PiterfyLibrary.account();generation++}
            if(!local())stopVoice()
            if(speaking&&(states.optJSONObject("dj")?.optBoolean("voice")!=true||states.optJSONObject("dj")?.optBoolean("active")!=true))finishVoice(player.currentMediaItem?.mediaId.orEmpty())
            val now=android.os.SystemClock.elapsedRealtime()
            if(!busy&&now-lastPoll>8000){busy=true;lastPoll=now;request(JSONObject().put("action","list")){states=it;busy=false}}
            val id=player.currentMediaItem?.mediaId
            if(local()&&id!=null)for(mode in listOf("dj","discover")){
                val state=states.optJSONObject(mode)?:continue
                if(!state.optBoolean("active"))continue
                val blocks=state.optJSONArray("blocks")?:continue
                for(index in 0 until blocks.length()){
                    val block=blocks.getJSONObject(index);val tracks=block.getJSONArray("tracks")
                    if((0 until tracks.length()).none{tracks.getJSONObject(it).optString("id")==id})continue
                    if(announcements.shouldStart(block.optString("id"),id,tracks.getJSONObject(0).optString("id"),player.isPlaying,
                            player.currentPosition,state.optBoolean("voice")&&!block.isNull("voiceKey"),block.optBoolean("announced")))
                        speak(state,block,id)
                    if(tracks.getJSONObject(tracks.length()-1).optString("id")==id&&player.playbackState==Player.STATE_ENDED&&index+1<blocks.length())
                        play(blocks.getJSONObject(index+1))
                    if(player.isPlaying&&index>=blocks.length()-2&&now-lastRefill>90000&&state.optString("status") !in listOf("queued","downloading")){
                        lastRefill=now;request(JSONObject().put("action","refill").put("mode",mode)){states.put(mode,it)}
                    }
                }
            }
            main.postDelayed(this,1000)
        }
    }
    init{
        instance=this;player.addListener(this)
        androidx.core.content.ContextCompat.registerReceiver(App.getContext(),noisy,android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        main.post(tick)
    }
    private fun play(block:JSONObject){
        if(busy)return
        busy=true;val guard=generation;val expected=player.currentMediaItem?.mediaId
        val songs=decode(block.getJSONArray("tracks"))
        io.execute{
            val items=MappingUtil.mapMediaItems(songs)
            main.post{
                busy=false
                if(closed||guard!=generation||!local()||player.currentMediaItem?.mediaId!=expected||items.isEmpty())return@post
                PiterfyQueues.save(player);PiterfyCrossfade.cancelActive()
                QueueRepository().insertAll(songs,true,0)
                MediaManager.justStarted.set(true);player.setMediaItems(items);player.prepare();player.play()
            }
        }
    }
    private fun speak(state:JSONObject,block:JSONObject,id:String){
        if(!state.optBoolean("voice")||block.isNull("voiceKey")||speaking)return
        android.util.Log.d("PiterfyDJ","Claiming introduction for playable block")
        val guard=generation
        request(JSONObject().put("action","claim").put("mode",state.getString("mode")).put("block",block.getString("id"))){claim->
            if(!claim.optBoolean("speak")||closed||guard!=generation||!local()){
                android.util.Log.d("PiterfyDJ","Introduction already claimed, cancelled, or receiver changed")
                return@request
            }
            request(JSONObject().put("action","audio").put("mode",state.getString("mode")).put("key",claim.getString("voiceKey"))){data->
                if(closed||guard!=generation||!local()||player.currentMediaItem?.mediaId!=id||!player.isPlaying)return@request
                stopVoice();speaking=true;PiterfyCrossfade.cancelActive();player.pause()
                if(!focus()){android.util.Log.w("PiterfyDJ","Speech audio focus denied");finishVoice(id);return@request}
                try{
                    val file=File.createTempFile("dj-", ".wav",App.getContext().cacheDir)
                    file.writeBytes(Base64.decode(data.getString("audio"),Base64.DEFAULT));speechFile=file
                    speech=MediaPlayer().apply{
                        setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                        setDataSource(file.absolutePath);setVolume(player.volume,player.volume)
                        setOnCompletionListener{finishVoice(id)};setOnErrorListener{_,_,_->finishVoice(id);true}
                        setOnPreparedListener{if(speaking&&local()){it.start();android.util.Log.d("PiterfyDJ","Speech playback started")}else stopVoice()};prepareAsync()
                    }
                }catch(_:Exception){android.util.Log.w("PiterfyDJ","Speech playback unavailable");finishVoice(id)}
            }
        }
    }
    private fun finishVoice(id:String){
        val resume=speaking&&!closed&&local()&&player.currentMediaItem?.mediaId==id
        stopVoice();if(resume)player.play()
    }
    @Suppress("DEPRECATION") private fun stopVoice(){
        generation++;speaking=false;speech?.release();speech=null;speechFile?.delete();speechFile=null
        if(android.os.Build.VERSION.SDK_INT>=26)focus?.let{audioManager.abandonAudioFocusRequest(it)} else audioManager.abandonAudioFocus(focusListener)
        focus=null
    }
    override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int){if(speaking&&playWhenReady)stopVoice()}
    override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int){generation++;stopVoice()}
    override fun onPositionDiscontinuity(old:Player.PositionInfo,next:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)stopVoice()}
    fun close(){closed=true;generation++;stopVoice();main.removeCallbacks(tick);player.removeListener(this);io.shutdown();runCatching{App.getContext().unregisterReceiver(noisy)};if(instance===this)instance=null}
}
