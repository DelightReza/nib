package app.nib.notes;

import android.app.UiModeManager;
import android.content.Context;

/** App theme: follow the system, or force Clean (light) / Graphite (dark). Android remembers it per app. */
final class Theme {
    private Theme() {}

    static int get(Context c) { return c.getSharedPreferences("nib", Context.MODE_PRIVATE).getInt("theme", 0); }

    /** Re-applies the saved choice (Android keeps it per app ID; a renamed or restored app starts on System). */
    static void restore(Context c) {
        int i = get(c);
        if (i != 0) c.getSystemService(UiModeManager.class).setApplicationNightMode(i == 1 ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_YES);
    }

    static void set(Context c, int i) {
        c.getSharedPreferences("nib", Context.MODE_PRIVATE).edit().putInt("theme", i).apply();
        c.getSystemService(UiModeManager.class).setApplicationNightMode(
                i == 1 ? UiModeManager.MODE_NIGHT_NO : i == 2 ? UiModeManager.MODE_NIGHT_YES : UiModeManager.MODE_NIGHT_AUTO);
    }
}
