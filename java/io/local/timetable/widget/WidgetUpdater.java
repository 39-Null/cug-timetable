package io.local.timetable.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.Calendar;

import io.local.timetable.util.Prefs;

/** 刷新与定时：数据变更时刷新，上课/下课时和 0 点重新调度。 */
public final class WidgetUpdater {

    private static final int REQ = 7;

    private WidgetUpdater() {
    }

    public static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager == null) {
            return;
        }
        int[] ids = manager.getAppWidgetIds(TimetableWidgetProvider.component(context));
        for (int id : ids) {
            TimetableWidgetProvider.update(context, manager, id);
        }
    }

    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(context);
        am.cancel(pi);
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC, nextTrigger(context), pi);
        } catch (Exception ignored) {
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pending(context));
        }
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, TimetableWidgetProvider.class);
        intent.setAction(TimetableWidgetProvider.ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, REQ, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 下一个刷新点：今天下一节课的开始/结束，否则明天 0 点。 */
    private static long nextTrigger(Context context) {
        Calendar now = Calendar.getInstance();
        Calendar midnight = (Calendar) now.clone();
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 10);
        midnight.set(Calendar.MILLISECOND, 0);
        long best = midnight.getTimeInMillis();

        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        String[] times = new Prefs(context).sectionTimes();
        Calendar candidate = (Calendar) now.clone();
        for (String t : times) {
            if (t == null) {
                continue;
            }
            int dash = t.indexOf('-');
            if (dash <= 0) {
                continue;
            }
            int a = minutes(t.substring(0, dash));
            int b = minutes(t.substring(dash + 1));
            int target = -1;
            if (a > nowMin) {
                target = a;
            } else if (b > nowMin) {
                target = b;
            }
            if (target >= 0) {
                candidate.set(Calendar.HOUR_OF_DAY, target / 60);
                candidate.set(Calendar.MINUTE, target % 60);
                candidate.set(Calendar.SECOND, 5);
                candidate.set(Calendar.MILLISECOND, 0);
                best = Math.min(best, candidate.getTimeInMillis());
                break;
            }
        }
        return best;
    }

    private static int minutes(String clock) {
        int colon = clock.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        try {
            return Integer.parseInt(clock.substring(0, colon)) * 60
                    + Integer.parseInt(clock.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
