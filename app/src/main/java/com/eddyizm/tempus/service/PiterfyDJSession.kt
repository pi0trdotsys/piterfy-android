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
        private var awaitingSession=""
        private var awaitingAccount=""
        @JvmStatic fun awaitReady(state:JSONObject){acceptState(state);awaitingSession=state.optString("id");awaitingAccount=PiterfyLibrary.account();android.util.Log.d("PiterfyDJ","Explicit start accepted; service present="+(instance!=null));instance?.startReady()}
        @JvmStatic fun cancelAwait(){awaitingSession=""}
        private fun contextPrefs()=App.getContext().getSharedPreferences("piterfy_dj_context",0)
        @JvmStatic fun arm(state:JSONObject){
            acceptState(state)
            val ids=JSONArray();val blocks=state.optJSONArray("blocks")?:JSONArray()
            for(i in 0 until blocks.length()){val tracks=blocks.getJSONObject(i).optJSONArray("tracks")?:continue;for(j in 0 until tracks.length())ids.put(tracks.getJSONObject(j).optString("id"))}
            contextPrefs().edit().putString(PiterfyLibrary.account(),state.optString("id")).putString("mode:"+PiterfyLibrary.account(),state.optString("mode")).putString("ids:"+PiterfyLibrary.account(),ids.toString()).remove("discovery:"+PiterfyLibrary.account()).apply()
        }
        @JvmStatic fun markDiscovery(songs:List<Child>){disarm();contextPrefs().edit().putString("discovery:"+PiterfyLibrary.account(),JSONArray(songs.map{it.id}).toString()).apply()}
        @JvmStatic fun disarm(){contextPrefs().edit().remove(PiterfyLibrary.account()).remove("discovery:"+PiterfyLibrary.account()).remove("mode:"+PiterfyLibrary.account()).remove("ids:"+PiterfyLibrary.account()).apply()}
        @JvmStatic fun pauseForPreparation(){cancelAwait();instance?.let{it.stopVoice();it.player.pause()};disarm()}
        private fun armed(state:JSONObject)=contextPrefs().getString(PiterfyLibrary.account(),"")==state.optString("id")
        @JvmStatic fun sourceLabel(id:String?):String{
            if(id==null)return ""
            val manual=runCatching{JSONArray(contextPrefs().getString("discovery:"+PiterfyLibrary.account(),"[]"))}.getOrDefault(JSONArray())
            if((0 until manual.length()).any{manual.optString(it)==id})return "Odkrywaj"
            val ids=runCatching{JSONArray(contextPrefs().getString("ids:"+PiterfyLibrary.account(),"[]"))}.getOrDefault(JSONArray())
            if((0 until ids.length()).any{ids.optString(it)==id})return if(contextPrefs().getString("mode:"+PiterfyLibrary.account(),"")=="dj")"DJ" else "Odkrywaj"
            val all=instance?.states?:pendingState?:return ""
            for(mode in listOf("dj","discover")){
                val s=all.optJSONObject(mode)?:continue
                if(!s.optBoolean("active")||!armed(s))continue
                val blocks=s.optJSONArray("blocks")?:continue
                for(i in 0 until blocks.length()){
                    val tracks=blocks.getJSONObject(i).optJSONArray("tracks")?:continue
                    if((0 until tracks.length()).any{tracks.getJSONObject(it).optString("id")==id})return if(mode=="dj")"DJ" else "Odkrywaj"
                }
            }
            return ""
        }
        @JvmStatic fun needsIntroduction(id:String?):Boolean=instance?.introduction(id)!=null
        @JvmStatic fun sourcePrefix(id:String?):String=sourceLabel(id).let{if(it.isEmpty())"" else "$it · "}
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
        @JvmStatic fun visualActive():Boolean=instance?.let{it.player.isPlaying||it.speaking||it.waitingForIntro}==true
        @JvmStatic fun isNarrating(player:Player):Boolean=instance?.let{it.player===player&&(it.speaking||it.waitingForIntro)}==true
        @JvmStatic fun holdQueue(songs:List<Child>,index:Int):Boolean=instance?.let{it.local()&&it.introduction(songs.getOrNull(index)?.id)!=null}==true
        @JvmStatic fun startCurrentIntroduction():Boolean=instance?.let{it.waitingForIntro||it.speaking||it.beginIntroduction()}==true
        @JvmStatic fun currentSong():Child? = instance?.takeIf{it.local()}?.player?.currentMediaItem
            ?.takeIf{it.mediaMetadata.extras?.getString("type") !in listOf("podcast","radio") && !com.eddyizm.tempus.util.PiterfyMedia.isPodcast(it.mediaMetadata.extras?.getString("genre"))}
            ?.let{MappingUtil.mapToChild(it)}
        @JvmStatic fun decode(rows: JSONArray): List<Child> = (0 until rows.length()).mapNotNull {
            runCatching { Gson().fromJson(rows.getJSONObject(it).toString(), Child::class.java) }.getOrNull()
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val preload=PiterfyDJPreload()
    private var warmedAccount=""
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
    private var waitingForIntro = false
    private var generation = 0
    private var observedBlock:JSONObject?=null
    private var observedSession=""
    private var observedMode="dj"
    private var heardBlock=false
    private fun completeObserved(){
        val block=observedBlock;observedBlock=null
        if(block!=null&&heardBlock)request(JSONObject().put("action","complete").put("mode",observedMode).put("session",observedSession).put("block",block.optString("id"))){}
        heardBlock=false
    }
    private fun observeBlock(){
        if(!local())return
        val id=player.currentMediaItem?.mediaId
        var match:JSONObject?=null;var session="";var mode="dj"
        for(kind in listOf("dj","discover")){
            val state=states.optJSONObject(kind)?:continue
            if(!state.optBoolean("active")||!armed(state))continue
            val blocks=state.optJSONArray("blocks")?:continue
            for(i in 0 until blocks.length()){
                val b=blocks.getJSONObject(i);val tracks=b.optJSONArray("tracks")?:continue
                if((0 until tracks.length()).any{tracks.getJSONObject(it).optString("id")==id}){match=b;session=state.optString("id");mode=kind}
            }
        }
        if(observedBlock!=null&&(match?.optString("id")!=observedBlock?.optString("id")||session!=observedSession))completeObserved()
        if(match!=null){
            observedBlock=match;observedSession=session;observedMode=mode
            if(player.isPlaying&&player.currentPosition>10000)heardBlock=true
            val tracks=match.getJSONArray("tracks")
            if(tracks.optJSONObject(tracks.length()-1)?.optString("id")==id&&player.playbackState==Player.STATE_ENDED)completeObserved()
        }
    }
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
    private fun request(body: JSONObject, failed:()->Unit={}, done: (JSONObject)->Unit) {
        body.put("device",ConnectSession.deviceId())
        PiterfyLibrary.dj(body, {if(!closed)done(it)}, {busy=false;android.util.Log.w("PiterfyDJ","DJ request unavailable: "+body.optString("action"));if(!closed)failed()})
    }
    private fun startReady(){
        if(closed||busy||!local()||awaitingSession.isEmpty()||awaitingAccount!=account)return
        for(mode in listOf("dj","discover")){
            val state=states.optJSONObject(mode)?:continue
            if(state.optString("id")!=awaitingSession)continue
            if(!state.optBoolean("active")){cancelAwait();return}
            val blocks=state.optJSONArray("blocks")?:continue
            val ready=(0 until blocks.length()).map{blocks.getJSONObject(it)}.firstOrNull{it.optString("id")==state.optString("startBlock")&&it.optBoolean("ready",true)}?:continue
            cancelAwait();arm(state)
            android.util.Log.d("PiterfyDJ","Starting explicitly requested ready block")
            play(ready);return
        }
    }
    private val tick=object:Runnable {
        override fun run(){
            if(closed)return
            if(account!=PiterfyLibrary.account()){observedBlock=null;heardBlock=false;stopVoice();states=JSONObject();announcements.clear();account=PiterfyLibrary.account();generation++}
            if(!local())stopVoice()
            if((speaking||waitingForIntro)&&(states.optJSONObject("dj")?.optBoolean("voice")!=true||states.optJSONObject("dj")?.optBoolean("active")!=true))finishVoice(player.currentMediaItem?.mediaId.orEmpty())
            val now=android.os.SystemClock.elapsedRealtime()
            if(warmedAccount!=account){warmedAccount=account;for(mode in listOf("dj","discover"))request(JSONObject().put("action","warm").put("mode",mode)){states.put(mode,it)}}
            // Consume an explicit ready start before another poll can mark the service busy.
            startReady()
            if(!busy&&now-lastPoll>(if(awaitingSession.isNotEmpty())1000 else 8000)){busy=true;lastPoll=now;request(JSONObject().put("action","list")){states=it;busy=false;startReady()}}
            val id=player.currentMediaItem?.mediaId
            preload.update(states,id,local())
            observeBlock()
            if(local()&&id!=null)for(mode in listOf("dj","discover")){
                val state=states.optJSONObject(mode)?:continue
                if(!state.optBoolean("active")||!armed(state))continue
                val blocks=state.optJSONArray("blocks")?:continue
                for(index in 0 until blocks.length()){
                    val block=blocks.getJSONObject(index);val tracks=block.getJSONArray("tracks")
                    if((0 until tracks.length()).none{tracks.getJSONObject(it).optString("id")==id})continue
                    // Introductions are started explicitly before play(), never by interrupting music.
                    if(tracks.getJSONObject(tracks.length()-1).optString("id")==id&&player.playbackState==Player.STATE_ENDED){
                        val next=(0 until blocks.length()).map{blocks.getJSONObject(it)}.firstOrNull{it.optString("id")==state.optString("startBlock")}
                        if(next!=null)play(next)
                    }
                    if(player.isPlaying&&now-lastRefill>30000){
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
        val state=listOf("dj","discover").mapNotNull{states.optJSONObject(it)}.firstOrNull{s->val rows=s.optJSONArray("blocks");rows!=null&&(0 until rows.length()).any{rows.getJSONObject(it).optString("id")==block.optString("id")}}?:return
        busy=true
        request(JSONObject().put("action","select").put("mode",state.optString("mode")).put("session",state.optString("id")).put("block",block.optString("id")),failed={busy=false}){
            states.put(it.optString("mode"),it);busy=false;playSelected(block)
        }
    }
    private fun playSelected(block:JSONObject){
        for(mode in listOf("dj","discover")){
            val s=states.optJSONObject(mode)?:continue;val blocks=s.optJSONArray("blocks")?:continue
            if((0 until blocks.length()).any{blocks.getJSONObject(it).optString("id")==block.optString("id")}){arm(s);break}
        }
        busy=true;val guard=generation;val expected=player.currentMediaItem?.mediaId
        val songs=decode(block.getJSONArray("tracks"))
        io.execute{
            val items=MappingUtil.mapMediaItems(songs)
            main.post{
                busy=false
                if(closed||guard!=generation||!local()||player.currentMediaItem?.mediaId!=expected||items.isEmpty())return@post
                PiterfyQueues.save(player);PiterfyCrossfade.cancelActive();player.pause()
                QueueRepository().insertAll(songs,true,0)
                MediaManager.justStarted.set(true);player.setMediaItems(items);player.prepare();if(!beginIntroduction())player.play()
            }
        }
    }
    private fun introduction(id:String?):Pair<JSONObject,JSONObject>?{
        if(id==null)return null
        for(mode in listOf("dj","discover")){
            val state=states.optJSONObject(mode)?:continue
            if(!state.optBoolean("active")||!state.optBoolean("voice")||!armed(state))continue
            val blocks=state.optJSONArray("blocks")?:continue
            for(i in 0 until blocks.length()){
                val block=blocks.getJSONObject(i);val tracks=block.optJSONArray("tracks")?:continue
                if(!block.optBoolean("ready",true))continue
                if(tracks.optJSONObject(0)?.optString("id")==id&&!block.isNull("voiceKey")&&!block.optBoolean("announced"))return state to block
                val clips=block.optJSONArray("announcements")?:continue
                for(j in 0 until clips.length()){
                    val clip=clips.getJSONObject(j)
                    if(clip.optString("track")==id&&!clip.isNull("voiceKey")&&!clip.optBoolean("announced"))return state to JSONObject(clip.toString()).put("id",block.optString("id")).put("track",id)
                }
            }
        }
        return null
    }
    private fun beginIntroduction():Boolean{
        if(closed||!local()||player.playWhenReady||player.currentPosition>1500)return false
        val id=player.currentMediaItem?.mediaId?:return false
        val (state,block)=introduction(id)?:return false
        if(!announcements.shouldStart(block.optString("id")+":"+id,id,id,true,0,true,false))return false
        stopVoice();waitingForIntro=true;player.pause();speak(state,block,id);return true
    }
    private fun speak(state:JSONObject,block:JSONObject,id:String){
        android.util.Log.d("PiterfyDJ","Claiming introduction for playable block")
        val guard=generation
        request(JSONObject().put("action","claim").put("mode",state.getString("mode")).put("block",block.getString("id")).put("track",id),failed={if(guard==generation)finishVoice(id)}){claim->
            if(closed||guard!=generation||!local())return@request
            if(!claim.optBoolean("speak")){
                android.util.Log.d("PiterfyDJ","Introduction already claimed, cancelled, or receiver changed")
                finishVoice(id)
                return@request
            }
            val receive:(JSONObject)->Unit=receive@{data->
                if(!closed&&guard==generation&&local()&&player.currentMediaItem?.mediaId==id&&waitingForIntro){
                waitingForIntro=false;speaking=true;PiterfyCrossfade.cancelActive();player.pause()
                if(!focus()){android.util.Log.w("PiterfyDJ","Speech audio focus denied");finishVoice(id);return@receive}
                try{
                    val file=File.createTempFile("dj-", ".wav",App.getContext().cacheDir)
                    file.writeBytes(Base64.decode(data.getString("audio"),Base64.DEFAULT));speechFile=file
                    speech=MediaPlayer().apply{
                        setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                        setDataSource(file.absolutePath);setVolume(player.volume,player.volume)
                        setOnCompletionListener{finishVoice(id)};setOnErrorListener{_,_,_->finishVoice(id);true}
                        setOnPreparedListener{if(speaking&&local()){it.start();android.util.Log.d("PiterfyDJ","Speech started before music; position="+player.currentPosition+", musicPlaying="+player.isPlaying)}else stopVoice()};prepareAsync()
                    }
                }catch(_:Exception){android.util.Log.w("PiterfyDJ","Speech playback unavailable");finishVoice(id)}
                }
            }
            val cached=preload.cachedVoice(claim.getString("voiceKey"))
            if(cached!=null)receive(JSONObject().put("audio",Base64.encodeToString(cached,Base64.NO_WRAP)))
            else request(JSONObject().put("action","audio").put("mode",state.getString("mode")).put("key",claim.getString("voiceKey")),failed={if(guard==generation)finishVoice(id)},done=receive)
        }
    }
    private fun finishVoice(id:String){
        val resume=(speaking||waitingForIntro)&&!closed&&local()&&player.currentMediaItem?.mediaId==id
        stopVoice();if(resume)player.play()
    }
    @Suppress("DEPRECATION") private fun stopVoice(){
        generation++;speaking=false;waitingForIntro=false;speech?.release();speech=null;speechFile?.delete();speechFile=null
        if(android.os.Build.VERSION.SDK_INT>=26)focus?.let{audioManager.abandonAudioFocusRequest(it)} else audioManager.abandonAudioFocus(focusListener)
        focus=null
    }
    override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int){if((speaking||waitingForIntro)&&playWhenReady)stopVoice()}
    override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int){
        stopVoice()
        if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)cancelAwait()
        val id=item?.mediaId
        if((reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO||reason==Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)&&player.playWhenReady&&local()&&introduction(id)!=null){
            PiterfyCrossfade.cancelActive();player.pause()
            main.post{if(!closed&&local()&&player.currentMediaItem?.mediaId==id&&!player.playWhenReady){if(!beginIntroduction())player.play()}}
        }
    }
    override fun onPositionDiscontinuity(old:Player.PositionInfo,next:Player.PositionInfo,reason:Int){if(reason==Player.DISCONTINUITY_REASON_SEEK)stopVoice()}
    fun close(){closed=true;generation++;stopVoice();preload.close();main.removeCallbacks(tick);player.removeListener(this);io.shutdown();runCatching{App.getContext().unregisterReceiver(noisy)};if(instance===this)instance=null}
}
