package com.eddyizm.tempus.service;

import android.app.job.*;
import android.content.*;
import android.os.*;
import androidx.media3.common.util.UnstableApi;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.util.*;
import com.eddyizm.tempus.subsonic.models.Child;
import java.util.*;
import java.util.concurrent.Executors;

/** Periodic, Wi-Fi-only subscription refresh. The existing downloader owns files and metadata. */
@UnstableApi
public final class PiterfyPodcastDownloads extends JobService {
    private static final int JOB = 72841;
    private volatile boolean stopped;
    private volatile JobParameters active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private void finish(JobParameters params, boolean retry) {
        if (active != params) return;
        stopped=true; active=null; jobFinished(params,retry);
    }
    public static void schedule(Context context) {
        JobInfo.Builder job=new JobInfo.Builder(JOB,
                new ComponentName(context,PiterfyPodcastDownloads.class)).setPeriodic(60*60*1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true);
        if(Build.VERSION.SDK_INT>=26)job.setRequiresStorageNotLow(true);
        context.getSystemService(JobScheduler.class).schedule(job.build());
    }
    public static void refreshSoon(Context context) {
        JobInfo.Builder job=new JobInfo.Builder(JOB+1,
            new ComponentName(context,PiterfyPodcastDownloads.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            ;
        if(Build.VERSION.SDK_INT>=26)job.setRequiresStorageNotLow(true);
        context.getSystemService(JobScheduler.class).schedule(job.build());
    }
    @Override public boolean onStartJob(JobParameters params) {
        if (active != null) return false;
        active=params;stopped=false;String account=PiterfyLibrary.account();
        PiterfyLibrary.request(new org.json.JSONObject(),state->{
            if(stopped||active!=params)return;
            var subscriptions=state.optJSONArray("subscriptions");
            List<String> albums=new ArrayList<>();if(subscriptions!=null)for(int i=0;i<subscriptions.length();i++)albums.add(subscriptions.optString(i));
            fetch(params,account,albums,0);
        },message->finish(params,true));
        // A failed refresh must not leave a JobService running indefinitely.
        main.postDelayed(()->finish(params,true),120000);
        return true;
    }
    private void fetch(JobParameters params,String account,List<String> albums,int index) {
        if(stopped||active!=params)return;
        if(!account.equals(PiterfyLibrary.account())||index>=albums.size()){finish(params,false);return;}
        String id=albums.get(index);
        var executor=Executors.newSingleThreadExecutor();
        executor.execute(()->{
            try{
                var response=App.getSubsonicClientInstance(false).getBrowsingClient().getAlbum(id).execute();
                var sr=response.body()==null?null:response.body().getSubsonicResponse();
                if(!response.isSuccessful()||sr==null||sr.getAlbum()==null)throw new IllegalStateException();
                List<Child> songs=new ArrayList<>(sr.getAlbum().getSongs());
                songs.removeIf(song->!PiterfyMedia.isPodcast(song.getGenre()));
                songs.sort(Comparator.comparing((Child song)->song.getCreated()==null?new Date(0):song.getCreated()).reversed());
                var prefs=getSharedPreferences("piterfy_podcast_downloads",0);
                String key=account+":"+id;Set<String> seen=new HashSet<>(prefs.getStringSet(key,Collections.emptySet()));
                List<Child> fresh=new ArrayList<>();
                for(Child song:songs)if(!seen.contains(song.getId())&&(prefs.contains(key)||fresh.size()<3))fresh.add(song);
                Set<String> next=new HashSet<>();for(Child song:songs)next.add(song.getId());
                new Handler(Looper.getMainLooper()).post(()->{
                    if(stopped||active!=params||!account.equals(PiterfyLibrary.account()))return;
                    DownloadUtil.downloadMissing(this,fresh,null,"Podcasty · subskrypcje",count->{
                        if(count<0){finish(params,true);return;}
                        for(Child song:fresh)if(!DownloadUtil.getDownloadTracker(this).isRequested(song.getId()) &&
                            !DownloadUtil.getDownloadTracker(this).isDownloaded(song.getId()) && !ExternalAudioWriter.isPending(song.getId()))next.remove(song.getId());
                        prefs.edit().putStringSet(key,next).apply();
                        fetch(params,account,albums,index+1);
                    },true);
                });
            }catch(Exception ignored){main.post(()->finish(params,true));}
            finally{executor.shutdown();}
        });
    }
    @Override public boolean onStopJob(JobParameters params){if(active==params){stopped=true;active=null;}return true;}
}
