package io.local.timetable.util;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.view.Window;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import io.local.timetable.R;

/** 明暗主题、背景图与调色板。 */
public final class Theme {

    /** 一套界面颜色。 */
    public static final class Palette {
        public int bg;
        public int card;
        public int cardAlpha;
        public int line;
        public int text;
        public int sub;
        public int faint;
        public int primary;
        public int primaryLight;
        public int danger;
    }

    private static Palette light;
    private static Palette dark;

    private Theme() {
    }

    public static Palette light() {
        if (light == null) {
            light = new Palette();
            light.bg = 0xFFF5F6FA;
            light.card = 0xFFFFFFFF;
            light.cardAlpha = 0xE6FFFFFF;
            light.line = 0xFFE6E9F0;
            light.text = 0xFF1B1F27;
            light.sub = 0xFF6B7280;
            light.faint = 0xFF9AA3B2;
            light.primary = 0xFF3F6BE0;
            light.primaryLight = 0xFFE8EEFF;
            light.danger = 0xFFE5484D;
        }
        return light;
    }

    public static Palette dark() {
        if (dark == null) {
            dark = new Palette();
            dark.bg = 0xFF0F1216;
            dark.card = 0xFF171C23;
            dark.cardAlpha = 0xF2161B22;
            dark.line = 0xFF2A3240;
            dark.text = 0xFFE8EBF0;
            dark.sub = 0xFF9AA3B2;
            dark.faint = 0xFF7C8696;
            dark.primary = 0xFF7C9BF7;
            dark.primaryLight = 0xFF1D2942;
            dark.danger = 0xFFFF6B6F;
        }
        return dark;
    }

    public static boolean isDark(Context ctx) {
        return new Prefs(ctx).dark();
    }

    public static Palette p(Context ctx) {
        return isDark(ctx) ? dark() : light();
    }

    /** 必须在 super.onCreate() 之前调用。 */
    public static void prepare(Activity a) {
        a.setTheme(isDark(a) ? R.style.AppTheme_Dark : R.style.AppTheme);
    }

    /** 设置窗口背景（纯色或图片 + 遮罩），并适配状态栏。 */
    public static void decorate(Activity a, View root) {
        Prefs prefs = new Prefs(a);
        Palette p = p(a);
        Window win = a.getWindow();
        win.setStatusBarColor(p.bg);
        win.setNavigationBarColor(p.card);

        if (Build.VERSION.SDK_INT >= 23) {
            View decor = win.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (!isDark(a)) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }

        String path = prefs.bgImage();
        Drawable img = null;
        if (path != null && !path.isEmpty()) {
            img = loadBackground(a, path);
        }
        if (img != null) {
            int dim = prefs.bgDim() * 255 / 100;
            int scrim = isDark(a)
                    ? Color.argb(dim, 0, 0, 0)
                    : Color.argb(dim, 255, 255, 255);
            LayerDrawable layer = new LayerDrawable(new Drawable[]{
                    img, new ColorDrawable(scrim)});
            win.setBackgroundDrawable(layer);
            if (root != null) {
                root.setBackgroundColor(Color.TRANSPARENT);
            }
        } else {
            win.setBackgroundDrawable(new ColorDrawable(p.bg));
        }
    }

    /** 主题签名，用于 Activity 返回时判断是否需要重建。 */
    public static String signature(Context ctx) {
        Prefs p = new Prefs(ctx);
        return (p.dark() ? "d" : "l") + "|" + p.bgImage() + "|" + p.bgDim() + "|" + p.gridAlpha()
                + "|" + p.bgStamp();
    }

    public static boolean hasBackground(Context ctx) {
        String path = new Prefs(ctx).bgImage();
        return path != null && !path.isEmpty() && new File(path).exists();
    }

    /** 按屏幕尺寸降采样解码，避免大照片把内存撑爆。 */
    public static Drawable loadBackground(Context ctx, String path) {
        android.graphics.Bitmap bmp = loadBitmap(path);
        if (bmp == null) {
            return null;
        }
        return new CropDrawable(bmp);
    }

    private static android.graphics.Bitmap loadBitmap(String path) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, bounds);
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (max / sample > 1600) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            return android.graphics.BitmapFactory.decodeFile(path, opts);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 背景图按「填充裁切」绘制：保持原图比例，铺满窗口后裁掉多余部分，
     * 不拉伸、不压扁。
     */
    private static final class CropDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Bitmap bitmap;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

        CropDrawable(android.graphics.Bitmap bmp) {
            bitmap = bmp;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            if (bitmap == null || b.isEmpty()) {
                return;
            }
            float scale = Math.max(b.width() / (float) bitmap.getWidth(),
                    b.height() / (float) bitmap.getHeight());
            float w = bitmap.getWidth() * scale;
            float h = bitmap.getHeight() * scale;
            float left = b.left + (b.width() - w) / 2f;
            float top = b.top + (b.height() - h) / 2f;
            android.graphics.RectF dst = new android.graphics.RectF(left, top, left + w, top + h);
            canvas.drawBitmap(bitmap, null, dst, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }

        @Override
        public int getIntrinsicWidth() {
            return bitmap == null ? 0 : bitmap.getWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return bitmap == null ? 0 : bitmap.getHeight();
        }
    }

    /** 把用户选的图片复制到应用私有目录（待裁剪），返回本地路径。 */
    public static String importBackground(Context ctx, Uri uri) {
        File out = new File(ctx.getFilesDir(), "theme_pick");
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            fos = new FileOutputStream(out);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static File pickFile(Context ctx) {
        return new File(ctx.getFilesDir(), "theme_pick");
    }

    /** 未经裁剪的原图（用于「重新裁剪」）。 */
    public static File sourceFile(Context ctx) {
        return new File(ctx.getFilesDir(), "theme_src");
    }

    public static File bgFile(Context ctx) {
        return new File(ctx.getFilesDir(), "theme_bg");
    }

    public static boolean hasSource(Context ctx) {
        return sourceFile(ctx).exists() && sourceFile(ctx).length() > 0;
    }

    /** 旧版本只存了背景图，没有原图；首次升级时把现有背景当作原图，保证「重新裁剪」可用。 */
    public static void ensureSource(Context ctx) {
        File src = sourceFile(ctx);
        File bg = bgFile(ctx);
        if (src.exists() && src.length() > 0) {
            return;
        }
        if (bg.exists() && bg.length() > 0) {
            try {
                copy(bg, src);
            } catch (Exception ignored) {
            }
        }
    }

    /** 把裁剪结果写成背景图，返回路径。 */
    public static String saveCropped(Context ctx, android.graphics.Bitmap bmp) {
        if (bmp == null) {
            return null;
        }
        File out = bgFile(ctx);
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, fos);
            fos.flush();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception ignored) {
            }
            try {
                bmp.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    /** 裁剪成功后，把待裁剪图提升为「原图」，供下次重新裁剪。 */
    public static void promoteSource(Context ctx) {
        File pick = pickFile(ctx);
        File src = sourceFile(ctx);
        if (!pick.exists()) {
            return;
        }
        try {
            copy(pick, src);
            pick.delete();
        } catch (Exception ignored) {
        }
    }

    private static void copy(File from, File to) throws Exception {
        InputStream in = new java.io.FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void clearBackground(Context ctx) {
        String path = new Prefs(ctx).bgImage();
        if (path != null && !path.isEmpty()) {
            try {
                new File(path).delete();
            } catch (Exception ignored) {
            }
        }
        try {
            sourceFile(ctx).delete();
            pickFile(ctx).delete();
            bgFile(ctx).delete();
        } catch (Exception ignored) {
        }
        new Prefs(ctx).setBgImage("");
        new Prefs(ctx).setBgStamp(System.currentTimeMillis());
    }
}
