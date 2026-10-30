package com.eddyizm.tempus.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.media3.common.util.UnstableApi;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.PiterfyLibrary;
import com.eddyizm.tempus.util.PiterfyMedia;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;
import retrofit2.*;

@UnstableApi
public final class PiterfyDiscoveryDialog {
    private PiterfyDiscoveryDialog() {}
    public static void show(Context context, Consumer<List<Child>> play) {
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView label = new TextView(context); label.setText("Poziom eksperymentowania"); content.addView(label);
        TextView value = new TextView(context); content.addView(value);
        SeekBar exploration = new SeekBar(context); exploration.setMax(100);
        exploration.setProgress(PiterfyLibrary.cached().optInt("exploration",50)); content.addView(exploration);
        TextView status = new TextView(context); status.setText("Znajome brzmienia ← → Nowi wykonawcy"); content.addView(status);
        MaterialButton refresh = new MaterialButton(context); refresh.setText("Odkryj muzykę"); content.addView(refresh);
        LinearLayout results = new LinearLayout(context); results.setOrientation(LinearLayout.VERTICAL); content.addView(results);
        ScrollView scroll = new ScrollView(context); scroll.addView(content);
        var dialog = new MaterialAlertDialogBuilder(context).setTitle("Odkrywaj").setView(scroll).setNegativeButton("Zamknij",null).create();
        Runnable update = () -> value.setText(exploration.getProgress() + "% · " + (exploration.getProgress()<35?"Blisko Twojego gustu":exploration.getProgress()>65?"Szukamy nowych brzmień":"Równowaga"));
        update.run();
        exploration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int progress,boolean user){update.run();}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){try {PiterfyLibrary.request(new JSONObject().put("action","settings").put("exploration",bar.getProgress()), ignored->status.setText("Zapisane na Twoim koncie"), status::setText);}catch(JSONException ignored){}}
        });
        PiterfyLibrary.refresh(state->{if(dialog.isShowing()){exploration.setProgress(state.optInt("exploration",50));update.run();}});
        refresh.setOnClickListener(v->{
            refresh.setEnabled(false); status.setText("Dobieram muzykę…"); results.removeAllViews();
            Call<ApiResponse> call=App.getSubsonicClientInstance(false).getAlbumSongListClient().getRandomSongs(150,null,null);
            dialog.setOnDismissListener(ignored->call.cancel());
            call.enqueue(new Callback<ApiResponse>() {
                public void onFailure(@NonNull Call<ApiResponse> c,@NonNull Throwable error){if(dialog.isShowing()){status.setText("Nie udało się pobrać propozycji. Spróbuj ponownie.");refresh.setEnabled(true);}}
                public void onResponse(@NonNull Call<ApiResponse> c,@NonNull Response<ApiResponse> response){
                    if(!dialog.isShowing())return;
                    var sr=response.body()==null?null:response.body().getSubsonicResponse();
                    if(!response.isSuccessful()||sr==null||sr.getRandomSongs()==null){onFailure(c,new Exception());return;}
                    Map<String,Child> songs=new LinkedHashMap<>(); JSONArray candidates=new JSONArray();
                    try {
                        for(Child song:sr.getRandomSongs().getSongs()) if(!PiterfyMedia.isPodcast(song.getGenre())){
                            songs.put(song.getId(),song);candidates.put(new JSONObject().put("id",song.getId()).put("artist",song.getArtist()).put("familiar",song.getPlayCount()!=null&&song.getPlayCount()>0));
                        }
                        PiterfyLibrary.request(new JSONObject().put("action","rank").put("items",candidates), ranked->{
                            if(!dialog.isShowing())return;
                            refresh.setEnabled(true);status.setText("Propozycje uwzględniają Twoje reakcje i wczesne pominięcia.");
                            JSONArray rows=ranked.optJSONArray("items"); List<Child> playlist=new ArrayList<>();
                            if(rows!=null)for(int i=0;i<Math.min(20,rows.length());i++){Child song=songs.get(rows.optJSONObject(i).optString("id"));if(song!=null)playlist.add(song);}
                            if(play!=null&&!playlist.isEmpty()){MaterialButton all=new MaterialButton(context);all.setText("Odtwórz propozycje");all.setOnClickListener(b->{play.accept(playlist);dialog.dismiss();});results.addView(all);}
                            for(Child song:playlist){
                                TextView title=new TextView(context); title.setText(song.getTitle()+"\n"+song.getArtist());title.setTextSize(15);title.setPadding(0,padding,0,0);results.addView(title);
                                LinearLayout controls=new LinearLayout(context);results.addView(controls);
                                for(int vote:new int[]{1,-1}){MaterialButton button=new MaterialButton(context,null,com.google.android.material.R.attr.borderlessButtonStyle);button.setText(vote==1?"Więcej takich":"Mniej takich");button.setOnClickListener(b->PiterfyLibrary.feedback(song.getId(),song.getArtist(),vote,ignored->{status.setText("Zapisano — uwzględnię to w kolejnych propozycjach");button.setEnabled(false);},status::setText));controls.addView(button,new LinearLayout.LayoutParams(0,-2,1));}
                            }
                            if(playlist.isEmpty())status.setText("Brak propozycji. Zmień poziom eksperymentowania i spróbuj ponownie.");
                        },message->{status.setText(message);refresh.setEnabled(true);});
                    } catch(JSONException error){onFailure(c,error);}
                }
            });
        });
        dialog.show();
    }
}
