package jit.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/** Line icons drawn on a 24x24 grid; colours come from the {@code .icon} style class. */
public final class Icons {
    public static final String FETCH = "M8 17l4 4 4-4M12 12v9M20.88 18.09A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.29";
    public static final String PULL = "M12 3v11M7 9l5 5 5-5M3 15v4a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-4";
    public static final String PUSH = "M12 15V3M7 8l5-5 5 5M3 15v4a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-4";
    public static final String STASH = "M21 8v13H3V8M1 3h22v5H1zM10 12h4";
    public static final String POP = "M21 8v13H3V8M1 3h22v5H1zM12 18v-6M9 15l3-3 3 3";
    public static final String BRANCH = "M6 3v12M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 9a9 9 0 0 1-9 9";
    public static final String MERGE = "M18 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21V9a9 9 0 0 0 9 9";
    public static final String TAG = "M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82zM7 7h.01";
    public static final String COMMIT = "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM1.05 12H7M17.01 12h5.95";
    public static final String TERMINAL = "M4 17l6-6-6-6M12 19h8";
    public static final String FOLDER = "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z";
    public static final String CLOUD = "M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z";
    public static final String REFRESH = "M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15";
    public static final String SEARCH = "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.35-4.35";
    public static final String FILE = "M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM13 2v7h7";
    public static final String EDIT = "M12 20h9M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z";
    public static final String LIST = "M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01";
    public static final String PLUS = "M12 5v14M5 12h14";
    public static final String MINUS = "M5 12h14";
    public static final String CHECK = "M20 6L9 17l-5-5";
    public static final String CLOSE = "M18 6L6 18M6 6l12 12";
    public static final String UNDO = "M3 7v6h6M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13";
    public static final String COPY = "M20 9h-9a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h9a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2zM5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1";
    public static final String SERVER = "M2 2h20v8H2zM2 14h20v8H2zM6 6h.01M6 18h.01";
    public static final String LAYERS = "M12 2L2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5";
    public static final String NEW_REPO = "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM12 8v8M8 12h8";
    public static final String CLONE = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3";
    public static final String HISTORY = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2";
    public static final String WARNING = "M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0zM12 9v4M12 17h.01";
    public static final String ARROW_UP = "M12 19V5M5 12l7-7 7 7";
    public static final String ARROW_DOWN = "M12 5v14M19 12l-7 7-7-7";

    private Icons() {
    }

    public static Node icon(String path, double size) {
        return icon(path, size, null);
    }

    public static Node icon(String path, double size, String styleClass) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add("icon");
        if (styleClass != null) {
            p.getStyleClass().add(styleClass);
        }
        p.setStrokeWidth(size <= 14 ? 2.2 : 1.9);
        p.setStrokeLineCap(StrokeLineCap.ROUND);
        p.setStrokeLineJoin(StrokeLineJoin.ROUND);
        double s = size / 24.0;
        p.setScaleX(s);
        p.setScaleY(s);
        StackPane box = new StackPane(new Group(p));
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.getStyleClass().add("icon-box");
        return box;
    }
}
