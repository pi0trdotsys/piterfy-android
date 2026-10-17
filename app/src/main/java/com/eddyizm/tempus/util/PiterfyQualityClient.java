package com.eddyizm.tempus.util;

import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.widget.TextView;
import android.content.Context;
import com.eddyizm.tempus.R;
import com.google.gson.*;
import java.util.concurrent.*;
import okhttp3.*;

/** Separate source analysis from the actual stream codec shown by Media3. */
public final class PiterfyQualityClient {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final OkHttpClient http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).callTimeout(20,TimeUnit.SECONDS).build();
    private volatile Call active;
    private String displayed="",token="",identity="";
    private long loginAt;
    private volatile int generation;
    private static final int GREEN = 0xFF1ED760, ORANGE = 0xFFFF9F4A, YELLOW = 0xFFF2C94C;
    private static final java.util.Set<String> LOSSLESS = new java.util.HashSet<>(java.util.Arrays.asList(
        "flac", "alac", "wav", "aif", "aiff", "ape", "wv", "tta", "dsf", "dff"));

    /** Poziom jakosci z formatu i bitrate: Niska / Normalna / Wysoka / Bezstratna (0 = nie wiadomo). */
    public static int level(String suffix, int kbps) {
        String s = suffix == null ? "" : suffix.toLowerCase(java.util.Locale.ROOT);
        if (LOSSLESS.contains(s) || ((s.equals("m4a") || s.equals("mp4")) && kbps >= 500)) return R.string.piterfy_q_lossless;
        if (kbps <= 0) return 0;
        // nowoczesne kodeki (AAC, Opus, Vorbis) brzmia lepiej przy tym samym bitrate
        double effective = kbps * (s.equals("mp3") ? 1.0 : 1.5);
        return effective >= 256 ? R.string.piterfy_q_high : effective >= 128 ? R.string.piterfy_q_normal : R.string.piterfy_q_low;
    }

    private static void paint(TextView view, CharSequence text, int color) {
        view.setText(text);
        view.setTextColor(color);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
    }

    public void show(String id, TextView view, String suffix, int kbps) {
        String base=Preferences.getServer(), user=Preferences.getUser(), password=Preferences.getPassword();
        String key=base+"|"+user+"|"+id;
        if(id==null||id.isEmpty()||key.equals(displayed))return;
        displayed=key;int request=++generation;
        if(active!=null)active.cancel();
        // Poziom znamy od razu z formatu i bitrate; serwer (analiza widma) moze go tylko skorygowac
        final int local=level(suffix,kbps);
        if(local!=0)paint(view,view.getContext().getString(local),GREEN);
        else view.setText("");
        // The extension uses a Navidrome login; never send a password over cleartext HTTP.
        if(base==null||user==null||password==null||!"https".equalsIgnoreCase(Uri.parse(base).getScheme()))return;
        io.execute(()->{
            try {
                String origin=Uri.parse(base).buildUpon().encodedPath("").clearQuery().fragment(null).build().toString();
                if(!identity.equals(origin+"|"+user)||System.currentTimeMillis()-loginAt>1800000)token="";
                if(token.isEmpty()) {
                    JsonObject login=new JsonObject();login.addProperty("username",user);login.addProperty("password",password);
                    Request r=new Request.Builder().url(origin+"/auth/login")
                        .post(RequestBody.create(MediaType.parse("application/json"),login.toString())).build();
                    JsonObject auth=json(r,request);
                    if(auth==null||!auth.has("token"))return;
                    token=auth.get("token").getAsString();identity=origin+"|"+user;loginAt=System.currentTimeMillis();
                }
                JsonObject result=json(new Request.Builder().url(origin+"/piterfy-api/quality/"+Uri.encode(id))
                    .header("X-ND-Authorization","Bearer "+token).build(),request);
                if(result==null){token="";return;}
                String verdict=result.has("verdict")?result.get("verdict").getAsString():"unknown";
                if(verdict.equals("fake")||verdict.equals("transcoded")){
                    String rate=result.has("source_kbps")?result.get("source_kbps").getAsString():"?";
                    int effective=level("mp3",result.has("source_kbps")?result.get("source_kbps").getAsInt():0);
                    final String level=effective!=0?view.getContext().getString(effective):"";
                    main.post(()->{if(request==generation)paint(view,view.getContext().getString(R.string.piterfy_quality_fake,level,rate),ORANGE);});
                } else if(verdict.equals("suspect")||verdict.equals("upsampled")){
                    final String level=local!=0?view.getContext().getString(local):"";
                    main.post(()->{if(request==generation)paint(view,view.getContext().getString(R.string.piterfy_quality_maybe,level),YELLOW);});
                }
            } catch(Exception ignored) { /* Optional extension; playback never depends on it. */ }
        });
    }
    private JsonObject json(Request request,int expected)throws Exception{
        if(expected!=generation)return null;
        Call call=http.newCall(request);active=call;
        if(expected!=generation){call.cancel();return null;}
        try(Response response=call.execute()){
            if(!response.isSuccessful()||response.body()==null)return null;
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        }
    }
    public void close(){generation++;if(active!=null)active.cancel();io.shutdownNow();token="";}
}
