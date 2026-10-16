package jit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Version and source revision of this build, read from the Maven-filtered {@code build.properties}. */
public final class BuildInfo {
    private static final Properties PROPS = new Properties();
    private static final boolean DIRTY;

    static {
        load("/jit/build.properties");
        // written by "git log" during the build; holds an error message when built outside a git repository
        load("/jit/git.properties");
        String status = "";
        try (InputStream in = BuildInfo.class.getResourceAsStream("/jit/git-status.txt")) {
            if (in != null) {
                status = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // treat as clean
        }
        DIRTY = !status.isBlank() && !status.startsWith("fatal");
    }

    private static void load(String resource) {
        try (InputStream in = BuildInfo.class.getResourceAsStream(resource)) {
            if (in != null) {
                PROPS.load(in);
            }
        } catch (IOException ignored) {
            // values report as unknown
        }
    }

    private BuildInfo() {
    }

    private static String get(String key) {
        String v = PROPS.getProperty(key, "").strip();
        return v.isEmpty() || v.startsWith("${") ? null : v;
    }

    public static String version() {
        String v = get("version");
        return v == null ? "development" : v;
    }

    /** Full commit id, or {@code null} when the build was made outside a git working tree. */
    public static String commit() {
        String c = get("commit");
        return c != null && c.matches("[0-9a-f]{7,64}") ? c : null;
    }

    public static String commitShort() {
        String s = get("commitShort");
        if (s == null && commit() != null) {
            s = commit().substring(0, Math.min(7, commit().length()));
        }
        return s;
    }

    public static String commitTime() {
        return get("commitTime");
    }

    public static String buildTime() {
        return get("buildTime");
    }

    /** True when the build contained uncommitted changes. */
    public static boolean dirty() {
        return DIRTY;
    }
}
