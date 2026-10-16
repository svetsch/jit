package jit.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import jit.settings.SettingsLocation;
import jit.settings.SettingsStore;

/**
 * Persistent user settings (recent and open repositories, theme, window size, tools …), kept in the
 * {@link SettingsLocation} chosen by the user: the registry or a properties file.
 */
public final class Settings {
    private static final int MAX_RECENT = 20;
    private static SettingsStore store = SettingsLocation.resolve();

    private Settings() {
    }

    public static SettingsStore store() {
        return store;
    }

    /** Moves every setting to another location; it is used from then on and found again at start-up. */
    public static void moveTo(SettingsLocation target) {
        store = SettingsLocation.moveTo(store, target);
    }

    public static List<String> recentRepositories() {
        return list("recent");
    }

    public static void addRecent(String path) {
        List<String> recent = recentRepositories();
        recent.removeIf(p -> p.equalsIgnoreCase(path));
        recent.add(0, path);
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
        putList("recent", recent);
    }

    public static void removeRecent(String path) {
        List<String> recent = recentRepositories();
        recent.removeIf(p -> p.equalsIgnoreCase(path));
        putList("recent", recent);
    }

    public static List<String> openTabs() {
        return list("tabs");
    }

    public static void setOpenTabs(List<String> tabs) {
        putList("tabs", tabs);
    }

    public static boolean darkTheme() {
        return Boolean.parseBoolean(store.get("dark", "false"));
    }

    public static void setDarkTheme(boolean dark) {
        store.put("dark", Boolean.toString(dark));
    }

    public static String get(String key, String def) {
        return store.get(key, def);
    }

    public static void put(String key, String value) {
        if (store.location() == SettingsLocation.SYSTEM && value != null
                && value.length() > Preferences.MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("The setting '" + key + "' is too long for the registry ("
                    + value.length() + " characters). Store the settings in a file instead (Preferences → Storage).");
        }
        store.put(key, value);
    }

    public static double getDouble(String key, double def) {
        try {
            return Double.parseDouble(store.get(key, Double.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static void putDouble(String key, double value) {
        store.put(key, Double.toString(value));
    }

    private static List<String> list(String key) {
        List<String> list = new ArrayList<>();
        for (String s : store.get(key, "").split("\n")) {
            if (!s.isBlank()) {
                list.add(s);
            }
        }
        return list;
    }

    private static void putList(String key, List<String> values) {
        String joined = String.join("\n", values);
        // registry values are limited to 8 KB: drop the oldest entries
        while (store.location() == SettingsLocation.SYSTEM && joined.length() > Preferences.MAX_VALUE_LENGTH
                && joined.contains("\n")) {
            joined = joined.substring(0, joined.lastIndexOf('\n'));
        }
        store.put(key, joined);
    }
}
