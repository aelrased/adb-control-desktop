package com.adbappcontrol.bridge;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Headless exporter used by both the Activity and the ContentProvider.
 *
 * Every app is read in parallel, icons are compressed exactly once and packed
 * into a single {@code icons.zip} (plus {@code _apps.json}) so the desktop host
 * pulls one file instead of hundreds of small PNGs.
 */
public final class Exporter {

    /** Upper bound for the longest icon edge, in pixels. */
    private static final int MAX_ICON_SIZE = 256;
    private static final int DEFAULT_ICON_SIZE = 128;
    private static final int MAX_THREADS = 8;

    private static final String APPS_ENTRY = "_apps.json";

    private Exporter() {
    }

    public static void export(Context context) {
        export(context, DEFAULT_ICON_SIZE);
    }

    public static void export(Context context, int iconSize) {
        final int size = Math.max(16, Math.min(MAX_ICON_SIZE, iconSize));

        File base;
        if (Build.VERSION.SDK_INT < 29) {
            base = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ADBControlBridge"
            );
        } else {
            base = context.getExternalFilesDir(null);
        }
        if (base == null) {
            return;
        }
        if (!base.exists() && !base.mkdirs()) {
            return;
        }

        // Leftovers from older bridge builds (one PNG per app) are useless now.
        deleteChildren(new File(base, "icons"));
        new File(base, "apps.json").delete();
        new File(base, "icons.zip").delete();
        new File(base, "icons.zip.tmp").delete();
        new File(base, "ready").delete();

        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);

        int threads = Math.max(2, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<AppEntry>> futures = new ArrayList<>(apps.size());

        for (final ApplicationInfo ai : apps) {
            futures.add(pool.submit(new Callable<AppEntry>() {
                @Override
                public AppEntry call() {
                    return readApp(pm, ai, size);
                }
            }));
        }
        pool.shutdown();

        JSONArray out = new JSONArray();
        int icons = 0;
        File tmp = new File(base, "icons.zip.tmp");
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(tmp)))) {
            for (Future<AppEntry> future : futures) {
                AppEntry entry;
                try {
                    entry = future.get();
                } catch (Throwable ignored) {
                    continue;
                }
                if (entry == null) {
                    continue;
                }
                out.put(entry.meta);
                if (entry.icon != null) {
                    putStored(zip, entry.pkg + ".png", entry.icon);
                    icons++;
                }
            }
            byte[] json = out.toString().getBytes("UTF-8");
            ZipEntry jsonEntry = new ZipEntry(APPS_ENTRY);
            zip.putNextEntry(jsonEntry);
            zip.write(json);
            zip.closeEntry();
        } catch (Throwable t) {
            tmp.delete();
            return;
        }

        File target = new File(base, "icons.zip");
        if (!tmp.renameTo(target)) {
            tmp.delete();
            return;
        }

        try (FileWriter w = new FileWriter(new File(base, "ready"))) {
            w.write("{\"apps\":" + out.length() + ",\"icons\":" + icons
                    + ",\"zip\":1,\"time\":" + (System.currentTimeMillis() / 1000L) + "}");
        } catch (Throwable ignored) {
        }
    }

    private static AppEntry readApp(PackageManager pm, ApplicationInfo ai, int size) {
        try {
            JSONObject o = new JSONObject();
            o.put("package", ai.packageName);
            o.put("label", String.valueOf(pm.getApplicationLabel(ai)));
            o.put("system", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            o.put("enabled", ai.enabled);
            try {
                o.put("version", String.valueOf(pm.getPackageInfo(ai.packageName, 0).versionName));
            } catch (Throwable ignored) {
                o.put("version", "");
            }

            long sizeBytes = 0L;
            try {
                if (ai.sourceDir != null) {
                    sizeBytes += new File(ai.sourceDir).length();
                }
                if (ai.splitSourceDirs != null) {
                    for (String split : ai.splitSourceDirs) {
                        sizeBytes += new File(split).length();
                    }
                }
            } catch (Throwable ignored) {
            }
            o.put("sizeBytes", sizeBytes);

            byte[] icon = null;
            try {
                Drawable d = pm.getApplicationIcon(ai);
                Bitmap bmp = toBitmap(d, size);
                if (bmp != null) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, buffer);
                    icon = buffer.toByteArray();
                    bmp.recycle();
                }
            } catch (Throwable ignored) {
            }

            return new AppEntry(ai.packageName, o, icon);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** PNG data is already compressed: store it verbatim instead of deflating twice. */
    private static void putStored(ZipOutputStream zip, String name, byte[] data) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(data);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void deleteChildren(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) deleteChildren(f);
            f.delete();
        }
        dir.delete();
    }

    private static Bitmap toBitmap(Drawable d, int iconSize) {
        if (d == null) return null;
        int w;
        int h;
        if (d instanceof AdaptiveIconDrawable) {
            w = iconSize;
            h = iconSize;
        } else {
            w = d.getIntrinsicWidth();
            h = d.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = iconSize;
                h = iconSize;
            }
            float scale = Math.min(1f, (float) iconSize / Math.max(w, h));
            w = Math.max(1, (int) (w * scale));
            h = Math.max(1, (int) (h * scale));
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        return bmp;
    }

    private static final class AppEntry {
        final String pkg;
        final JSONObject meta;
        final byte[] icon;

        AppEntry(String pkg, JSONObject meta, byte[] icon) {
            this.pkg = pkg;
            this.meta = meta;
            this.icon = icon;
        }
    }
}
