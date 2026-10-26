package com.dshpocket;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide runtime state shared between DshService, MainActivity and WebActivity.
 * (The app is a single process; AndroidX LocalBroadcastManager is not available without AndroidX.)
 */
public final class DshState {
    public enum Status { IDLE, INSTALLING, STARTING, READY, ERROR, STOPPED }

    public interface Listener { void onDshStateChanged(); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile Status status = Status.IDLE;
    private static volatile String url = null;
    private static volatile String message = "Not started";
    private static volatile int progress = -1;      // 0..100 while extracting, -1 = n/a
    private static volatile long updatedAt = 0L;

    private DshState() {}

    public static Status getStatus() { return status; }
    public static String getUrl() { return url; }
    public static String getMessage() { return message; }
    public static int getProgress() { return progress; }
    public static long getUpdatedAt() { return updatedAt; }
    public static boolean isReady() { return status == Status.READY && url != null; }
    public static boolean isBusy() { return status == Status.INSTALLING || status == Status.STARTING; }

    /** Publish a new state; notifies listeners on the main thread. */
    public static void set(Status newStatus, String newMessage, String newUrl, int newProgress) {
        status = newStatus;
        message = newMessage != null ? newMessage : "";
        if (newUrl != null) {
            url = newUrl;
        } else if (newStatus != Status.READY) {
            url = null;
        }
        progress = newProgress;
        updatedAt = System.currentTimeMillis();
        MAIN.post(new Runnable() {
            @Override public void run() {
                for (Listener listener : LISTENERS) {
                    try {
                        listener.onDshStateChanged();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    public static void addListener(Listener listener) {
        if (listener == null) { return; }
        LISTENERS.addIfAbsent(listener);
        MAIN.post(new Runnable() {
            @Override public void run() {
                try { listener.onDshStateChanged(); } catch (Throwable ignored) { }
            }
        });
    }

    public static void removeListener(Listener listener) {
        if (listener != null) { LISTENERS.remove(listener); }
    }
}
