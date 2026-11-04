package com.eddyizm.tempus.service;

import android.app.job.*;
import android.content.*;
import android.os.*;
import androidx.media3.common.util.UnstableApi;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.*;
import java.util.*;
import java.util.concurrent.Executors;

/** All starred music, synced over an unmetered connection without deleting unstarred files. */
@UnstableApi
public final class PiterfyFavoriteDownloads extends JobService {
    private static final int JOB=72851;
    private final Handler main=new Handler(Looper.getMainLooper());
    private JobParameters active;
    private boolean stopped;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("piterfy_favorite_downloads",0);}
    public static boolean enabled(Context c){return prefs(c).getBoolean(PiterfyLibrary.account()+":enabled",true);}
    public static void setEnabled(Context c,boolean enabled){
        prefs(c).edit().putBoolean(PiterfyLibrary.account()+":enabled",enabled).apply();
        if(enabled)refreshSoon(c);else c.getSystemService(JobScheduler.class).cancel(JOB+1);
    }
    public static String status(Context c){return prefs(c).getString(PiterfyLibrary.account()+":status","Ulubione zostaną sprawdzone przez Wi-Fi.");}
    public static void schedule(Context c){
        JobInfo.Builder job=new JobInfo.Builder(JOB,new ComponentName(c,PiterfyFavoriteDownloads.class))
            .setPeriodic(15*60*1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true);
        if(Build.VERSION.SDK_INT>=26)job.setRequiresStorageNotLow(true);
        c.getSystemService(JobScheduler.class).schedule(job.build());
    }
    public static void refreshSoon(Context c){
        if(Preferences.getServerId()==null||Preferences.getUser()==null||!enabled(c))return;
        prefs(c).edit().putString(PiterfyLibrary.account()+":status","Ulubione: oczekiwanie na synchronizację przez Wi-Fi.").apply();
        JobInfo.Builder job=new JobInfo.Builder(JOB+1,new ComponentName(c,PiterfyFavoriteDownloads.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setMinimumLatency(2000);
        if(Build.VERSION.SDK_INT>=26)job.setRequiresStorageNotLow(true);
        c.getSystemService(JobScheduler.class).schedule(job.build());
    }
    private void finish(JobParameters params,boolean retry){if(active!=params)return;stopped=true;active=null;jobFinished(params,retry);}
    private boolean valid(JobParameters params,String account){return !stopped&&active==params&&account.equals(PiterfyLibrary.account())&&enabled(this);}
    @Override public boolean onStartJob(JobParameters params){
        if(active!=null||Preferences.getServerId()==null||!enabled(this))return false;
        active=params;stopped=false;String account=PiterfyLibrary.account();
        var executor=Executors.newSingleThreadExecutor();
        executor.execute(()->{
            try{
                var response=App.getSubsonicClientInstance(false).getAlbumSongListClient().getStarred2().execute();
                var sr=response.body()==null?null:response.body().getSubsonicResponse();
                if(!response.isSuccessful()||sr==null||sr.getStarred2()==null)throw new IllegalStateException();
                List<Child> music=new ArrayList<>();
                if(sr.getStarred2().getSongs()!=null)for(Child song:sr.getStarred2().getSongs())
                    if(!PiterfyMedia.isPodcast(song)&&FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG,song.getId(),true))music.add(song);
                main.post(()->batch(params,account,music,0,0));
            }catch(Exception ignored){main.post(()->{if(valid(params,account))prefs(this).edit().putString(account+":status","Ulubione: nie udało się sprawdzić serwera. Ponowię synchronizację.").apply();finish(params,true);});}
            finally{executor.shutdown();}
        });
        main.postDelayed(()->finish(params,true),120000);
        return true;
    }
    private void batch(JobParameters params,String account,List<Child> songs,int offset,int queued){
        if(!valid(params,account)){finish(params,false);return;}
        if(offset>=songs.size()){
            prefs(this).edit().putString(account+":status","Ulubione: sprawdzono "+songs.size()+" utworów · dodano do pobierania: "+queued+".").apply();finish(params,false);return;
        }
        if(!PiterfyOfflinePolicy.allow(this)){
            prefs(this).edit().putString(account+":status","Ulubione: osiągnięto limit offline. Pozostałe utwory czekają na miejsce.").apply();finish(params,false);return;
        }
        int end=Math.min(offset+10,songs.size());
        DownloadUtil.downloadMissing(this,songs.subList(offset,end),null,"Ulubione · automatycznie offline",count->{
            if(count<0){prefs(this).edit().putString(account+":status","Ulubione: pobieranie wstrzymane. Sprawdź limit miejsca i połączenie.").apply();finish(params,true);return;}
            main.postDelayed(()->batch(params,account,songs,end,queued+count),50);
        },true);
    }
    @Override public boolean onStopJob(JobParameters params){if(active==params){stopped=true;active=null;}return true;}
}
