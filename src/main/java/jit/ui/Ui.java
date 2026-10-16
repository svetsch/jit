package jit.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Text;

/** Small UI helpers shared by the views. */
public final class Ui {
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

    private static javafx.application.HostServices hostServices;

    private Ui() {
    }

    public static void setHostServices(javafx.application.HostServices services) {
        hostServices = services;
    }

    /** Opens a web page in the default browser. */
    public static void openUrl(String url) {
        if (hostServices != null) {
            hostServices.showDocument(url);
        }
    }

    public static String formatDate(long epochSeconds) {
        return DATE.format(Instant.ofEpochSecond(epochSeconds));
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static Label label(String text, String... styleClasses) {
        Label l = new Label(text);
        l.getStyleClass().addAll(styleClasses);
        return l;
    }

    public static MenuItem item(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> action.run());
        return mi;
    }

    public static MenuItem item(String text, String icon, Runnable action) {
        MenuItem mi = item(text, action);
        if (icon != null) {
            mi.setGraphic(Icons.icon(icon, 14));
        }
        return mi;
    }

    /** Big toolbar button with the icon above the text, like Fork's toolbar. */
    public static Button toolButton(String text, String icon, String tooltip, Runnable action) {
        Button b = new Button(text, Icons.icon(icon, 20));
        b.setContentDisplay(ContentDisplay.TOP);
        b.getStyleClass().add("tool-button");
        b.setFocusTraversable(false);
        if (tooltip != null) {
            b.setTooltip(new Tooltip(tooltip));
        }
        b.setOnAction(e -> action.run());
        return b;
    }

    public static Button smallButton(String text, String icon, Runnable action) {
        Button b = new Button(text);
        if (icon != null) {
            b.setGraphic(Icons.icon(icon, 12));
        }
        b.getStyleClass().add("small-button");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    public static void copy(String text) {
        ClipboardContent c = new ClipboardContent();
        c.putString(text);
        Clipboard.getSystemClipboard().setContent(c);
    }

    /** Round avatar with the author's initials. */
    public static Node avatar(String name, String email, double size) {
        Circle circle = new Circle(size / 2, Theme.avatarColor(email == null || email.isEmpty() ? name : email));
        Text initials = new Text(initials(name));
        initials.setFill(Color.WHITE);
        initials.setStyle("-fx-font-weight: bold; -fx-font-size: " + Math.round(size * 0.4) + "px;");
        StackPane p = new StackPane(circle, initials);
        p.setMinSize(size, size);
        p.setMaxSize(size, size);
        return p;
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        String[] parts = name.trim().split("[\\s._-]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty() && sb.length() < 2) {
                sb.appendCodePoint(p.codePointAt(0));
            }
        }
        return sb.toString().toUpperCase();
    }

    /** Small coloured square with the status letter of a changed file. */
    public static Label statusBadge(char status) {
        String letter;
        String cls;
        switch (status) {
            case 'A', '?' -> {
                letter = "+";
                cls = "status-added";
            }
            case 'D' -> {
                letter = "−";
                cls = "status-deleted";
            }
            case 'R', 'C' -> {
                letter = "R";
                cls = "status-renamed";
            }
            case 'U' -> {
                letter = "!";
                cls = "status-conflict";
            }
            case 'T' -> {
                letter = "T";
                cls = "status-modified";
            }
            default -> {
                letter = "M";
                cls = "status-modified";
            }
        }
        Label l = new Label(letter);
        l.getStyleClass().addAll("status-badge", cls);
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.setAlignment(Pos.CENTER);
        return l;
    }

    /** File list row: status badge, file name, dimmed directory. */
    public static HBox fileRow(char status, String path, String oldPath) {
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        String dir = slash < 0 ? "" : path.substring(0, slash);
        Label nameLabel = label(name, "file-name");
        nameLabel.setMinWidth(Region.USE_PREF_SIZE);
        Label dirLabel = label(dir, "file-dir");
        HBox box = new HBox(6, statusBadge(status), nameLabel, dirLabel);
        if (oldPath != null) {
            Label from = label("← " + oldPath, "file-dir");
            box.getChildren().add(from);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static void openInExplorer(Path path) {
        try {
            if (WINDOWS) {
                if (Files.isDirectory(path)) {
                    new ProcessBuilder("explorer.exe", path.toString()).start();
                } else {
                    new ProcessBuilder("explorer.exe", "/select," + path).start();
                }
            } else if (MAC) {
                new ProcessBuilder("open", "-R", path.toString()).start();
            } else {
                Path dir = Files.isDirectory(path) ? path : path.getParent();
                new ProcessBuilder("xdg-open", dir.toString()).start();
            }
        } catch (IOException ignored) {
            // nothing sensible to do
        }
    }

    public static void openFile(Path path) {
        try {
            if (WINDOWS) {
                new ProcessBuilder("cmd", "/c", "start", "", path.toString()).start();
            } else if (MAC) {
                new ProcessBuilder("open", path.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", path.toString()).start();
            }
        } catch (IOException ignored) {
            // nothing sensible to do
        }
    }

    public static void openTerminal(Path dir) {
        try {
            Terminal.open(dir);
        } catch (IOException e) {
            Dialogs.error(null, "Cannot open terminal", e.getMessage());
        }
    }
}
