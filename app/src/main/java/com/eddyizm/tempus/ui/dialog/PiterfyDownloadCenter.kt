package com.eddyizm.tempus.ui.dialog

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import com.eddyizm.tempus.service.DownloaderService
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.PiterfyOfflinePolicy
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.Executors

@UnstableApi
object PiterfyDownloadCenter {
    private val io=Executors.newSingleThreadExecutor()
    @JvmStatic fun show(context:Context){
        fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
        val owner=PiterfyLibrary.account();val main=Handler(Looper.getMainLooper())
        val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(24))}
        fun label(text:String)=TextView(context).apply{this.text=text;setPadding(0,dp(8),0,dp(8));body.addView(this)}
        val stats=label("Sprawdzam pobieranie…");stats.accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        val notice=label("")
        fun button(title:String,action:()->Unit)=MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply{text=title;isAllCaps=false;minimumHeight=dp(48);setOnClickListener{action()};body.addView(this)}
        val favorites=SwitchMaterial(context).apply{text="Automatycznie pobieraj ulubione utwory";isChecked=com.eddyizm.tempus.service.PiterfyFavoriteDownloads.enabled(context);minimumHeight=dp(48);body.addView(this)}
        favorites.setOnCheckedChangeListener{_,enabled->com.eddyizm.tempus.service.PiterfyFavoriteDownloads.setEnabled(context,enabled)}
        label("Przez Wi-Fi: obecne ulubione i kolejne polubienia. Podcasty mają osobne subskrypcje. Cofnięcie polubienia nie usuwa zapisanych plików.")
        val favoritesStatus=label(com.eddyizm.tempus.service.PiterfyFavoriteDownloads.status(context))
        button("Sprawdź ulubione teraz"){com.eddyizm.tempus.service.PiterfyFavoriteDownloads.refreshSoon(context);favoritesStatus.text=com.eddyizm.tempus.service.PiterfyFavoriteDownloads.status(context)}
        val pause=button("Wstrzymaj pobieranie"){
            if(!DownloadUtil.getDownloadManager(context).downloadsPaused)DownloadService.sendPauseDownloads(context,DownloaderService::class.java,false)
            else if(PiterfyOfflinePolicy.allow(context))DownloadService.sendResumeDownloads(context,DownloaderService::class.java,false)
            else notice.text="Osiągnięto limit miejsca. Zwiększ limit lub usuń pobrane utwory."
        }
        val cap=button("Limit miejsca offline"){
            val limits=listOf(0L,1L,2L,5L,10L)
            MaterialAlertDialogBuilder(context).setTitle("Limit miejsca offline")
                .setSingleChoiceItems(arrayOf("Bez limitu","1 GB","2 GB","5 GB","10 GB"),limits.indexOf(PiterfyOfflinePolicy.limit(context)/(1024L*1024*1024))){dialog,index->
                    PiterfyOfflinePolicy.setLimit(context,limits[index]*1024L*1024*1024);dialog.dismiss()
                }.show()
        }
        val cleanup=SwitchMaterial(context).apply{text="Usuwaj przesłuchane podcasty po 24 godzinach";isChecked=PiterfyOfflinePolicy.cleanupEnabled(context);minimumHeight=dp(48);body.addView(this)}
        cleanup.setOnCheckedChangeListener{_,enabled->PiterfyOfflinePolicy.setCleanup(context,enabled)}
        label("Sprzątanie obejmuje pobrania zarządzane przez Piterfy. Przypięte i obecnie odtwarzane odcinki pozostają zapisane. Limit wstrzymuje pobieranie; nie usuwa Twojej muzyki.")
        if(Preferences.getDownloadDirectoryUri()!=null){label("Wybrano folder zewnętrzny. Limit i automatyczne sprzątanie dotyczą pamięci wewnętrznej Piterfy; pliki w Twoim folderze pozostają pod Twoją kontrolą.");cleanup.isEnabled=false;cap.isEnabled=false}
        val rows=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL};body.addView(rows)
        val scroll=ScrollView(context).apply{addView(body)}
        val dialog=MaterialAlertDialogBuilder(context).setTitle("Centrum pobierania").setView(scroll).setNegativeButton("Zamknij",null).create()
        val views=linkedMapOf<String,Pair<TextView,LinearProgressIndicator>>()
        var loading=false;var signature=""
        val tick=object:Runnable{
            override fun run(){
                if(!dialog.isShowing||owner!=PiterfyLibrary.account())return
                if(!loading){loading=true;io.execute{
                    try{
                        val manager=DownloadUtil.getDownloadManager(context);val all=mutableListOf<Download>()
                        manager.downloadIndex.getDownloads().use{cursor->while(cursor.moveToNext())all.add(cursor.download)}
                        val shown=(all.filter{it.state!=Download.STATE_COMPLETED}.sortedByDescending{it.updateTimeMs}+all.filter{it.state==Download.STATE_COMPLETED}.sortedByDescending{it.updateTimeMs}.take(10)).take(60)
                        val repository=com.eddyizm.tempus.repository.DownloadRepository()
                        val titles=shown.associate{it.request.id to (repository.getDownload(it.request.id)?.title?:"Utwór offline")}
                        val bytes=DownloadUtil.getOfflineBytes(context);val paused=manager.downloadsPaused
                        main.post{
                            loading=false;if(!dialog.isShowing||owner!=PiterfyLibrary.account())return@post
                            val favoriteState=com.eddyizm.tempus.service.PiterfyFavoriteDownloads.status(context);if(favoritesStatus.text.toString()!=favoriteState)favoritesStatus.text=favoriteState
                            val pending=all.count{it.state in listOf(Download.STATE_QUEUED,Download.STATE_DOWNLOADING,Download.STATE_STOPPED,Download.STATE_RESTARTING)}
                            stats.text="Gotowe: ${all.count{it.state==Download.STATE_COMPLETED}} · w kolejce: $pending · błędy: ${all.count{it.state==Download.STATE_FAILED}}\nZapisano: ${Formatter.formatFileSize(context,bytes)}"
                            pause.text=if(paused)"Wznów pobieranie" else "Wstrzymaj pobieranie"
                            val limit=PiterfyOfflinePolicy.limit(context);cap.text="Limit miejsca · "+if(limit==0L)"bez limitu" else Formatter.formatFileSize(context,limit)
                            val sig=shown.joinToString{it.request.id+":"+it.state}
                            if(sig!=signature){signature=sig;rows.removeAllViews();views.clear()
                                for(download in shown){
                                    val title=TextView(context).apply{text=titles[download.request.id];textSize=15f;setPadding(0,dp(18),0,dp(4))};rows.addView(title)
                                    val status=TextView(context);rows.addView(status)
                                    val progress=LinearProgressIndicator(context).apply{max=100};rows.addView(progress,LinearLayout.LayoutParams(-1,dp(12)))
                                    views[download.request.id]=status to progress
                                    if(download.state==Download.STATE_FAILED){
                                        val retry=MaterialButton(context).apply{text="Ponów";isAllCaps=false;setOnClickListener{
                                            if(!PiterfyOfflinePolicy.allow(context)){notice.text="Osiągnięto limit miejsca.";return@setOnClickListener}
                                            DownloadService.sendAddDownload(context,DownloaderService::class.java,download.request,false)
                                        }};rows.addView(retry)
                                    }
                                }
                            }
                            for(d in shown){val pair=views[d.request.id]?:continue
                                val state=when(d.state){Download.STATE_COMPLETED->"Gotowe offline";Download.STATE_FAILED->"Błąd pobierania — sprawdź połączenie i ponów";Download.STATE_DOWNLOADING->"Pobieranie";Download.STATE_REMOVING->"Usuwanie";Download.STATE_STOPPED->"Wstrzymano";else->if(paused)"Wstrzymano" else "W kolejce"}
                                pair.first.text=state+" · "+Formatter.formatFileSize(context,d.bytesDownloaded)+(if(d.percentDownloaded>=0)" · ${d.percentDownloaded.toInt()}%" else "")
                                pair.second.isIndeterminate=d.percentDownloaded<0&&d.state!=Download.STATE_FAILED;pair.second.progress=d.percentDownloaded.toInt().coerceIn(0,100)
                            }
                            if(all.isEmpty())notice.text="Nie ma jeszcze pobrań w pamięci wewnętrznej. Pobierz playlistę lub zasubskrybuj podcast."
                        }
                    }catch(_:Exception){main.post{loading=false;if(dialog.isShowing)notice.text="Nie udało się odczytać pobrań. Spróbuję ponownie."}}
                }}
                main.postDelayed(this,2000)
            }
        }
        dialog.setOnDismissListener{main.removeCallbacks(tick)};dialog.show();main.post(tick)
    }
}
