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
    public void show(String id, TextView view) {
        String base=Preferences.getServer(), user=Preferences.getUser(), password=Preferences.getPassword();
        String key=base+"|"+user+"|"+id;
        if(id==null||id.isEmpty()||key.equals(displayed))return;
        displayed=key;int request=++generation;
        if(active!=null)active.cancel();
        view.setText(R.string.piterfy_quality_unknown);
        // The extension uses a Navidrome login; never send a password over cleartext HTTP.
        if(base==null||user==null||password==null||!"https".equalsIgnoreCase(Uri.parse(base).getScheme()))return;
        io.execute(()->{
            int label=R.string.piterfy_quality_unknown;String bitrate="?";
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
                if(result.has("podcast")&&result.get("podcast").getAsBoolean())label=R.string.piterfy_quality_podcast;
                else if(verdict.equals("fake")||verdict.equals("transcoded")){
                    label=R.string.piterfy_quality_suspect;
                    if(result.has("source_kbps"))bitrate=result.get("source_kbps").getAsString();
                } else if(verdict.equals("suspect")||verdict.equals("upsampled"))label=R.string.piterfy_quality_uncertain;
                else if(verdict.equals("ok")){
                    String codec=result.has("codec")?result.get("codec").getAsString():"";
                    label=(codec.equals("flac")||codec.equals("alac")||codec.equals("wavpack")||codec.startsWith("pcm_"))
                        ?R.string.piterfy_quality_lossless:R.string.piterfy_quality_lossy;
                }
            } catch(Exception ignored) { /* Optional extension; playback never depends on it. */ }
            final int text=label;final String rate=bitrate;
            main.post(()->{if(request==generation)view.setText(text==R.string.piterfy_quality_suspect?view.getContext().getString(text,rate):view.getContext().getString(text));});
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
