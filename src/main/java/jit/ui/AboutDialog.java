package jit.ui;

import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import jit.BuildInfo;
import jit.git.Git;

/** Help → About: application version, build commit and runtime versions. */
public final class AboutDialog {
    private AboutDialog() {
    }

    public static void show(Window owner, Image icon) {
        Dialog<ButtonType> d = new Dialog<>();
        if (owner != null) {
            d.initOwner(owner);
        }
        d.setTitle("About Jit");
        d.getDialogPane().getStylesheets().setAll(Theme.stylesheets());
        d.getDialogPane().getStyleClass().addAll("jit-dialog", "about-dialog");

        Label title = Ui.label("Jit", "about-title");
        Label subtitle = Ui.label("A fast and friendly Git client", "dim-text");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(5);
        int row = 0;
        grid.addRow(row++, key("Version"), Ui.label(BuildInfo.version(), "form-value"));

        String commit = BuildInfo.commit();
        if (commit != null) {
            Hyperlink link = new Hyperlink(BuildInfo.commitShort() + (BuildInfo.dirty() ? " (modified)" : ""));
            link.getStyleClass().add("mono-link");
            link.setTooltip(new Tooltip(commit + "\nClick to copy"));
            link.setOnAction(e -> Ui.copy(commit));
            grid.addRow(row++, key("Commit"), link);
            if (BuildInfo.commitTime() != null) {
                grid.addRow(row++, key("Commit date"), Ui.label(BuildInfo.commitTime(), "meta-value"));
            }
        } else {
            grid.addRow(row++, key("Commit"), Ui.label("unknown (built outside a git repository)", "dim-text"));
        }
        if (BuildInfo.buildTime() != null) {
            grid.addRow(row++, key("Built"), Ui.label(BuildInfo.buildTime(), "meta-value"));
        }
        grid.addRow(row++, key("Java"), Ui.label(System.getProperty("java.version") + " ("
                + System.getProperty("java.vendor", "") + ")", "meta-value"));
        grid.addRow(row++, key("JavaFX"), Ui.label(System.getProperty("javafx.runtime.version",
                System.getProperty("javafx.version", "?")), "meta-value"));
        grid.addRow(row, key("Git"), Ui.label(gitVersion(), "meta-value"));

        VBox text = new VBox(4, title, subtitle);
        VBox right = new VBox(16, text, grid);
        HBox content = new HBox(20);
        if (icon != null) {
            ImageView iv = new ImageView(icon);
            iv.setFitWidth(72);
            iv.setFitHeight(72);
            content.getChildren().add(iv);
        }
        content.getChildren().add(right);
        content.setAlignment(Pos.TOP_LEFT);
        content.setPadding(new Insets(20, 24, 8, 24));
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        d.showAndWait();
    }

    private static Label key(String text) {
        return Ui.label(text, "meta-key");
    }

    private static String gitVersion() {
        try {
            String v = new Git(null).exec(List.of("--version")).strip();
            return v.startsWith("git version ") ? v.substring("git version ".length()) : v;
        } catch (Exception e) {
            return "not found";
        }
    }
}
