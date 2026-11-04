package com.eddyizm.tempus.util;

import androidx.media3.common.Player;
import androidx.media3.session.MediaBrowser;
import androidx.media3.common.util.UnstableApi;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.service.MediaManager;
import com.eddyizm.tempus.subsonic.models.Child;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import org.json.JSONObject;
import java.util.*;

/** Independent resumable music and podcast queues, scoped to the selected server account. */
@UnstableApi
public final class PiterfyQueues {
    private PiterfyQueues(){}
    public static boolean podcast(Child song){return PiterfyMedia.isPodcast(song)||Constants.MEDIA_TYPE_PODCAST.equals(song.getType());}
    private static String key(boolean podcast){return PiterfyLibrary.account()+":"+(podcast?"podcasts":"music");}
    public static List<Child> partition(List<Child> songs,int index){
        if(songs==null||songs.isEmpty()||index<0||index>=songs.size())return Collections.emptyList();
        boolean kind=podcast(songs.get(index)); List<Child> result=new ArrayList<>();
        for(Child song:songs)if(podcast(song)==kind)result.add(song);
        return result;
    }
    public static void save(Player player){
        if(player==null||player.getMediaItemCount()==0||player.getMediaItemCount()>500||player.getCurrentMediaItem()==null)return;
        if(Constants.MEDIA_TYPE_RADIO.equals(player.getCurrentMediaItem().mediaMetadata.extras==null?null:player.getCurrentMediaItem().mediaMetadata.extras.getString("type")))return;
        List<Child> all=new ArrayList<>();for(int i=0;i<player.getMediaItemCount();i++)all.add(MappingUtil.mapToChild(player.getMediaItemAt(i)));
        int index=player.getCurrentMediaItemIndex();if(index<0||index>=all.size())return;
        Child current=all.get(index);List<Child> songs=partition(all,index);
        int selected=0;for(int i=0;i<index;i++)if(podcast(all.get(i))==podcast(current))selected++;
        try{
            JSONObject data=new JSONObject().put("songs",new org.json.JSONArray(new Gson().toJson(songs)))
                .put("index",selected).put("position",player.getCurrentPosition());
            App.getContext().getSharedPreferences("piterfy_queues",0).edit().putString(key(podcast(current)),data.toString()).apply();
        }catch(Exception ignored){}
    }
    public static boolean restore(boolean podcasts,ListenableFuture<MediaBrowser> browser){
        try{
            JSONObject data=new JSONObject(App.getContext().getSharedPreferences("piterfy_queues",0).getString(key(podcasts),"{}"));
            Child[] songs=new Gson().fromJson(data.getJSONArray("songs").toString(),Child[].class);
            if(songs.length==0)return false;
            MediaManager.startQueue(browser,Arrays.asList(songs),Math.min(data.optInt("index"),songs.length-1),Math.max(0,data.optLong("position")));return true;
        }catch(Exception ignored){return false;}
    }
    public static List<Child> routeEnqueue(Player player,List<Child> songs){
        if(player.getCurrentMediaItem()==null)return songs;
        Child current=MappingUtil.mapToChild(player.getCurrentMediaItem());boolean kind=podcast(current);
        List<Child> compatible=new ArrayList<>(),other=new ArrayList<>();
        for(Child song:songs)(podcast(song)==kind?compatible:other).add(song);
        if(other.isEmpty())return songs;
        if(!App.isReady())return compatible;
        try{
            var prefs=App.getContext().getSharedPreferences("piterfy_queues",0);
            JSONObject data=new JSONObject(prefs.getString(key(!kind),"{}"));
            List<Child> queued=new ArrayList<>();
            if(data.has("songs"))queued.addAll(Arrays.asList(new Gson().fromJson(data.getJSONArray("songs").toString(),Child[].class)));
            Set<String> ids=new HashSet<>();for(Child song:queued)ids.add(song.getId());
            for(Child song:other)if(ids.add(song.getId())&&queued.size()<500)queued.add(song);
            data.put("songs",new org.json.JSONArray(new Gson().toJson(queued)));
            prefs.edit().putString(key(!kind),data.toString()).apply();
            android.widget.Toast.makeText(App.getContext(),kind?"Dodano do osobnej kolejki muzyki":"Dodano do osobnej kolejki podcastów",android.widget.Toast.LENGTH_SHORT).show();
        }catch(Exception ignored){}
        return compatible;
    }
}
