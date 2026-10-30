package io.local.timetable.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import io.local.timetable.MainActivity;
import io.local.timetable.R;
import io.local.timetable.util.Theme;

/** 桌面小组件：今天课程 + 最近考试。 */
public class TimetableWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "io.local.timetable.WIDGET_REFRESH";

    private static final int[] ROW_IDS = {
            R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
            R.id.widget_row_4, R.id.widget_row_5, R.id.widget_row_6
    };
    private static final int[] TIME_IDS = {
            R.id.widget_time_1, R.id.widget_time_2, R.id.widget_time_3,
            R.id.widget_time_4, R.id.widget_time_5, R.id.widget_time_6
    };
    private static final int[] NAME_IDS = {
            R.id.widget_name_1, R.id.widget_name_2, R.id.widget_name_3,
            R.id.widget_name_4, R.id.widget_name_5, R.id.widget_name_6
    };
    private static final int[] ROOM_IDS = {
            R.id.widget_room_1, R.id.widget_room_2, R.id.widget_room_3,
            R.id.widget_room_4, R.id.widget_room_5, R.id.widget_room_6
    };

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            update(context, manager, id);
        }
        WidgetUpdater.schedule(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        update(context, manager, appWidgetId);
    }

    @Override
    public void onEnabled(Context context) {
        WidgetUpdater.schedule(context);
    }

    @Override
    public void onDisabled(Context context) {
        WidgetUpdater.cancel(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (ACTION_REFRESH.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            WidgetUpdater.refresh(context);
            WidgetUpdater.schedule(context);
        }
    }

    static void update(Context context, AppWidgetManager manager, int appWidgetId) {
        WidgetData data = WidgetData.load(context);
        Theme.Palette p = Theme.p(context);
        boolean dark = Theme.isDark(context);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_timetable);
        views.setInt(R.id.widget_root, "setBackgroundResource",
                dark ? R.drawable.widget_bg_dark : R.drawable.widget_bg);
        views.setTextColor(R.id.widget_title, p.text);
        views.setTextColor(R.id.widget_week, p.sub);
        views.setTextViewText(R.id.widget_week, data.weekText);

        if (data.examText.isEmpty()) {
            views.setViewVisibility(R.id.widget_exam, View.GONE);
        } else {
            views.setViewVisibility(R.id.widget_exam, View.VISIBLE);
            views.setTextViewText(R.id.widget_exam, data.examText);
            views.setTextColor(R.id.widget_exam, p.danger);
        }

        int maxRows = rowsFor(manager, appWidgetId);
        for (int i = 0; i < ROW_IDS.length; i++) {
            if (i < data.rows.size() && i < maxRows) {
                WidgetData.Row row = data.rows.get(i);
                views.setViewVisibility(ROW_IDS[i], View.VISIBLE);
                views.setTextViewText(TIME_IDS[i], row.time);
                views.setTextViewText(NAME_IDS[i], row.name);
                views.setTextViewText(ROOM_IDS[i], row.room);
                views.setTextColor(TIME_IDS[i], row.current ? p.primary : p.sub);
                views.setTextColor(NAME_IDS[i], row.current ? p.primary : p.text);
                views.setTextColor(ROOM_IDS[i], p.sub);
            } else {
                views.setViewVisibility(ROW_IDS[i], View.GONE);
            }
        }
        if (data.rows.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE);
            views.setTextViewText(R.id.widget_empty,
                    data.weekText.startsWith("第 ") ? "今天没有课" : "打开 App 设置开学日期");
            views.setTextColor(R.id.widget_empty, p.sub);
        } else {
            views.setViewVisibility(R.id.widget_empty, View.GONE);
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        views.setOnClickPendingIntent(R.id.widget_title, pi);
        views.setOnClickPendingIntent(R.id.widget_week, pi);
        views.setOnClickPendingIntent(R.id.widget_exam, pi);
        views.setOnClickPendingIntent(R.id.widget_empty, pi);
        for (int id : ROW_IDS) {
            views.setOnClickPendingIntent(id, pi);
        }

        manager.updateAppWidget(appWidgetId, views);
    }

    private static int rowsFor(AppWidgetManager manager, int appWidgetId) {
        Bundle options = manager.getAppWidgetOptions(appWidgetId);
        int maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        if (maxHeight <= 0) {
            maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        }
        if (maxHeight <= 0) {
            return 4;
        }
        return Math.max(1, Math.min(6, (maxHeight - 30) / 38));
    }

    /** 供设置页请求固定到桌面。 */
    public static ComponentName component(Context context) {
        return new ComponentName(context, TimetableWidgetProvider.class);
    }
}
