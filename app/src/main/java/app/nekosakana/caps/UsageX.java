package app.nekosakana.caps;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 应用使用情况：当前前台应用、使用统计与进入历史。 */
public final class UsageX {
    private UsageX() {
    }

    public static boolean hasAccess(Context c) {
        return PermsX.hasUsageAccess(c);
    }

    public static String currentApp(Context c) {
        AccessService service = AccessService.get();
        if (service != null && !service.foregroundPackage().isEmpty()) {
            return service.foregroundPackage();
        }
        return queryCurrent(c);
    }

    private static String queryCurrent(Context c) {
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 6 * 3600_000L, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = "";
            long lastTs = 0;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (e.getTimeStamp() >= lastTs) {
                        lastTs = e.getTimeStamp();
                        last = e.getPackageName();
                    }
                }
            }
            return last;
        } catch (Exception e) {
            return "";
        }
    }

    /** 最近一段时间的使用统计。 */
    public static JSONArray stats(Context c, long sinceMs) {
        JSONArray arr = new JSONArray();
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            Map<String, UsageStats> map = usm.queryAndAggregateUsageStats(sinceMs, System.currentTimeMillis());
            List<UsageStats> list = new ArrayList<>(map.values());
            Collections.sort(list, Comparator.comparingLong(UsageStats::getTotalTimeInForeground).reversed());
            for (UsageStats s : list) {
                if (s.getTotalTimeInForeground() <= 0) continue;
                JSONObject o = new JSONObject();
                o.put("pkg", s.getPackageName());
                o.put("label", label(c, s.getPackageName()));
                o.put("total", s.getTotalTimeInForeground());
                o.put("last", s.getLastTimeUsed());
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        return arr;
    }

    /** 进入/离开应用的历史事件。 */
    public static JSONArray events(Context c, long sinceMs, int limit) {
        List<JSONObject> list = new ArrayList<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usm.queryEvents(sinceMs, System.currentTimeMillis());
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                int type = e.getEventType();
                boolean resumed = type == UsageEvents.Event.ACTIVITY_RESUMED || type == UsageEvents.Event.MOVE_TO_FOREGROUND;
                boolean paused = type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.MOVE_TO_BACKGROUND;
                if (!resumed && !paused) continue;
                JSONObject o = new JSONObject();
                o.put("ts", e.getTimeStamp());
                o.put("pkg", e.getPackageName());
                o.put("label", label(c, e.getPackageName()));
                o.put("action", resumed ? "进入" : "离开");
                list.add(o);
            }
        } catch (Exception ignored) {
        }
        JSONArray arr = new JSONArray();
        int from = Math.max(0, list.size() - limit);
        for (int i = from; i < list.size(); i++) arr.put(list.get(i));
        return arr;
    }

    public static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
