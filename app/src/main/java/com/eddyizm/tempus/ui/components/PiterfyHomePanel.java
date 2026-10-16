package com.eddyizm.tempus.ui.components;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.navigation.Navigation;
import com.eddyizm.tempus.App;
import com.eddyizm.tempus.R;
import com.eddyizm.tempus.subsonic.base.ApiResponse;
import com.eddyizm.tempus.subsonic.models.Playlist;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import java.util.*;
import java.util.function.Consumer;
import retrofit2.*;

/** Piterfy: real server discovery playlists; view requests die with the view. */
@androidx.media3.common.util.UnstableApi
public final class PiterfyHomePanel extends LinearLayout implements DefaultLifecycleObserver {
    private final Fragment host;
    private final Consumer<List<Child>> play;
    private final List<Call<ApiResponse>> calls = new ArrayList<>();
    private final LinearLayout playlists;
    private boolean destroyed;
    private final String server = String.valueOf(Preferences.getServerId());
    public PiterfyHomePanel(Fragment host, Consumer<List<Child>> play) {
        super(host.requireContext()); this.host = host; this.play = play;
        setOrientation(VERTICAL); setPadding(dp(16), dp(8), dp(16), dp(24));
        host.getViewLifecycleOwner().getLifecycle().addObserver(this);
        MaterialButton taste = button(R.string.piterfy_taste);
        taste.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x2028aa65));
        taste.setTextColor(Color.WHITE);
        taste.setStrokeColor(android.content.res.ColorStateList.valueOf(0xff4a7860)); taste.setStrokeWidth(dp(1));
        taste.setOnClickListener(v -> {
            String base = Preferences.getServer();
            if (base == null) return;
            Uri uri = Uri.parse(base);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return;
            Uri target = uri.buildUpon().encodedPath("/gust/").clearQuery().fragment(null).build();
            try { host.startActivity(new Intent(Intent.ACTION_VIEW, target)); } catch (ActivityNotFoundException ignored) {}
        });
        addView(taste, new LayoutParams(LayoutParams.WRAP_CONTENT, dp(52)));
        TextView heading = text(R.string.piterfy_discover, 23, themed(com.google.android.material.R.attr.colorOnSurface));
        heading.setTypeface(heading.getTypeface(), Typeface.BOLD); heading.setPadding(0,dp(24),0,dp(12)); addView(heading);
        playlists = column(); addView(playlists);
        load();
    }
    private boolean alive() { return !destroyed && server.equals(String.valueOf(Preferences.getServerId())); }
    private int themed(int attr) { return com.google.android.material.color.MaterialColors.getColor(this,attr); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(getContext()); l.setOrientation(VERTICAL); return l; }
    private TextView text(int res,int size,int color) {
        TextView t=new TextView(getContext()); t.setText(res); t.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(getContext(),R.font.figtree)); t.setTextSize(size); t.setTextColor(color); t.setPadding(0,dp(6),0,dp(6)); return t;
    }
    private MaterialButton button(int res) {
        MaterialButton b=new MaterialButton(getContext()); b.setText(res); b.setMinHeight(dp(48)); b.setCornerRadius(dp(24)); b.setAllCaps(false); return b;
    }
    private void load() {
        playlists.removeAllViews(); ProgressBar progress = new ProgressBar(getContext()); playlists.addView(progress,new LayoutParams(dp(32),dp(32)));
        Call<ApiResponse> call=App.getSubsonicClientInstance(false).getPlaylistClient().getPlaylists(); calls.add(call);
        call.enqueue(new Callback<ApiResponse>() {
            public void onResponse(@NonNull Call<ApiResponse> c,@NonNull Response<ApiResponse> r) {
                if (!alive() || c.isCanceled()) return;
                var sr=r.body()==null?null:r.body().getSubsonicResponse();
                if (!r.isSuccessful() || sr==null || sr.getPlaylists()==null) { error(); return; }
                List<Playlist> list=new ArrayList<>();
                if (sr.getPlaylists().getPlaylists()!=null) for (Playlist p:sr.getPlaylists().getPlaylists())
                    if (p.getName()!=null && (p.getName().equalsIgnoreCase("Odkrywaj") || p.getName().startsWith("Odkrywaj "))) list.add(p);
                list.sort(Comparator.comparing((Playlist p)->p.getCreated()==null?new Date(0):p.getCreated()).reversed());
                playlists.removeAllViews();
                if (list.isEmpty()) playlists.addView(text(R.string.piterfy_discover_empty,14,themed(com.google.android.material.R.attr.colorOnSurfaceVariant)));
                for (Playlist p:list.subList(0,Math.min(3,list.size()))) card(p);
            }
            public void onFailure(@NonNull Call<ApiResponse> c,@NonNull Throwable t) { if(alive()&&!c.isCanceled()) error(); }
        });
    }
    private void error() {
        playlists.removeAllViews();
        playlists.addView(text(R.string.piterfy_network_error,14,themed(com.google.android.material.R.attr.colorOnSurfaceVariant)));
        MaterialButton retry=button(R.string.piterfy_retry); retry.setOnClickListener(v->load()); playlists.addView(retry);
    }
    private void card(Playlist p) {
        LinearLayout card=column(); card.setPadding(dp(16),dp(12),dp(16),dp(12));
        GradientDrawable bg=new GradientDrawable(); bg.setColor(themed(com.google.android.material.R.attr.colorSurfaceVariant)); bg.setCornerRadius(dp(12)); card.setBackground(bg);
        LayoutParams params=new LayoutParams(-1,-2); params.bottomMargin=dp(12); playlists.addView(card,params);
        LinearLayout row=new LinearLayout(getContext()); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView art=new ImageView(getContext()); row.addView(art,new LayoutParams(dp(64),dp(64)));
        com.bumptech.glide.Glide.with(host).load(com.eddyizm.tempus.glide.CustomGlideRequest.createUrl(p.getCoverArtId(),160))
            .placeholder(R.drawable.ic_placeholder_playlist).centerCrop().into(art);
        LinearLayout words=column(); words.setPadding(dp(14),0,0,0); row.addView(words,new LayoutParams(0,-2,1));
        TextView name=text(R.string.piterfy_discover,18,themed(com.google.android.material.R.attr.colorOnSurface)); name.setText(p.getName()); name.setTypeface(name.getTypeface(),Typeface.BOLD); words.addView(name);
        TextView count=text(R.string.piterfy_discover,12,themed(com.google.android.material.R.attr.colorOnSurfaceVariant)); count.setText(getResources().getString(R.string.piterfy_discover_count,p.getSongCount())); words.addView(count); card.addView(row);
        row.setOnClickListener(v->{Bundle b=new Bundle();b.putParcelable(Constants.PLAYLIST_OBJECT,p);Navigation.findNavController(host.requireView()).navigate(R.id.playlistPageFragment,b);});
        row.setContentDescription(p.getName()); row.setFocusable(true);
        LinearLayout actions=new LinearLayout(getContext());
        MaterialButton playButton=button(R.string.piterfy_play),download=button(R.string.piterfy_download);
        download.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.TRANSPARENT));
        download.setTextColor(themed(com.google.android.material.R.attr.colorOnSurface));
        download.setStrokeColor(android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(themed(com.google.android.material.R.attr.colorOnSurface), 100)));download.setStrokeWidth(dp(1));
        actions.addView(playButton,new LayoutParams(0,-2,1)); LayoutParams gap=new LayoutParams(0,-2,1);gap.leftMargin=dp(10);actions.addView(download,gap);card.addView(actions);
        playButton.setOnClickListener(v->tracks(p,playButton,false));
        download.setOnClickListener(v->tracks(p,download,true));
    }
    private void tracks(Playlist p,MaterialButton button,boolean download) {
        button.setEnabled(false);
        Call<ApiResponse> call=App.getSubsonicClientInstance(false).getPlaylistClient().getPlaylist(p.getId());calls.add(call);
        call.enqueue(new Callback<ApiResponse>() {
            public void onResponse(@NonNull Call<ApiResponse> c,@NonNull Response<ApiResponse> r) {
                if(!alive()||c.isCanceled())return;button.setEnabled(true);
                var sr=r.body()==null?null:r.body().getSubsonicResponse();
                if(!r.isSuccessful()||sr==null||sr.getPlaylist()==null||sr.getPlaylist().getEntries()==null){fail();return;}
                List<Child> songs=sr.getPlaylist().getEntries();
                if(download) DownloadUtil.downloadMissing(getContext(),songs,p.getId(),p.getName(),queued->{
                    if(alive()) Snackbar.make(PiterfyHomePanel.this,queued<0?getResources().getString(R.string.piterfy_network_error):getResources().getString(R.string.piterfy_queued,queued),Snackbar.LENGTH_LONG).show();
                }); else if(!songs.isEmpty())play.accept(songs);
            }
            public void onFailure(@NonNull Call<ApiResponse> c,@NonNull Throwable t){if(alive()&&!c.isCanceled()){button.setEnabled(true);fail();}}
            private void fail(){Snackbar.make(PiterfyHomePanel.this,R.string.piterfy_network_error,Snackbar.LENGTH_LONG).show();}
        });
    }
    @Override public void onDestroy(@NonNull LifecycleOwner owner){destroyed=true;for(var c:calls)c.cancel();calls.clear();}
}
