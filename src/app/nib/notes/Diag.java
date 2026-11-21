package app.nib.notes;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A small log file of what matters when ink misbehaves (display mode changes, surfaces, saves that failed), kept in
 * the app's files as diag.log / diag.old.log (256 KB each). Android's own log only reaches back minutes on a busy
 * tablet; this one survives until the next few thousand events, so a report like "the pen stopped at 13:50" can be
 * traced afterwards (adb: run-as / su, files/diag.log). Nothing leaves the device.
 */
final class Diag {
    private Diag() {}

    private static final long MAX = 256 * 1024;
    private static File file;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    static synchronized void init(Context c) {
        if (file == null) file = new File(c.getApplicationContext().getFilesDir(), "diag.log");
    }

    /** Logs to Android's log too (tag nib). Any thread. */
    static void log(String msg) {
        Log.i(Gl.TAG, msg);
        write(msg);
    }

    static void warn(String msg) {
        Log.w(Gl.TAG, msg);
        write("WARN " + msg);
    }

    private static synchronized void write(String msg) {
        if (file == null) return;
        try {
            if (file.length() > MAX) {
                File old = new File(file.getParentFile(), "diag.old.log");
                old.delete();
                file.renameTo(old);
            }
            String line = TIME.format(new Date()) + " [" + Thread.currentThread().getName() + "] " + msg + "\n";
            try (FileOutputStream o = new FileOutputStream(file, true)) {
                o.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }
}
