package jit.settings;

import java.util.Set;

/** Key/value storage of the user preferences: Java preferences (Windows registry) or a properties file. */
public interface SettingsStore {

    /** Where the settings are kept. */
    SettingsLocation location();

    /** Human readable location (registry key or file path). */
    String description();

    String get(String key, String defaultValue);

    /** Stores a value; {@code null} removes the key. */
    void put(String key, String value);

    void remove(String key);

    Set<String> keys();

    /** Removes every key (and the backing file, if any). */
    void clear();

    /** Persists pending changes; file stores write through, so this is mostly a no-op. */
    void flush();

    /** Copies every key of this store into {@code target}. */
    default void copyTo(SettingsStore target) {
        for (String key : keys()) {
            target.put(key, get(key, null));
        }
        target.flush();
    }
}
