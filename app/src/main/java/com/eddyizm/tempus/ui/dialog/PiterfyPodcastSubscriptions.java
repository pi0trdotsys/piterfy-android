package com.eddyizm.tempus.ui.dialog;

import android.content.Context;
import android.widget.*;
import androidx.media3.common.util.UnstableApi;
import com.eddyizm.tempus.subsonic.models.AlbumID3;
import com.eddyizm.tempus.util.PiterfyLibrary;
import com.eddyizm.tempus.service.PiterfyPodcastDownloads;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.*;
import java.util.*;

@UnstableApi
public final class PiterfyPodcastSubscriptions {
    public static void show(Context context,List<AlbumID3> shows){
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);
        int padding=Math.round(20*context.getResources().getDisplayMetrics().density);content.setPadding(padding,padding,padding,padding);
        TextView help=new TextView(context);help.setText("Nowe odcinki zaznaczonych audycji będą pobierane przez Wi-Fi. Przy pierwszym włączeniu pobierzemy maksymalnie 3 najnowsze odcinki. Android może opóźnić zadanie, aby oszczędzić baterię.");content.addView(help);
        ScrollView scroll=new ScrollView(context);scroll.addView(content);
        var dialog=new MaterialAlertDialogBuilder(context).setTitle("Subskrypcje podcastów").setView(scroll).setNegativeButton("Zamknij",null).create();
        PiterfyLibrary.request(new JSONObject(),state->{
            if(!dialog.isShowing())return;
            Set<String> selected=new HashSet<>();JSONArray array=state.optJSONArray("subscriptions");if(array!=null)for(int i=0;i<array.length();i++)selected.add(array.optString(i));
            for(AlbumID3 show:shows){
                CheckBox checkbox=new CheckBox(context);checkbox.setText(show.getName());checkbox.setMinHeight(Math.round(48*context.getResources().getDisplayMetrics().density));checkbox.setChecked(selected.contains(show.getId()));content.addView(checkbox);
                final boolean[] reverting={false};
                checkbox.setOnCheckedChangeListener((button,checked)->{
                    if(reverting[0])return;
                    checkbox.setEnabled(false);
                    try{PiterfyLibrary.request(new JSONObject().put("action","subscribe").put("id",show.getId()).put("enabled",checked),result->{
                        checkbox.setEnabled(true);if(checked)PiterfyPodcastDownloads.refreshSoon(context);
                    },message->{checkbox.setEnabled(true);reverting[0]=true;checkbox.setChecked(!checked);reverting[0]=false;Toast.makeText(context,message,Toast.LENGTH_LONG).show();});}
                    catch(JSONException ignored){checkbox.setEnabled(true);}
                });
            }
            if(shows.isEmpty())help.setText("Brak audycji. Odśwież listę podcastów i otwórz subskrypcje ponownie.");
        },help::setText);
        dialog.show();
    }
}
