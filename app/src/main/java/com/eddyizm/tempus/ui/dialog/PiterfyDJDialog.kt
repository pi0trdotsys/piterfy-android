package com.eddyizm.tempus.ui.dialog

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.service.PiterfyDJSession
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.PiterfyLibrary
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.util.function.Consumer

@UnstableApi
object PiterfyDJDialog {
    @JvmStatic fun show(context:Context, play:Consumer<List<Child>>, mode:String="dj"){
        val content=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16)}
        val avatar=if(mode=="dj")PiterfyDJAvatar(context).also{content.addView(it,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,(164*context.resources.displayMetrics.density).toInt()))}else null
        val intro=TextView(context).apply{text=if(mode=="dj")"Twoja audycja z polskimi zapowiedziami i nowymi gatunkami." else "Osobne pobieranie nowych utworów, niezależne od codziennego Odkrywaj."};content.addView(intro)
        val label=TextView(context);content.addView(label)
        val slider=SeekBar(context).apply{max=100;progress=PiterfyLibrary.cached().optInt("exploration",50)};content.addView(slider)
        val status=TextView(context);content.addView(status)
        val progress=com.google.android.material.progressindicator.LinearProgressIndicator(context).apply{trackCornerRadius=(8*context.resources.displayMetrics.density).toInt();trackThickness=(8*context.resources.displayMetrics.density).toInt();setIndicatorColor(android.graphics.Color.rgb(164,237,194))};content.addView(progress,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,(16*context.resources.displayMetrics.density).toInt()))
        val progressLabel=TextView(context).apply{textSize=12f};content.addView(progressLabel)
        val rows=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        val scroll=ScrollView(context).apply{addView(content)}
        val dialog=MaterialAlertDialogBuilder(context).setTitle(if(mode=="dj")"Piterfy DJ" else "Odkrywaj · nowe utwory").setView(scroll).setNegativeButton("Zamknij",null).create()
        var state=JSONObject();var starting=false;var pendingStart=false;var pendingNextIds:Set<String>?=null;var voiceToggle:MaterialButton?=null;var initialized=false
        val summaryButtons=mutableMapOf<String,MaterialButton>()
        fun playReadyBlock(block:JSONObject){
            PiterfyDJSession.cancelAwait()
            PiterfyLibrary.dj(JSONObject().put("action","select").put("mode",mode).put("session",state.optString("id")).put("block",block.optString("id")).put("device",com.eddyizm.tempus.connect.ConnectSession.deviceId()),{selected->
                state=selected;PiterfyDJSession.arm(selected);play.accept(PiterfyDJSession.decode(block.getJSONArray("tracks")));dialog.dismiss()
            },{status.text=it})
        }
        fun render(){
            if(!dialog.isShowing)return
            voiceToggle?.text=if(state.optBoolean("voice"))"Głos DJ-a: włączony" else "Głos DJ-a: wyłączony"
            for((key,b) in summaryButtons)b.text=(if(key=="summaryEmail")"Podsumowania e-mail: " else "Podsumowania Telegram: ")+(if(state.optBoolean(key,true))"włączone" else "wyłączone")
            status.text=when(state.optString("status")){"queued"->"Przygotowuję blok…";"downloading"->"Szukam i pobieram nowe utwory…";"ready"->"Blok gotowy";"no_new_tracks"->"Nowe pobrania jeszcze niedostępne; możesz słuchać gotowych bloków.";"hourly_limit"->"Limit pobierania na tę godzinę wykorzystany.";else->"Uruchom sesję lub wybierz gotowy blok."}
            state.optJSONObject("buffer")?.let{status.text="Gotowe bloki: ${it.optInt("ready")}/2 · inne klimaty: ${it.optJSONArray("alternatives")?.length()?:0}"}
            val p=state.optJSONObject("progress");val complete=p?.optBoolean("complete")==true
            progress.isIndeterminate=p==null||p.optString("stage") in listOf("queued","matching","searching")
            if(!progress.isIndeterminate)progress.setProgressCompat(p?.optInt("percent")?:0,true)
            progressLabel.text=(when(p?.optString("stage")){"matching"->"Dobieranie muzyki";"searching"->"Wyszukiwanie plików";"downloading"->"Pobieranie";"importing"->"Import do biblioteki";"voice"->"Przygotowanie zapowiedzi";"ready"->"Cały blok gotowy";"unavailable"->"Przygotowanie nie powiodło się";else->"Czekam na uruchomienie"})+(if((p?.optInt("total")?:0)>0)" · ${p!!.optInt("completed")}/${p.optInt("total")}" else "")
            val readyCount=state.optJSONObject("buffer")?.optInt("ready")?:0
            if(readyCount>0){progress.isIndeterminate=false;progress.setProgressCompat((readyCount*50).coerceAtMost(100),true);progressLabel.text="Zapas gotowych bloków · $readyCount/2"}
            rows.removeAllViews();val blocks=state.optJSONArray("blocks")?:JSONArray()
            val firstNew=if(pendingNextIds!=null)(0 until blocks.length()).firstOrNull{blocks.getJSONObject(it).optString("id") !in pendingNextIds!!}else null
            for(i in 0 until blocks.length()){
                val block=blocks.getJSONObject(i);val songs=PiterfyDJSession.decode(block.getJSONArray("tracks"))
                rows.addView(TextView(context).apply{text=block.optString("title")+"\n"+block.optString("text")+"\n"+songs.joinToString(" · "){it.artist+" — "+it.title};setPadding(0,28,0,4)})
                fun playReady(){playReadyBlock(block)}
                rows.addView(MaterialButton(context).apply{text="Odtwórz gotowy blok";isEnabled=block.optBoolean("ready",true);setOnClickListener{playReady();dialog.dismiss()}})
                for(vote in listOf(1,-1))rows.addView(MaterialButton(context).apply{text=if(vote==1)"Blok OK" else "Nie mój klimat";setOnClickListener{
                    isEnabled=false
                    PiterfyLibrary.dj(JSONObject().put("action","block-feedback").put("mode",mode).put("block",block.optString("id")).put("vote",vote),{isEnabled=true;status.text="Zapisano ocenę bloku. Uwzględnię ją w kolejnych odkryciach."},{isEnabled=true;status.text=it})
                }})
                if(firstNew==i&&block.optBoolean("ready",true)){pendingNextIds=null;playReady()}
            }
        }
        fun request(body:JSONObject){PiterfyLibrary.dj(body.put("mode",mode),{state=it;PiterfyDJSession.acceptState(it);if(body.optString("action")=="start"){starting=false;pendingStart=false;PiterfyDJSession.awaitReady(it)};if(!initialized){if(it.has("exploration"))slider.progress=it.optInt("exploration");initialized=true};render()},{status.text=it;starting=false;pendingStart=false;pendingNextIds=null})}
        fun button(text:String, action:()->Unit):MaterialButton{val b=MaterialButton(context).apply{this.text=text;setOnClickListener{action()}};content.addView(b);return b}
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(bar:SeekBar,p:Int,user:Boolean){label.text="Eksperymentowanie · $p%"}
            override fun onStartTrackingTouch(bar:SeekBar){}
            override fun onStopTrackingTouch(bar:SeekBar){request(JSONObject().put("action","settings").put("exploration",bar.progress))}
        });label.text="Eksperymentowanie · ${slider.progress}%"
        button("Uruchom"){
            if(!starting){starting=true;PiterfyDJSession.pauseForPreparation();status.text="Uruchamiam gotowy blok…";request(JSONObject().put("action","start").put("exploration",slider.progress))}
        }
        button("Zmień klimat"){
            if(!state.optBoolean("active")){status.text="Najpierw uruchom sesję DJ.";return@button}
            val blocks=state.optJSONArray("blocks")?:JSONArray()
            val alternative=(0 until blocks.length()).map{blocks.getJSONObject(it)}.firstOrNull{it.optString("variant")=="alternative"&&!it.optBoolean("consumed")&&it.optString("id")!=state.optString("currentBlock")}
                ?:(0 until blocks.length()).map{blocks.getJSONObject(it)}.firstOrNull{it.optString("id")==state.optString("startBlock")}
            if(alternative!=null){playReadyBlock(alternative)}
            else {pendingNextIds=(0 until blocks.length()).map{blocks.getJSONObject(it).optString("id")}.toSet();request(JSONObject().put("action","next"))}
        }
        if(mode=="dj")voiceToggle=button("Głos DJ-a"){if(state.optBoolean("voice"))PiterfyDJSession.cancelNarration();request(JSONObject().put("action","settings").put("voice",!state.optBoolean("voice")))}
        if(mode=="dj")button("Pomiń zapowiedź"){PiterfyDJSession.cancelNarration()}
        for(key in listOf("summaryEmail","summaryTelegram"))summaryButtons[key]=button(key){request(JSONObject().put("action","settings").put(key,!state.optBoolean(key,true)))}
        button("Zakończ sesję"){pendingStart=false;pendingNextIds=null;PiterfyDJSession.cancelAwait();PiterfyDJSession.disarm();PiterfyDJSession.cancelNarration();request(JSONObject().put("action","stop"))}
        for(vote in listOf(1,-1))button(if(vote==1)"Więcej takich · bieżący utwór" else "Mniej takich · bieżący utwór"){
            val song=PiterfyDJSession.currentSong()
            if(song==null)status.text="Odtwórz najpierw utwór na tym telefonie."
            else PiterfyLibrary.feedback(song.id,song.artist.orEmpty(),vote,{status.text="Zapisano reakcję na: ${song.title}";request(JSONObject().put("action","invalidate"))},{status.text=it})
        }
        content.addView(rows)
        val handler=Handler(Looper.getMainLooper());val poll=object:Runnable{override fun run(){if(dialog.isShowing){request(JSONObject().put("action","get"));handler.postDelayed(this,8000)}}}
        val animate=object:Runnable{override fun run(){if(dialog.isShowing){avatar?.setActive(state.optBoolean("active")&&PiterfyDJSession.visualActive());handler.postDelayed(this,500)}}}
        dialog.setOnDismissListener{handler.removeCallbacks(poll);handler.removeCallbacks(animate);avatar?.setActive(false)};dialog.show();request(JSONObject().put("action","warm"));handler.post(poll);handler.post(animate)
    }
}
