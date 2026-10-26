package com.dshpocket;

import android.content.Context;
import android.content.SharedPreferences;

/** Small typed wrapper around the app's SharedPreferences. */
public final class Prefs {
    private static final String FILE = "dsh_pocket";

    public static final String KEY_AUTO_START = "auto_start";
    public static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    public static final String KEY_PORT = "port";                 // 0 = let dsh pick (recommended)
    public static final String KEY_LAST_URL = "last_url";
    public static final String KEY_ASKED_STORAGE = "asked_storage";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean isAutoStart() {
        return sp.getBoolean(KEY_AUTO_START, true);
    }

    public void setAutoStart(boolean value) {
        sp.edit().putBoolean(KEY_AUTO_START, value).apply();
    }

    public boolean isKeepScreenOn() {
        return sp.getBoolean(KEY_KEEP_SCREEN_ON, true);
    }

    public void setKeepScreenOn(boolean value) {
        sp.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply();
    }

    public int getPort() {
        return sp.getInt(KEY_PORT, 0);
    }

    public void setPort(int port) {
        sp.edit().putInt(KEY_PORT, port).apply();
    }

    public String getLastUrl() {
        return sp.getString(KEY_LAST_URL, null);
    }

    public void setLastUrl(String url) {
        sp.edit().putString(KEY_LAST_URL, url).apply();
    }

    public boolean hasAskedStorage() {
        return sp.getBoolean(KEY_ASKED_STORAGE, false);
    }

    public void setAskedStorage(boolean value) {
        sp.edit().putBoolean(KEY_ASKED_STORAGE, value).apply();
    }
}
