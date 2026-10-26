package com.dshpocket;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/** In-memory ring buffer of the runtime log, mirrored to filesDir/logs/dsh.log. */
public final class DshLog {
    private DshLog() {}

    private static final Object LOCK = new Object();
    private static final int MAX_LINES = 600;
    private static final java.util.ArrayDeque<String> LINES = new java.util.ArrayDeque<>();
    private static File logFile;

    /** Point the log at a file and preload its tail (call from the service/activity). */
    public static void init(File file) {
        synchronized (LOCK) {
            if (file == null) { return; }
            if (logFile != null && logFile.equals(file) && !LINES.isEmpty()) { return; }
            logFile = file;
            LINES.clear();
            if (file.isFile()) {
                BufferedReader reader = null;
                try {
                    reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        addUnlocked(line);
                    }
                } catch (Exception ignored) {
                } finally {
                    closeQuietly(reader);
                }
            }
        }
    }

    public static File getFile() {
        synchronized (LOCK) { return logFile; }
    }

    /** Append one line (timestamp added) to logcat, the ring buffer and the log file. */
    public static void append(String line) {
        String stamped = "[" + now() + "] " + line;
        synchronized (LOCK) {
            addUnlocked(stamped);
            if (logFile != null) {
                try {
                    File parent = logFile.getParentFile();
                    if (parent != null) { parent.mkdirs(); }
                    OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8");
                    try {
                        writer.write(stamped);
                        writer.write("\n");
                    } finally {
                        writer.close();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        android.util.Log.i(DshService.TAG, line);
    }

    /** Append a line without timestamp (continuation output). */
    public static void appendRaw(String line) {
        synchronized (LOCK) {
            addUnlocked(line);
            if (logFile != null) {
                try {
                    OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile, true), "UTF-8");
                    try {
                        writer.write(line);
                        writer.write("\n");
                    } finally {
                        writer.close();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        android.util.Log.i(DshService.TAG, line);
    }

    private static void addUnlocked(String line) {
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) { LINES.removeFirst(); }
    }

    /** Last {@code maxLines} lines as one string. */
    public static String tail(int maxLines) {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            int skip = Math.max(0, LINES.size() - maxLines);
            int i = 0;
            for (String line : LINES) {
                if (i++ >= skip) { sb.append(line).append('\n'); }
            }
            return sb.toString();
        }
    }

    private static String now() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) { try { c.close(); } catch (Exception ignored) { } }
    }
}
