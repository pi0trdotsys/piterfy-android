package com.eddyizm.tempus.service

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Base64
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheWriter
import com.eddyizm.tempus.App
import com.eddyizm.tempus.connect.ConnectSession
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.Preferences
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Small, disposable look-ahead buffer. Does not mark songs as downloaded/offline. */
@UnstableApi
class PiterfyDJPreload {
    private val io=Executors.newSingleThreadExecutor()
    private var account=PiterfyLibrary.account()
    private var signature=""
    private var last=0L
    @Volatile private var generation=0
    @Volatile private var writer:CacheWriter?=null
    private var voiceBusy=false
    private val folder=File(App.getContext().cacheDir,"piterfy-dj-voice").apply{mkdirs()}
    private fun voiceFile(key:String):File? {
        if(!key.matches(Regex("[0-9a-f]{64}")))return null
        val owner=MessageDigest.getInstance("SHA-256").digest(PiterfyLibrary.account().toByteArray()).joinToString(""){"%02x".format(it)}
        return File(folder,"$owner-$key.wav")
    }
    fun cachedVoice(key:String):ByteArray?=runCatching{voiceFile(key)?.takeIf{it.exists()&&it.lastModified()>System.currentTimeMillis()-86400000}?.readBytes()}.getOrNull()
    fun update(states:JSONObject,current:String?,local:Boolean){
        if(account!=PiterfyLibrary.account()){cancel();account=PiterfyLibrary.account();signature=""}
        val network=App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if(!local||network.isActiveNetworkMetered){cancel();return}
        val targets=mutableListOf<Pair<String,JSONObject>>()
        for(mode in listOf("dj","discover")){
            val s=states.optJSONObject(mode)?:continue
            val blocks=s.optJSONArray("blocks")?:continue
            for(i in 0 until blocks.length()){
                val b=blocks.getJSONObject(i)
                if(b.optBoolean("consumed")||!b.optBoolean("ready",true))continue
                if(b.optString("id")==s.optString("currentBlock")||b.optString("id")==s.optString("startBlock")||b.optString("variant")=="alternative")targets.add(mode to b)
            }
        }
        val songs=mutableListOf<com.eddyizm.tempus.subsonic.models.Child>()
        val voices=mutableListOf<Pair<String,String>>()
        for((mode,b) in targets){
            val tracks=PiterfyDJSession.decode(b.optJSONArray("tracks")?:continue)
            val index=tracks.indexOfFirst{it.id==current}
            songs.addAll(if(index>=0)tracks.drop(index+1).take(1) else tracks.take(1))
            if(!b.optBoolean("announced")&&!b.isNull("voiceKey"))voices.add(mode to b.optString("voiceKey"))
            val clips=b.optJSONArray("announcements")
            if(index>=0&&clips!=null)for(j in 0 until clips.length()){
                val clip=clips.getJSONObject(j)
                if(clip.optString("track")==tracks.getOrNull(index+1)?.id&&!clip.optBoolean("announced")&&!clip.isNull("voiceKey"))voices.add(mode to clip.optString("voiceKey"))
            }
        }
        if(!voiceBusy){
            val next=voices.firstOrNull{voiceFile(it.second)?.let{file->file.exists()&&file.lastModified()>System.currentTimeMillis()-86400000}!=true}
            if(next!=null){
                voiceBusy=true;val expected=account;val guard=generation
                PiterfyLibrary.dj(JSONObject().put("action","audio").put("mode",next.first).put("key",next.second).put("device",ConnectSession.deviceId()),{data->
                    voiceBusy=false
                    if(expected==PiterfyLibrary.account()&&guard==generation)io.execute{runCatching{
                        val bytes=Base64.decode(data.getString("audio"),Base64.DEFAULT)
                        if(bytes.size<=2*1024*1024&&expected==PiterfyLibrary.account()&&guard==generation){voiceFile(next.second)?.writeBytes(bytes);trim()}
                    }}
                },{voiceBusy=false})
            }
        }
        if(Preferences.getStreamingCacheSize()<=0)return
        val unique=songs.distinctBy{it.id}.take(3)
        val key=unique.joinToString{it.id}
        val now=SystemClock.elapsedRealtime()
        if(key==signature&&now-last<60000)return
        cancel();signature=key;last=now
        val expected=account;val guard=generation
        io.execute{
            for(item in MappingUtil.mapMediaItems(unique)){
                if(guard!=generation||expected!=PiterfyLibrary.account())break
                val uri=item.localConfiguration?.uri?:continue
                if(uri.scheme!="https")continue
                // Unknown-length transcoding output cannot safely resume a byte prefix.
                // The existing QueuePreloader can still cache whole transcoded tracks.
                if(uri.getQueryParameter("format")!="raw")continue
                runCatching{
                    val spec=DataSpec.Builder().setUri(uri).setLength(512L*1024).build()
                    val task=CacheWriter(DownloadUtil.getStreamingCacheWriterFactory(App.getContext()).createDataSource(),spec,null,null)
                    writer=task
                    if(guard==generation)task.cache()
                }
                writer=null
            }
        }
    }
    private fun trim(){
        val files=folder.listFiles()?.sortedBy{it.lastModified()}.orEmpty()
        var total=files.sumOf{it.length()}
        for(file in files)if(total>12*1024*1024||file.lastModified()<System.currentTimeMillis()-86400000){total-=file.length();file.delete()}
    }
    fun cancel(){generation++;writer?.cancel();writer=null}
    fun close(){cancel();io.shutdownNow()}
}
