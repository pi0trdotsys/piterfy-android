package com.eddyizm.tempus.service

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real device test with silent PCM: validates mixing and handoff without contacting a music server. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PiterfyCrossfadeTest {
    @Test fun overlapsAndKeepsIncomingPosition() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences("piterfy_playback",0)
        val enabled=prefs.getBoolean("crossfade_enabled",true)
        val seconds=prefs.getInt("crossfade_seconds",5)
        val file=File(context.cacheDir,"piterfy-crossfade-test.wav")
        val samples=16000*12
        val buffer=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples*2)
        file.writeBytes(buffer.array())
        var player:ExoPlayer?=null
        var crossfade:PiterfyCrossfade?=null
        var overlap=false
        var completed=false
        try {
            instrumentation.runOnMainSync {
                prefs.edit().putBoolean("crossfade_enabled",true).putInt("crossfade_seconds",5).commit()
                val items=(0..1).map { index->
                    val extras=Bundle().apply{putString("type","music");putString("genre","Test");putInt("duration",12)}
                    MediaItem.Builder().setMediaId("crossfade-test-$index").setUri(Uri.fromFile(file))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Crossfade test").setExtras(extras).build()).build()
                }
                player=ExoPlayer.Builder(context).build().also { primary->
                    primary.volume=.2f
                    crossfade=PiterfyCrossfade(context,primary){true}
                    primary.setMediaItems(items);primary.prepare();primary.play()
                }
            }
            val deadline=SystemClock.elapsedRealtime()+25000
            while(SystemClock.elapsedRealtime()<deadline&&!completed) {
                instrumentation.runOnMainSync {
                    val p=player!!
                    if(p.currentMediaItemIndex==0&&p.isPlaying&&p.volume<.15f)overlap=true
                    if(p.currentMediaItemIndex==1&&p.isPlaying&&p.currentPosition>=5000&&p.volume>=.19f)completed=true
                    assertNull("Playback must not fail",p.playerError)
                }
                SystemClock.sleep(100)
            }
            assertTrue("Outgoing and incoming tracks must overlap",overlap)
            assertTrue("Native queue must continue beyond the already-played intro",completed)
        } finally {
            instrumentation.runOnMainSync {
                crossfade?.close();player?.release()
                prefs.edit().putBoolean("crossfade_enabled",enabled).putInt("crossfade_seconds",seconds).commit()
            }
            file.delete()
        }
    }
}
