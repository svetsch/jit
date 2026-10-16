package jit.settings;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the preferences (including the open and recent repositories) are stored. At start-up the first existing
 * location wins, in this order: the {@code jit.settings} system property, the portable file next to the application,
 * the file in the user folder, and finally the system preferences (Windows registry).
 */
public enum SettingsLocation {

    /** {@link java.util.prefs.Preferences}: the registry on Windows. The default. */
    SYSTEM("System preferences (Windows registry)"),
    /** {@code ~/.jit/settings.properties}. */
    USER_FILE("Settings file in the user folder"),
    /** {@code jit.properties} next to the application, so that a portable copy carries its settings. */
    PORTABLE_FILE("Portable settings file next to the application"),
    /** File given with {@code -Djit.settings=<path>}; not selectable in the UI. */
    CUSTOM_FILE("Settings file given on the command line (-Djit.settings)");

    public static final String PROPERTY = "jit.settings";
    static final String FILE_NAME = "settings.properties";
    static final String PORTABLE_NAME = "jit.properties";

    private final String displayName;

    SettingsLocation(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Backing file of a file location, or {@code null} for {@link #SYSTEM} or when not available. */
    public Path file() {
        return switch (this) {
            case SYSTEM -> null;
            case USER_FILE -> Path.of(System.getProperty("user.home"), ".jit", FILE_NAME);
            case PORTABLE_FILE -> {
                Path dir = applicationDirectory();
                yield dir == null ? null : dir.resolve(PORTABLE_NAME);
            }
            case CUSTOM_FILE -> {
                String path = System.getProperty(PROPERTY);
                yield path == null || path.isBlank() ? null : Path.of(path).toAbsolutePath();
            }
        };
    }

    /** Whether the location can be used on this installation. */
    public boolean isAvailable() {
        if (this == SYSTEM) {
            return true;
        }
        Path file = file();
        if (file == null) {
            return false;
        }
        if (this != PORTABLE_FILE) {
            return true;
        }
        Path dir = file.getParent();
        return Files.isRegularFile(file) ? Files.isWritable(file) : Files.isDirectory(dir) && Files.isWritable(dir);
    }

    /** Opens the store of this location (reading its file, if any). */
    public SettingsStore open() {
        return this == SYSTEM ? new PreferencesStore() : new FileStore(this, file());
    }

    /** Opens the store in use: the first location that exists, see the class comment. */
    public static SettingsStore resolve() {
        if (CUSTOM_FILE.file() != null) {
            return CUSTOM_FILE.open();
        }
        for (SettingsLocation location : new SettingsLocation[]{PORTABLE_FILE, USER_FILE}) {
            Path file = location.file();
            if (file != null && Files.isRegularFile(file)) {
                return location.open();
            }
        }
        return SYSTEM.open();
    }

    /**
     * Moves every setting from {@code current} to {@code target} and removes them from the old location, so that the
     * start-up lookup finds the new location.
     */
    public static SettingsStore moveTo(SettingsStore current, SettingsLocation target) {
        if (current.location() == target) {
            return current;
        }
        if (!target.isAvailable()) {
            throw new IllegalStateException(target.displayName() + " is not available for this installation.");
        }
        SettingsStore next = target.open();
        next.clear();
        current.copyTo(next);
        current.clear();
        return next;
    }

    /**
     * Folder of the installed application: the folder of the jpackage launcher, else the folder of the jar.
     * {@code null} when running from classes (IDE, {@code mvn javafx:run}).
     */
    static Path applicationDirectory() {
        String launcher = System.getProperty("jpackage.app-path");
        if (launcher != null && !launcher.isBlank()) {
            return Path.of(launcher).toAbsolutePath().getParent();
        }
        try {
            var source = SettingsLocation.class.getProtectionDomain().getCodeSource();
            if (source != null) {
                Path location = Path.of(source.getLocation().toURI());
                if (Files.isRegularFile(location) && location.getFileName().toString().endsWith(".jar")) {
                    return location.getParent();
                }
            }
        } catch (URISyntaxException | SecurityException | IllegalArgumentException ignored) {
            // not a local jar
        }
        return null;
    }
}
