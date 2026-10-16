package jit.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.prefs.Preferences;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {
    @TempDir
    Path dir;

    @Test
    void fileStoreRoundTripsSpecialCharacters() {
        Path file = dir.resolve("s.properties");
        FileStore store = new FileStore(SettingsLocation.USER_FILE, file);
        store.put("tabs", "C:\\repos\\a\nC:\\repos\\b é");
        store.put(" key=with:specials#", " value ");
        FileStore again = new FileStore(SettingsLocation.USER_FILE, file);
        assertEquals("C:\\repos\\a\nC:\\repos\\b é", again.get("tabs", null));
        assertEquals(" value ", again.get(" key=with:specials#", null));
        again.put("tabs", null);
        assertNull(new FileStore(SettingsLocation.USER_FILE, file).get("tabs", null));
    }

    @Test
    void movingCopiesEverythingAndClearsTheSource() throws Exception {
        Preferences node = Preferences.userRoot().node("jit-test-" + System.nanoTime());
        try {
            PreferencesStore prefs = new PreferencesStore(node);
            prefs.put("recent", "C:\\x");
            prefs.put("dark", "true");
            FileStore file = new FileStore(SettingsLocation.USER_FILE, dir.resolve("moved.properties"));
            prefs.copyTo(file);
            prefs.clear();
            assertEquals("C:\\x", file.get("recent", null));
            assertEquals("true", file.get("dark", null));
            assertTrue(prefs.keys().isEmpty());
            file.clear();
            assertFalse(Files.exists(dir.resolve("moved.properties")));
        } finally {
            node.removeNode();
        }
    }
}
