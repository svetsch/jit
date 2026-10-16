package jit.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.paint.Color;

/** Light / dark theme handling and the shared colour palette. */
public final class Theme {
    private static final String[] LANES = {
        "#3b82f6", "#f59e0b", "#10b981", "#a855f7", "#ef4444",
        "#06b6d4", "#ec4899", "#84cc16", "#f97316", "#6366f1"
    };
    private static final Color[] LANE_COLORS = new Color[LANES.length];

    static {
        for (int i = 0; i < LANES.length; i++) {
            LANE_COLORS[i] = Color.web(LANES[i]);
        }
    }

    private static boolean dark = Settings.darkTheme();

    private Theme() {
    }

    public static boolean isDark() {
        return dark;
    }

    public static void setDark(boolean value) {
        dark = value;
        Settings.setDarkTheme(value);
    }

    public static List<String> stylesheets() {
        String base = Theme.class.getResource("/jit/css/base.css").toExternalForm();
        String theme = Theme.class.getResource(dark ? "/jit/css/dark.css" : "/jit/css/light.css").toExternalForm();
        String os = System.getProperty("os.name", "").toLowerCase();
        String fontsName = os.contains("win") ? "fonts-win.css" : os.contains("mac") ? "fonts-mac.css" : "fonts-linux.css";
        String fonts = Theme.class.getResource("/jit/css/" + fontsName).toExternalForm();
        return List.of(base, fonts, theme);
    }

    public static void apply(Scene scene) {
        scene.getStylesheets().setAll(stylesheets());
    }

    public static Color lane(int i) {
        return LANE_COLORS[Math.floorMod(i, LANE_COLORS.length)];
    }

    public static String laneHex(int i) {
        return LANES[Math.floorMod(i, LANES.length)];
    }

    /** Background colour of the commit table, used to ring commit dots. */
    public static Color background() {
        return dark ? Color.web("#1f2227") : Color.WHITE;
    }

    public static Color avatarColor(String key) {
        return lane(Math.abs(key.toLowerCase().hashCode()));
    }
}
