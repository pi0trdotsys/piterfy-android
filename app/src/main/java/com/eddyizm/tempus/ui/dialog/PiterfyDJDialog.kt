package com.eddyizm.tempus.ui.dialog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.connect.ConnectSession
import com.eddyizm.tempus.service.PiterfyDJSession
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.PiterfyLibrary
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.util.function.Consumer

@UnstableApi
object PiterfyDJDialog {
    @JvmStatic fun show(context:Context, play:Consumer<List<Child>>, mode:String="dj"){
        fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
        val content=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(24))}
        val ink=MaterialColors.getColor(content,com.google.android.material.R.attr.colorOnSurface,Color.WHITE)
        val muted=MaterialColors.getColor(content,com.google.android.material.R.attr.colorOnSurfaceVariant,Color.LTGRAY)
        val accent=MaterialColors.getColor(content,com.google.android.material.R.attr.colorPrimary,Color.rgb(164,237,194))
        fun label(value:String,size:Float=14f,strong:Boolean=false)=TextView(context).apply{
            text=value;textSize=size;setTextColor(if(strong)ink else muted)
            if(strong)setTypeface(typeface,Typeface.BOLD)
            setPadding(0,dp(6),0,dp(6))
        }
        fun column()=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
        fun button(parent:LinearLayout,title:String,primary:Boolean=false,action:()->Unit):MaterialButton{
            val style=if(primary)com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle
            return MaterialButton(context,null,style).apply{
                text=title;isAllCaps=false;minHeight=dp(48);minimumHeight=dp(48);cornerRadius=dp(24);textSize=13f
                if(primary){backgroundTintList=ColorStateList.valueOf(accent);setTextColor(MaterialColors.getColor(content,com.google.android.material.R.attr.colorOnPrimary,Color.BLACK))}
                parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4);bottomMargin=dp(4)})
                setOnClickListener{action()}
            }
        }
        val avatar=if(mode=="dj")PiterfyDJAvatar(context).also{content.addView(it,LinearLayout.LayoutParams(-1,dp(112)))}else null
        content.addView(label(if(mode=="dj")"Znajome brzmienia. Nowe kierunki. Twój DJ." else "Nowa muzyka, gotowa do słuchania."))
        val status=label("").apply{accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};content.addView(status)
        var noticeAt=0L
        fun notice(value:String){noticeAt=SystemClock.elapsedRealtime();if(status.text.toString()!=value)status.text=value}
        val progress=LinearProgressIndicator(context).apply{trackCornerRadius=dp(4);trackThickness=dp(5);setIndicatorColor(accent)}
        content.addView(progress,LinearLayout.LayoutParams(-1,dp(12)))
        val progressLabel=label("Przygotowuję propozycje…",12f);content.addView(progressLabel)
        val transport=column();content.addView(transport)
        val feedback=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL};content.addView(feedback)
        val settings=column().apply{visibility=View.GONE}
        val settingsToggle=button(content,"Ustawienia audycji  ＋"){}
        settingsToggle.setOnClickListener{
            val expanded=settings.visibility!=View.VISIBLE;settings.visibility=if(expanded)View.VISIBLE else View.GONE
            settingsToggle.text=if(expanded)"Ustawienia audycji  −" else "Ustawienia audycji  ＋"
            settingsToggle.contentDescription=if(expanded)"Zwiń ustawienia audycji" else "Rozwiń ustawienia audycji"
        }
        content.addView(settings)
        val explorationLabel=label("Eksperymentowanie",14f,true);settings.addView(explorationLabel)
        val slider=SeekBar(context).apply{max=100;this.progress=PiterfyLibrary.cached().optInt("exploration",50);contentDescription="Eksperymentowanie DJ-a";minimumHeight=dp(48)};settings.addView(slider)
        settings.addView(label("Znajome brzmienia ← → Odważne odkrycia",12f))
        val rows=column();content.addView(rows)
        val scroll=ScrollView(context).apply{isFillViewport=true;addView(content)}
        val dialog=MaterialAlertDialogBuilder(context).setTitle(if(mode=="dj")"Piterfy DJ" else "Odkrywaj").setView(scroll).setNegativeButton("Zamknij",null).create()
        var state=JSONObject();var starting=false;var pendingNextIds:Set<String>?=null;var initialized=false
        var historyOpen=false;var rendered="";val expandedTracks=mutableSetOf<String>()
        var primary:MaterialButton?=null;var voiceToggle:MaterialButton?=null;var talkToggle:MaterialButton?=null;var skip:MaterialButton?=null
        val summaryButtons=mutableMapOf<String,MaterialButton>()
        fun playBlock(block:JSONObject){
            PiterfyDJSession.cancelAwait()
            PiterfyLibrary.dj(JSONObject().put("action","select").put("mode",mode).put("session",state.optString("id")).put("block",block.optString("id")).put("device",ConnectSession.deviceId()),{selected->
                state=selected;PiterfyDJSession.arm(selected);play.accept(PiterfyDJSession.protectedTracks(block.getJSONArray("tracks")));dialog.dismiss()
            },{notice(it)})
        }
        lateinit var render:()->Unit
        fun request(body:JSONObject){PiterfyLibrary.dj(body.put("mode",mode),{
            state=it;PiterfyDJSession.acceptState(it)
            if(body.optString("action")=="start"){starting=false;PiterfyDJSession.awaitReady(it)}
            if(!initialized){if(it.has("exploration"))slider.progress=it.optInt("exploration");initialized=true}
            render()
        },{notice(it);starting=false;primary?.isEnabled=true;pendingNextIds=null})}
        fun card(block:JSONObject,eyebrow:String){
            val songs=PiterfyDJSession.decode(block.getJSONArray("tracks"));val id=block.optString("id")
            val card=MaterialCardView(context).apply{
                radius=dp(18).toFloat();cardElevation=0f;strokeWidth=dp(1);strokeColor=ColorUtils.setAlphaComponent(muted,40)
                setCardBackgroundColor(MaterialColors.getColor(content,com.google.android.material.R.attr.colorSurface,Color.DKGRAY))
            }
            val body=column().apply{setPadding(dp(16),dp(12),dp(16),dp(12))};card.addView(body)
            rows.addView(card,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
            body.addView(label("$eyebrow · ${songs.size} utworów",11f,true).apply{setTextColor(accent)})
            body.addView(label(block.optString("title"),18f,true).apply{ViewCompat.setAccessibilityHeading(this,true)})
            body.addView(label(songs.mapNotNull{it.artist}.distinct().take(3).joinToString(" · "),13f).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
            val tracks=column().apply{visibility=if(id in expandedTracks)View.VISIBLE else View.GONE}
            val expand=button(body,if(id in expandedTracks)"Zwiń utwory" else "Zobacz utwory"){}
            expand.setOnClickListener{if(id in expandedTracks)expandedTracks.remove(id)else expandedTracks.add(id);tracks.visibility=if(id in expandedTracks)View.VISIBLE else View.GONE;expand.text=if(id in expandedTracks)"Zwiń utwory" else "Zobacz utwory"}
            for((index,song) in songs.withIndex()){
                tracks.addView(label("${index+1}. ${song.title.orEmpty()}",14f,true));tracks.addView(label(song.artist.orEmpty(),12f))
            }
            body.addView(tracks)
            button(body,"Odtwórz gotowy blok"){playBlock(block)}.isEnabled=block.optBoolean("ready",true)
            if(eyebrow!="Gotowy blok"&&eyebrow!="Inny klimat"){
                val votes=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL};body.addView(votes)
                for(vote in listOf(1,-1)){
                    var b:MaterialButton?=null
                    b=button(votes,if(vote==1)"Blok OK" else "Nie mój klimat"){
                        b?.isEnabled=false
                        PiterfyLibrary.dj(JSONObject().put("action","block-feedback").put("mode",mode).put("block",id).put("vote",vote),{b?.isEnabled=true;notice("Zapisano ocenę bloku.")},{b?.isEnabled=true;notice(it)})
                    };b?.layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(4)}
                }
            }
        }
        fun transportTitle()=if(starting)"Przygotowuję…" else if(PiterfyDJSession.hasPlayback(mode)&&PiterfyDJSession.visualActive())"Wstrzymaj" else if(PiterfyDJSession.hasPlayback(mode))"Wznów" else "Uruchom"
        render={
            if(dialog.isShowing){
                primary?.text=transportTitle();primary?.isEnabled=!starting
                voiceToggle?.text=if(state.optBoolean("voice"))"Głos DJ-a: włączony" else "Głos DJ-a: wyłączony"
                talkToggle?.text="Ile mówi DJ · "+when(state.optString("talkLevel","radio")){"sparse"->"Mało mówienia";"balanced"->"Równowaga";else->"Audycja radiowa"}
                for((key,b) in summaryButtons)b.text=(if(key=="summaryEmail")"Podsumowania e-mail: " else "Podsumowania Telegram: ")+(if(state.optBoolean(key,true))"włączone" else "wyłączone")
                val count=state.optJSONObject("buffer")?.optInt("ready")?:0
                val alternatives=state.optJSONObject("buffer")?.optJSONArray("alternatives")?.length()?:0
                if(SystemClock.elapsedRealtime()-noticeAt>5000){val message=if(count>0)"Gotowe bloki: $count/2 · inne klimaty: $alternatives" else "Przygotowuję Twoje propozycje…";if(status.text.toString()!=message)status.text=message}
                val p=state.optJSONObject("progress")
                progress.isIndeterminate=count==0&&(p==null||p.optString("stage") in listOf("queued","matching","searching"))
                if(!progress.isIndeterminate)progress.setProgressCompat(if(count>0)(count*50).coerceAtMost(100)else p?.optInt("percent")?:0,true)
                progressLabel.text=if(count>0)"Zapas gotowych bloków · $count/2" else when(p?.optString("stage")){"voice"->"Przygotowanie zapowiedzi";"downloading"->"Pobieranie odkryć w tle";"importing"->"Import do biblioteki";"unavailable"->"Odkrycia chwilowo niedostępne";else->"Dobieranie muzyki"}
                val array=state.optJSONArray("blocks")?:JSONArray();val blocks=(0 until array.length()).map{array.getJSONObject(it)}
                val next=blocks.firstOrNull{pendingNextIds!=null&&it.optString("id") !in pendingNextIds!!&&it.optBoolean("ready",true)}
                if(next!=null){pendingNextIds=null;playBlock(next)}
                val signature=blocks.joinToString{it.optString("id")+it.optString("title")+it.optJSONArray("tracks").toString()+it.optBoolean("consumed")+it.optBoolean("ready",true)}+state.optString("currentBlock")+state.optString("startBlock")+historyOpen
                if(rendered!=signature){
                    rendered=signature;rows.removeAllViews()
                    val current=blocks.filter{it.optString("id")==state.optString("currentBlock")}
                    val ready=blocks.filter{it !in current&&!it.optBoolean("consumed")&&it.optBoolean("ready",true)}.sortedBy{if(it.optString("id")==state.optString("startBlock"))-2 else if(it.optString("variant")=="alternative")1 else 0}
                    for(b in current)card(b,"Bieżący blok")
                    for(b in ready)card(b,if(b.optString("variant")=="alternative")"Inny klimat" else "Gotowy blok")
                    val history=blocks.filter{it !in current&&it !in ready}.reversed()
                    if(history.isNotEmpty())button(rows,(if(historyOpen)"Zwiń historię" else "Historia bloków")+" (${history.size})"){historyOpen=!historyOpen;render()}
                    if(historyOpen)for(b in history)card(b,"Poprzedni blok")
                    if(blocks.isEmpty())rows.addView(label("Możesz już kliknąć Uruchom. Odsłuch ruszy, gdy blok będzie gotowy.",13f))
                }
            }
        }
        primary=button(transport,"Uruchom",true){
            if(!starting&&!PiterfyDJSession.toggleFromDialog(mode)){starting=true;PiterfyDJSession.pauseForPreparation();notice("Uruchamiam gotowy blok…");request(JSONObject().put("action","start").put("exploration",slider.progress))};render()
        }
        button(transport,"Zmień klimat"){
            if(!state.optBoolean("active")){notice("Najpierw uruchom sesję.")}else{
                val array=state.optJSONArray("blocks")?:JSONArray();val blocks=(0 until array.length()).map{array.getJSONObject(it)}
                val next=blocks.firstOrNull{it.optString("variant")=="alternative"&&!it.optBoolean("consumed")&&it.optString("id")!=state.optString("currentBlock")}
                    ?:blocks.firstOrNull{it.optString("id")==state.optString("startBlock")}
                if(next!=null)playBlock(next)else{pendingNextIds=blocks.map{it.optString("id")}.toSet();request(JSONObject().put("action","next"))}
            }
        }
        if(mode=="dj")skip=button(transport,"Pomiń zapowiedź"){PiterfyDJSession.cancelNarration()}.apply{visibility=View.GONE}
        for(vote in listOf(1,-1)){
            val b=button(feedback,if(vote==1)"Więcej takich" else "Mniej takich"){
                val song=PiterfyDJSession.currentSong()
                if(song==null)notice("Odtwórz najpierw utwór na telefonie.")else PiterfyFeedback.show(context,song,vote,{notice("Zapisano reakcję na: ${song.title}");request(JSONObject().put("action","invalidate"))},{notice(it)})
            };b.layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(4)}
        }
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(bar:SeekBar,p:Int,user:Boolean){explorationLabel.text="Eksperymentowanie · $p%"}
            override fun onStartTrackingTouch(bar:SeekBar){}
            override fun onStopTrackingTouch(bar:SeekBar){request(JSONObject().put("action","settings").put("exploration",bar.progress))}
        });explorationLabel.text="Eksperymentowanie · ${slider.progress}%"
        if(mode=="dj")voiceToggle=button(settings,"Głos DJ-a"){if(state.optBoolean("voice"))PiterfyDJSession.cancelNarration();request(JSONObject().put("action","settings").put("voice",!state.optBoolean("voice")))}
        if(mode=="dj")talkToggle=button(settings,"Ile mówi DJ"){
            val keys=listOf("sparse","balanced","radio")
            MaterialAlertDialogBuilder(context).setTitle("Ile mówi DJ")
                .setSingleChoiceItems(arrayOf("Mało mówienia · początek bloku","Równowaga · co drugi utwór","Audycja radiowa · każdy utwór"),keys.indexOf(state.optString("talkLevel","radio"))){choice,index->request(JSONObject().put("action","settings").put("talkLevel",keys[index]));choice.dismiss()}.show()
        }
        for(key in listOf("summaryEmail","summaryTelegram"))summaryButtons[key]=button(settings,if(key=="summaryEmail")"Podsumowania e-mail" else "Podsumowania Telegram"){request(JSONObject().put("action","settings").put(key,!state.optBoolean(key,true)))}
        button(settings,"Zakończ sesję"){pendingNextIds=null;PiterfyDJSession.cancelAwait();PiterfyDJSession.disarm();PiterfyDJSession.cancelNarration();request(JSONObject().put("action","stop"))}
        val handler=Handler(Looper.getMainLooper());val poll=object:Runnable{override fun run(){if(dialog.isShowing){request(JSONObject().put("action","get"));handler.postDelayed(this,8000)}}}
        val animate=object:Runnable{override fun run(){if(dialog.isShowing){avatar?.setActive(state.optBoolean("active")&&PiterfyDJSession.hasPlayback(mode)&&PiterfyDJSession.visualActive());val title=transportTitle();if(primary?.text.toString()!=title)primary?.text=title;skip?.visibility=if(PiterfyDJSession.narratingInMode(mode))View.VISIBLE else View.GONE;handler.postDelayed(this,500)}}}
        dialog.setOnDismissListener{handler.removeCallbacks(poll);handler.removeCallbacks(animate);avatar?.setActive(false)}
        dialog.show();PiterfyLibrary.refresh(null);render();request(JSONObject().put("action","warm"));handler.post(poll);handler.post(animate)
    }
}
