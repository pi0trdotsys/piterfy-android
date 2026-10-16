package com.eddyizm.tempus.github.utils;
import com.eddyizm.tempus.BuildConfig;
import com.eddyizm.tempus.github.models.LatestRelease;
import java.util.regex.*;
public class UpdateUtil {
    public static boolean showUpdateDialog(LatestRelease release) {
        return release != null && !Boolean.TRUE.equals(release.getDraft()) && !Boolean.TRUE.equals(release.getPrerelease())
            && isNewer(BuildConfig.VERSION_NAME, release.getTagName());
    }
    public static boolean isNewer(String current, String remote) {
        long[] local=parse(current), next=parse(remote);
        if(local==null||next==null)return false;
        for(int i=0;i<local.length;i++){if(next[i]!=local[i])return next[i]>local[i];}
        return false;
    }
    private static long[] parse(String value) {
        if(value==null)return null;
        Matcher m=Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)-piterfy\\.(\\d+)$").matcher(value);
        if(!m.matches())return null;
        try{return new long[]{Long.parseLong(m.group(1)),Long.parseLong(m.group(2)),Long.parseLong(m.group(3)),Long.parseLong(m.group(4))};}
        catch(NumberFormatException e){return null;}
    }
}
