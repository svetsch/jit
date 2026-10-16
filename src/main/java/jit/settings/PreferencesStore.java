package jit.settings;

import java.util.Set;
import java.util.TreeSet;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Settings kept with {@link java.util.prefs.Preferences}: the Windows registry
 * (key {@code HKEY_CURRENT_USER\Software\JavaSoft\Prefs\jit-git-client}), {@code ~/.java} on Linux, a plist on macOS.
 */
final class PreferencesStore implements SettingsStore {

    /** Node used since the first version; {@code -Djit.prefs=<node>} selects another one (tests, snapshots). */
    static final String NODE = System.getProperty("jit.prefs", "jit-git-client");

    private final Preferences prefs;

    PreferencesStore() {
        this(Preferences.userRoot().node(NODE));
    }

    PreferencesStore(Preferences prefs) {
        this.prefs = prefs;
    }

    @Override
    public SettingsLocation location() {
        return SettingsLocation.SYSTEM;
    }

    @Override
    public String description() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "Registry: HKEY_CURRENT_USER\\Software\\JavaSoft\\Prefs" + prefs.absolutePath().replace('/', '\\');
        }
        return "Java preferences node " + prefs.absolutePath();
    }

    @Override
    public String get(String key, String defaultValue) {
        return prefs.get(key, defaultValue);
    }

    @Override
    public void put(String key, String value) {
        if (value == null) {
            prefs.remove(key);
        } else {
            prefs.put(key, value);
        }
    }

    @Override
    public void remove(String key) {
        prefs.remove(key);
    }

    @Override
    public Set<String> keys() {
        try {
            return new TreeSet<>(Set.of(prefs.keys()));
        } catch (BackingStoreException e) {
            return Set.of();
        }
    }

    @Override
    public void clear() {
        try {
            prefs.clear();
            prefs.flush();
        } catch (BackingStoreException ignored) {
            // nothing more we can do
        }
    }

    @Override
    public void flush() {
        try {
            prefs.flush();
        } catch (BackingStoreException ignored) {
            // the JDK retries on its own schedule
        }
    }
}
