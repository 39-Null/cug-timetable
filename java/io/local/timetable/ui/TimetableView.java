package io.local.timetable.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.local.timetable.model.Course;
import io.local.timetable.model.Exam;
import io.local.timetable.util.Prefs;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Weeks;

/**
 * 课表视图：
 * - 三页横滑（上一周 / 本周 / 下一周），跟手 + 回弹动画
 * - 课程块默认按 1-2 节跨两行显示
 * - 长按课程可拖动（移动）或拖下边缘（改结束节次）
 * - 点击左侧节次列 → 回调修改该小节时间
 */
public class TimetableView extends View {

    public interface Listener {
        void onCourseTap(Course course);

        void onWeekChanged(int weekDelta);

        void onSectionTimeTap(int sectionIndex);

        void onCourseEdited(Course course);

        void onExamTap(Exam exam);

        /** 点击空白格子：新建课程/实验。 */
        void onEmptyCellTap(int day, int section);
    }

    private static final int[] COLORS = {
            0xFF5B8DEF, 0xFF34C759, 0xFFFF9500, 0xFFAF52DE,
            0xFFFF3B30, 0xFF00B8D4, 0xFFFFB300, 0xFF7C4DFF
    };

    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint teacherPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint todayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final List<Course> courses = new ArrayList<>();
    private final List<Exam> exams = new ArrayList<>();
    private final Paint examFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint examText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String semesterStart = "";
    private boolean transparentBg = false;
    private Theme.Palette palette = Theme.light();
    private int gridAlpha = 100;
    private boolean dark = false;
    private String[] times = new String[0];
    private String[] dayDates = new String[7];
    private int sections = 8;
    private int currentWeek = 1;
    private int totalWeeks = 20;
    private int leftWidth;
    private int headerHeight;
    private float rowHeight;
    private float colWidth;
    private int todayColumn = -1;
    private int todayWeek = -1;

    public void setToday(int column, int week) {
        todayColumn = column;
        todayWeek = week;
        invalidate();
    }

    private Listener listener;

    // 滑动
    private final Scroller scroller;
    private float scrollOffset;
    private boolean scrolling;
    private float downX;
    private float downY;
    private long downTime;
    private int touchSlop;

    // 拖动课程
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean dragMode;
    private boolean resizeMode;
    private Course dragCourse;
    private int dragDay;
    private int dragStart;
    private int dragEnd;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            startDrag();
        }
    };

    public TimetableView(Context context) {
        this(context, null);
    }

    public TimetableView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float d = getResources().getDisplayMetrics().density;
        leftWidth = (int) (42 * d);
        headerHeight = (int) (40 * d);
        touchSlop = (int) (6 * d);

        titlePaint.setColor(0xFF1B1F27);
        titlePaint.setTextSize(10.5f * d);
        titlePaint.setFakeBoldText(true);

        roomPaint.setColor(0xFF5F6B7A);
        roomPaint.setTextSize(9.5f * d);
        teacherPaint.setColor(0xFF8A93A3);
        teacherPaint.setTextSize(9 * d);

        timePaint.setColor(0xFF8A93A3);
        timePaint.setTextSize(9f * d);
        timePaint.setTextAlign(Paint.Align.CENTER);

        smallPaint.setColor(0xFFA3AAB7);
        smallPaint.setTextSize(7.5f * d);
        smallPaint.setTextAlign(Paint.Align.CENTER);

        linePaint.setColor(0xFFE9ECF2);
        linePaint.setStrokeWidth(Math.max(1f, d * 0.6f));

        headerPaint.setColor(0xFF6B7280);
        headerPaint.setTextSize(10.5f * d);
        headerPaint.setTextAlign(Paint.Align.CENTER);

        todayPaint.setColor(0x0F3F6BE0);

        palette = Theme.p(context);
        dark = Theme.isDark(context);
        gridAlpha = new Prefs(context).gridAlpha();
        setBackgroundColor(palette.bg);
        scroller = new Scroller(context);

        Calendar cal = Calendar.getInstance();
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        todayColumn = dow == Calendar.SUNDAY ? 7 : dow - 1;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setExams(List<Exam> data) {
        exams.clear();
        if (data != null) {
            exams.addAll(data);
        }
        invalidate();
    }

    public void setSemesterStart(String start) {
        semesterStart = start == null ? "" : start;
        invalidate();
    }

    /** 背景图模式下让课表透明，露出窗口背景。 */
    public void setTransparentBackground(boolean transparent) {
        transparentBg = transparent;
        setBackgroundColor(transparent ? Color.TRANSPARENT : palette.bg);
        invalidate();
    }

    /** 主题切换后刷新调色板。 */
    public void refreshTheme() {
        palette = Theme.p(getContext());
        dark = Theme.isDark(getContext());
        gridAlpha = new Prefs(getContext()).gridAlpha();
        setBackgroundColor(transparentBg ? Color.TRANSPARENT : palette.bg);
        invalidate();
    }

    public void setData(List<Course> data, int sections, int currentWeek, int totalWeeks, String[] times, String[] dayDates) {
        courses.clear();
        if (data != null) {
            courses.addAll(data);
        }
        this.sections = Math.max(2, sections);
        this.currentWeek = Math.max(1, currentWeek);
        this.totalWeeks = Math.max(this.currentWeek, totalWeeks);
        this.times = times == null ? new String[0] : times;
        this.dayDates = dayDates == null ? new String[7] : dayDates;
        scrollOffset = 0;
        invalidate();
    }

    public void setCurrentWeek(int week) {
        currentWeek = Math.max(1, Math.min(totalWeeks, week));
        scrollOffset = 0;
        invalidate();
    }

    // ---------------- 绘制 ----------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        colWidth = (w - leftWidth) / 7f;
        rowHeight = (h - headerHeight) / (float) sections;

        for (int panel = -1; panel <= 1; panel++) {
            int week = currentWeek + panel;
            if (week < 1 || week > totalWeeks) {
                continue;
            }
            int save = canvas.save();
            canvas.translate(panel * w + scrollOffset, 0);
            drawPanel(canvas, week, w, h);
            canvas.restoreToCount(save);
        }
    }

    private void drawPanel(Canvas canvas, int week, int w, int h) {
        float d = getResources().getDisplayMetrics().density;

        boolean isToday = week == todayWeek;
        if (isToday && todayColumn >= 1 && todayColumn <= 7) {
            todayPaint.setColor(dark ? 0x1A7C9BF7 : 0x0F3F6BE0);
            canvas.drawRect(leftWidth + colWidth * (todayColumn - 1), 0,
                    leftWidth + colWidth * todayColumn, h, todayPaint);
        }
        if (transparentBg) {
            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(palette.cardAlpha);
            canvas.drawRect(0, 0, w, headerHeight, fillPaint);
            canvas.drawRect(0, headerHeight, leftWidth, h, fillPaint);
        }

        String[] days = {"一", "二", "三", "四", "五", "六", "日"};
        for (int i = 0; i < 7; i++) {
            float cx = leftWidth + colWidth * i + colWidth / 2f;
            boolean today = isToday && i + 1 == todayColumn;
            headerPaint.setColor(today ? palette.primary : (dark ? 0xFFC9D1DE : 0xFF4B5563));
            headerPaint.setTextSize(10.5f * d);
            headerPaint.setFakeBoldText(true);
            canvas.drawText(days[i], cx, headerHeight - 22 * d, headerPaint);
            if (dayDates != null && i < dayDates.length && dayDates[i] != null && !dayDates[i].isEmpty()) {
                smallPaint.setColor(today ? palette.primary : (dark ? 0xFF8A93A3 : 0xFF9AA3B2));
                smallPaint.setTextSize(8.5f * d);
                canvas.drawText(dayDates[i], cx, headerHeight - 8 * d, smallPaint);
            }
        }
        headerPaint.setFakeBoldText(false);

        timePaint.setColor(palette.sub);
        smallPaint.setColor(dark ? 0xFF7C8696 : 0xFF8A93A3);
        linePaint.setColor(withAlpha(palette.line, 255 * gridAlpha / 100));
        titlePaint.setColor(palette.text);
        roomPaint.setColor(dark ? 0xFFB8C0CC : 0xFF5F6B7A);
        teacherPaint.setColor(dark ? 0xFF97A1B0 : 0xFF8A93A3);

        for (int s = 1; s <= sections; s++) {
            float top = headerHeight + (s - 1) * rowHeight;
            float center = top + rowHeight / 2f;
            canvas.drawText(String.valueOf(s), leftWidth / 2f, center - 1 * d, timePaint);
            if (s - 1 < times.length && times[s - 1] != null) {
                String t = times[s - 1];
                int dash = t.indexOf('-');
                String start = dash > 0 ? t.substring(0, dash) : t;
                canvas.drawText(start, leftWidth / 2f, center + 8 * d, smallPaint);
            }
        }

        canvas.drawLine(0, headerHeight, w, headerHeight, linePaint);
        for (int i = 0; i <= 7; i++) {
            float x = leftWidth + colWidth * i;
            canvas.drawLine(x, headerHeight, x, h, linePaint);
        }
        for (int s = 0; s <= sections; s++) {
            float y = headerHeight + rowHeight * s;
            canvas.drawLine(leftWidth, y, w, y, linePaint);
        }

        for (Course c : courses) {
            boolean isDrag = dragMode && c == dragCourse;
            int day = isDrag ? dragDay : c.day;
            int start = isDrag ? dragStart : c.start;
            int end = isDrag ? dragEnd : c.end;
            if (!isDrag && !c.activeIn(week)) {
                continue;
            }
            day = Math.max(1, Math.min(7, day));
            start = Math.max(1, Math.min(sections, start));
            end = Math.max(start, Math.min(sections, end));

            float left = leftWidth + colWidth * (day - 1) + 2f * d;
            float right = left + colWidth - 4f * d;
            float top = headerHeight + rowHeight * (start - 1) + 2f * d;
            float bottom = headerHeight + rowHeight * end - 2f * d;
            rect.set(left, top, right, bottom);

            boolean isLab = c.type == 1;
            int color = isLab ? 0xFFF59E0B
                    : (c.color >= 0 && c.color < COLORS.length
                    ? COLORS[c.color]
                    : COLORS[Math.abs((c.name == null ? "" : c.name).hashCode()) % COLORS.length]);

            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(isLab
                    ? (dark ? 0xFF2A2214 : 0xFFFFF6E5)
                    : withAlpha(color, isDrag ? 70 : 34));
            canvas.drawRoundRect(rect, 7 * d, 7 * d, fillPaint);

            fillPaint.setStyle(Paint.Style.STROKE);
            fillPaint.setStrokeWidth((isDrag ? 1.6f : 1f) * d);
            fillPaint.setColor(withAlpha(color, isLab ? 210 : 160));
            canvas.drawRoundRect(rect, 7 * d, 7 * d, fillPaint);

            canvas.save();
            canvas.clipRect(rect);
            float textLeft = left + 4 * d;
            float maxWidth = right - left - 8 * d;
            float bottomLimit = bottom - 2.5f * d;
            String nameText = c.name == null || c.name.isEmpty() ? "Null" : c.name;
            String roomText = c.room == null || c.room.isEmpty() ? "Null" : c.room;
            String teacherText = c.teacher == null || c.teacher.isEmpty() ? "Null" : c.teacher;

            float y = top + 12 * d;
            if (isLab) {
                examText.setColor(Color.WHITE);
                examText.setTextSize(7.5f * d);
                examText.setFakeBoldText(true);
                float chipW = examText.measureText("实验") + 8 * d;
                RectF chip = new RectF(textLeft, top + 3 * d, textLeft + chipW, top + 3 * d + 12 * d);
                examFill.setStyle(Paint.Style.FILL);
                examFill.setColor(color);
                canvas.drawRoundRect(chip, 4 * d, 4 * d, examFill);
                canvas.drawText("实验", chip.left + 4 * d, chip.top + 9 * d, examText);
                examText.setFakeBoldText(false);
                y = chip.bottom + 11 * d;
            }
            int n = drawBlockText(canvas, nameText, titlePaint, textLeft, y, maxWidth,
                    2, 12 * d, bottomLimit);
            y += n * 12 * d;
            if (y <= bottomLimit - 1 * d) {
                n = drawBlockText(canvas, roomText, roomPaint, textLeft, y, maxWidth,
                        2, 10 * d, bottomLimit);
                y += n * 10 * d;
            }
            if (y <= bottomLimit - 1 * d) {
                drawTeacher(canvas, teacherText, textLeft, y, maxWidth, bottomLimit, d);
            }
            canvas.restore();
        }

        // 考试：按日期换算到周次，按开考时间落到大致节次
        for (Exam e : exams) {
            int[] slot = examSlot(e, week);
            if (slot == null) {
                continue;
            }
            drawExamBlock(canvas, e, slot[0], slot[1], slot[2], d);
        }
    }

    // ---------------- 考试 ----------------

    /** 返回 {星期(1-7), 起始节, 结束节}；不在这一周返回 null。 */
    private int[] examSlot(Exam e, int week) {
        if (e == null || semesterStart == null || semesterStart.isEmpty()) {
            return null;
        }
        String date = dateOf(e.time);
        if (date.isEmpty()) {
            return null;
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            Date start = f.parse(semesterStart);
            Date d = f.parse(date);
            if (start == null || d == null) {
                return null;
            }
            long days = (d.getTime() - start.getTime()) / (24L * 3600L * 1000L);
            int w = (int) (days / 7) + 1;
            if (w != week) {
                return null;
            }
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            int dow = c.get(Calendar.DAY_OF_WEEK);
            int day = dow == Calendar.SUNDAY ? 7 : dow - 1;
            int startSection = sectionOf(clockOf(e.time, true));
            int endSection = startSection;
            String endClock = clockOf(e.time, false);
            if (!endClock.isEmpty()) {
                endSection = Math.max(startSection, sectionOf(endClock));
            }
            if (endSection == startSection) {
                endSection = Math.min(sections, startSection + 1);
            }
            return new int[]{day, startSection, endSection};
        } catch (Exception ignored) {
            return null;
        }
    }

    private void drawExamBlock(Canvas canvas, Exam e, int day, int start, int end, float d) {
        day = Math.max(1, Math.min(7, day));
        start = Math.max(1, Math.min(sections, start));
        end = Math.max(start, Math.min(sections, end));

        float left = leftWidth + colWidth * (day - 1) + 2f * d;
        float right = left + colWidth - 4f * d;
        float top = headerHeight + rowHeight * (start - 1) + 2f * d;
        float bottom = headerHeight + rowHeight * end - 2f * d;
        rect.set(left, top, right, bottom);

        int red = dark ? 0xFFFF6B6F : 0xFFE5484D;
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(dark ? 0xFF2B1B1E : 0xFFFFF1F2);
        canvas.drawRoundRect(rect, 7 * d, 7 * d, fillPaint);
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeWidth(1.2f * d);
        fillPaint.setColor(withAlpha(red, 200));
        canvas.drawRoundRect(rect, 7 * d, 7 * d, fillPaint);

        canvas.save();
        canvas.clipRect(rect);
        float textLeft = left + 4 * d;
        float maxWidth = right - left - 8 * d;
        float bottomLimit = bottom - 2.5f * d;

        // “考试”标签
        examText.setColor(Color.WHITE);
        examText.setTextSize(7.5f * d);
        examText.setFakeBoldText(true);
        float chipW = examText.measureText("考试") + 8 * d;
        float chipH = 12 * d;
        RectF chip = new RectF(textLeft, top + 3 * d, textLeft + chipW, top + 3 * d + chipH);
        examFill.setStyle(Paint.Style.FILL);
        examFill.setColor(red);
        canvas.drawRoundRect(chip, 4 * d, 4 * d, examFill);
        canvas.drawText("考试", chip.left + 4 * d, chip.top + 9 * d, examText);
        examText.setFakeBoldText(false);

        float y = chip.bottom + 11 * d;
        int n = drawBlockText(canvas, e.course == null || e.course.isEmpty() ? "考试" : e.course,
                titlePaint, textLeft, y, maxWidth, 2, 11.5f * d, bottomLimit);
        y += n * 11.5f * d;
        if (y <= bottomLimit - 1 * d) {
            String room = e.place == null || e.place.isEmpty() ? "地点待定" : e.place;
            n = drawBlockText(canvas, room, roomPaint, textLeft, y, maxWidth, 2, 10 * d, bottomLimit);
            y += n * 10 * d;
        }
        if (y <= bottomLimit - 1 * d) {
            String clock = clockOf(e.time, true);
            drawBlockText(canvas, clock, teacherPaint, textLeft, y, maxWidth, 1, 10 * d, bottomLimit);
        }
        canvas.restore();
    }

    private static String dateOf(String time) {
        if (time == null) {
            return "";
        }
        Matcher m = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})").matcher(time);
        return m.find() ? m.group(1) : "";
    }

    private static String clockOf(String time, boolean start) {
        if (time == null) {
            return "";
        }
        Matcher m = Pattern.compile("(\\d{1,2}:\\d{2})(?:-(\\d{1,2}:\\d{2}))?").matcher(time);
        if (!m.find()) {
            return "";
        }
        String a = m.group(1);
        String b = m.group(2);
        return start ? a : (b == null ? "" : b);
    }

    /** 把 "09:00" 落到最接近的小节（1 起）。 */
    private int sectionOf(String clock) {
        int target = minutes(clock);
        if (target < 0) {
            return 1;
        }
        int best = 1;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < sections && i < times.length; i++) {
            String t = times[i];
            int dash = t.indexOf('-');
            int a = minutes(dash > 0 ? t.substring(0, dash) : t);
            int b = minutes(dash > 0 ? t.substring(dash + 1) : t);
            if (a < 0) {
                continue;
            }
            if (b < 0) {
                b = a + 45;
            }
            if (target >= a && target <= b) {
                return i + 1;
            }
            int diff = Math.min(Math.abs(target - a), Math.abs(target - b));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i + 1;
            }
        }
        return best;
    }

    private static int minutes(String clock) {
        if (clock == null) {
            return -1;
        }
        Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(clock);
        if (!m.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 老师名：2~3 个汉字优先缩小字号完整显示，不省略；4 字及以上才截断。 */
    private void drawTeacher(Canvas canvas, String text, float x, float y,
                             float maxWidth, float bottomLimit, float d) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float original = teacherPaint.getTextSize();
        String plain = text.replace(" ", "");
        if (plain.length() <= 3) {
            float size = original;
            while (size > 7.5f * d && teacherPaint.measureText(text) > maxWidth) {
                size -= 0.4f * d;
                teacherPaint.setTextSize(size);
            }
            canvas.drawText(text, x, y, teacherPaint);
            teacherPaint.setTextSize(original);
            return;
        }
        drawBlockText(canvas, text, teacherPaint, x, y, maxWidth, 1, 10 * d, bottomLimit);
    }

    /** 按行画文本，超出底部就停；返回实际画了几行。 */
    private int drawBlockText(Canvas canvas, String text, Paint paint, float x, float y,
                              float maxWidth, int maxLines, float lineHeight, float bottomLimit) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        List<String> lines = wrap(text, paint, maxWidth, maxLines);
        int i = 0;
        for (String line : lines) {
            float baseline = y + i * lineHeight;
            if (baseline > bottomLimit) {
                break;
            }
            canvas.drawText(line, x, baseline, paint);
            i++;
        }
        return i;
    }

    private int drawLines(Canvas canvas, String text, Paint paint, float x, float y,
                          float maxWidth, int maxLines, float lineHeight, float blockHeight, float used) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        List<String> lines = wrap(text, paint, maxWidth, maxLines);
        int i = 0;
        for (String line : lines) {
            if (used + (i + 1) * lineHeight > blockHeight + 2) {
                break;
            }
            canvas.drawText(line, x, y + i * lineHeight, paint);
            i++;
        }
        return Math.max(1, i);
    }

    private static List<String> wrap(String text, Paint paint, float maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            cur.append(ch);
            if (paint.measureText(cur.toString()) > maxWidth) {
                cur.deleteCharAt(cur.length() - 1);
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    cur.append(ch);
                }
                if (out.size() >= maxLines) {
                    break;
                }
            }
        }
        if (out.size() < maxLines && cur.length() > 0) {
            String last = cur.toString();
            if (out.size() == maxLines - 1 && paint.measureText(last + "…") > maxWidth) {
                while (last.length() > 1 && paint.measureText(last + "…") > maxWidth) {
                    last = last.substring(0, last.length() - 1);
                }
                last = last + "…";
            }
            out.add(last);
        }
        return out;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // ---------------- 触摸 ----------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float d = getResources().getDisplayMetrics().density;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = System.currentTimeMillis();
                scrolling = false;
                dragMode = false;
                resizeMode = false;
                dragCourse = null;
                if (scrollOffset == 0) {
                    handler.postDelayed(longPressRunnable, 380);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (dragMode) {
                    updateDrag(event.getY());
                    invalidate();
                    return true;
                }
                if (!scrolling && Math.abs(dx) > touchSlop * 2 && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    scrolling = true;
                    handler.removeCallbacks(longPressRunnable);
                }
                if (scrolling) {
                    scrollOffset = dx;
                    invalidate();
                } else if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                    handler.removeCallbacks(longPressRunnable);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPressRunnable);
                if (dragMode) {
                    finishDrag();
                    return true;
                }
                if (scrolling) {
                    settle();
                    return true;
                }
                handleTap(event.getX(), event.getY());
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void settle() {
        int w = getWidth();
        boolean next = scrollOffset < -w * 0.28f;
        boolean prev = scrollOffset > w * 0.28f;
        if (next || prev) {
            int target = next ? -w : w;
            int delta = next ? 1 : -1;
            scroller.startScroll((int) scrollOffset, 0, target - (int) scrollOffset, 0, 220);
            postInvalidateOnAnimation();
            pendingWeekDelta = delta;
            pendingTarget = target;
        } else {
            scroller.startScroll((int) scrollOffset, 0, -(int) scrollOffset, 0, 180);
            postInvalidateOnAnimation();
            pendingWeekDelta = 0;
            pendingTarget = 0;
        }
    }

    private int pendingWeekDelta;
    private int pendingTarget;

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.getCurrX();
            invalidate();
        } else if (pendingWeekDelta != 0) {
            int delta = pendingWeekDelta;
            pendingWeekDelta = 0;
            scrollOffset = 0;
            if (listener != null) {
                listener.onWeekChanged(delta);
            }
            invalidate();
        }
    }

    private void handleTap(float x, float y) {
        if (listener == null) {
            return;
        }
        if (x <= leftWidth) {
            int section = (int) ((y - headerHeight) / rowHeight);
            if (section >= 0 && section < sections) {
                listener.onSectionTimeTap(section);
            }
            return;
        }
        if (y <= headerHeight) {
            return;
        }
        int day = (int) ((x - leftWidth) / colWidth) + 1;
        int section = (int) ((y - headerHeight) / rowHeight) + 1;
        Exam ex = examAt(day, section);
        if (ex != null) {
            listener.onExamTap(ex);
            return;
        }
        for (Course c : courses) {
            if (c.day == day && section >= c.start && section <= c.end && c.activeIn(currentWeek)) {
                listener.onCourseTap(c);
                return;
            }
        }
        listener.onEmptyCellTap(day, section);
    }

    private Exam examAt(int day, int section) {
        for (Exam e : exams) {
            int[] slot = examSlot(e, currentWeek);
            if (slot != null && slot[0] == day && section >= slot[1] && section <= slot[2]) {
                return e;
            }
        }
        return null;
    }

    private Course courseAt(float x, float y) {
        if (x <= leftWidth || y <= headerHeight) {
            return null;
        }
        int day = (int) ((x - leftWidth) / colWidth) + 1;
        int section = (int) ((y - headerHeight) / rowHeight) + 1;
        for (Course c : courses) {
            if (c.day == day && section >= c.start && section <= c.end && c.activeIn(currentWeek)) {
                return c;
            }
        }
        return null;
    }

    private void startDrag() {
        Course c = courseAt(downX, downY);
        if (c == null) {
            return;
        }
        dragMode = true;
        dragCourse = c;
        dragDay = c.day;
        dragStart = c.start;
        dragEnd = c.end;
        float blockBottom = headerHeight + rowHeight * c.end;
        resizeMode = Math.abs(downY - blockBottom) < 18 * getResources().getDisplayMetrics().density;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void updateDrag(float y) {
        int section = (int) ((y - headerHeight) / rowHeight) + 1;
        section = Math.max(1, Math.min(sections, section));
        if (resizeMode) {
            dragEnd = Math.max(dragStart, section);
        } else {
            int day = (int) ((downX - leftWidth) / colWidth) + 1;
            day = Math.max(1, Math.min(7, day));
            int span = dragEnd - dragStart;
            dragDay = day;
            dragStart = Math.max(1, Math.min(sections - span, section));
            dragEnd = dragStart + span;
        }
    }

    private void finishDrag() {
        if (dragCourse != null && listener != null) {
            if (dragDay != dragCourse.day || dragStart != dragCourse.start || dragEnd != dragCourse.end) {
                dragCourse.day = dragDay;
                dragCourse.start = dragStart;
                dragCourse.end = dragEnd;
                listener.onCourseEdited(dragCourse);
            }
        }
        dragMode = false;
        resizeMode = false;
        dragCourse = null;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(longPressCallbacks);
        super.onDetachedFromWindow();
    }

    private final Runnable longPressCallbacks = longPressRunnable;
}
