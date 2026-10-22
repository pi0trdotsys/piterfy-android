package com.eddyizm.tempus.util;

import android.content.Context;
import android.media.*;
import android.os.*;
import androidx.mediarouter.media.MediaRouter;
import com.eddyizm.tempus.R;
import java.util.function.Consumer;

/** Piterfy: reports the selected media route, not merely a paired Bluetooth device. */
public final class PiterfyOutputMonitor {
    private final Context context;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> listener;
    private boolean running;
    private String previous;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            String current = label();
            if (!current.equals(previous)) { previous = current; listener.accept(current); }
            main.postDelayed(this, 2000);
        }
    };
    private final AudioDeviceCallback callback = new AudioDeviceCallback() {
        public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { changed(); }
        public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { changed(); }
    };
    public PiterfyOutputMonitor(Context context, Consumer<String> listener) {
        this.context=context.getApplicationContext(); this.listener=listener;
        audio=(AudioManager)this.context.getSystemService(Context.AUDIO_SERVICE);
    }
    private void changed() { if(running) { main.removeCallbacks(tick); main.post(tick); } }
    public void start() {
        if(running || audio==null)return;running=true;previous=null;
        audio.registerAudioDeviceCallback(callback,main);main.post(tick);
    }
    public void stop() { running=false;main.removeCallbacks(tick);if(audio!=null)audio.unregisterAudioDeviceCallback(callback); }
    public String label() {
        return context.getString(R.string.piterfy_output, destination());
    }
    public String destination() {
        MediaRouter.RouteInfo route=MediaRouter.getInstance(context).getSelectedRoute();
        if(route!=null && route.getPlaybackType()==MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE)
            return String.valueOf(route.getName());
        if(audio!=null) {
            try {
                if(Build.VERSION.SDK_INT>=33) {
                    var devices=audio.getAudioDevicesForAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
                    if(!devices.isEmpty()) return describe(devices.get(0));
                } else {
                    for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                        if(audio.isBluetoothA2dpOn() && device.getType()==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return describe(device);
                        if(audio.isWiredHeadsetOn() && (device.getType()==AudioDeviceInfo.TYPE_WIRED_HEADSET||device.getType()==AudioDeviceInfo.TYPE_WIRED_HEADPHONES))return describe(device);
                    }
                }
            } catch(SecurityException ignored) { }
        }
        return context.getString(R.string.piterfy_device);
    }
    private String describe(AudioDeviceInfo device) {
        int type=device.getType();
        boolean bluetooth=type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP||type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            ||type==AudioDeviceInfo.TYPE_BLE_HEADSET||type==AudioDeviceInfo.TYPE_BLE_SPEAKER||type==AudioDeviceInfo.TYPE_HEARING_AID;
        String name=String.valueOf(device.getProductName());
        if(type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER||type==AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)name=context.getString(R.string.piterfy_device);
        else if(name.isBlank())name=context.getString(R.string.piterfy_headphones);
        return (bluetooth?"Bluetooth · ":"")+name;
    }
}
