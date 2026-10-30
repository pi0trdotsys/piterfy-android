package com.eddyizm.tempus.ui.components;

import android.content.Context;
import androidx.preference.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PiterfyPlaybackPreferences {
    private PiterfyPlaybackPreferences(){}
    public static void install(PreferenceScreen screen) {
        Context context=screen.getContext();
        var prefs=context.getSharedPreferences("piterfy_playback",0);
        PreferenceCategory category=new com.eddyizm.tempus.util.ClickablePreferenceCategory(context,null);category.setKey("piterfy_playback_preferences");category.setTitle("Piterfy · odtwarzanie i odkrywanie");category.setOrder(-10);screen.addPreference(category);
        SwitchPreference fade=new SwitchPreference(context);fade.setTitle("Płynne przejścia");fade.setSummary("Następny utwór zaczyna grać pod wyciszanym końcem poprzedniego. Muzyka odtwarzana na tym telefonie.");fade.setPersistent(false);fade.setChecked(prefs.getBoolean("crossfade_enabled",true));category.addPreference(fade);
        SeekBarPreference length=new SeekBarPreference(context);length.setTitle("Długość przejścia · sekundy");length.setMin(1);length.setMax(10);length.setShowSeekBarValue(true);length.setPersistent(false);length.setValue(prefs.getInt("crossfade_seconds",5));length.setEnabled(fade.isChecked());category.addPreference(length);
        fade.setOnPreferenceChangeListener((p,value)->{com.eddyizm.tempus.service.PiterfyCrossfade.cancelActive();prefs.edit().putBoolean("crossfade_enabled",(Boolean)value).apply();length.setEnabled((Boolean)value);return true;});
        length.setOnPreferenceChangeListener((p,value)->{com.eddyizm.tempus.service.PiterfyCrossfade.cancelActive();prefs.edit().putInt("crossfade_seconds",(Integer)value).apply();return true;});
        Preference discover=new Preference(context);discover.setTitle("Odkrywaj · dopasuj swój gust");discover.setSummary("Więcej / mniej takich i poziom eksperymentowania");discover.setOnPreferenceClickListener(p->{com.eddyizm.tempus.ui.dialog.PiterfyDiscoveryDialog.show(context,null);return true;});category.addPreference(discover);
    }
}
