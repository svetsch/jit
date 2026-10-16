package jit.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Repository manager page shown in new tabs: open / clone / init and recent repositories. */
public final class WelcomeView extends BorderPane {
    private final ListView<String> recent = new ListView<>();
    private final Consumer<Path> open;

    public WelcomeView(Consumer<Path> open, Runnable openDialog, Runnable cloneDialog, Runnable initDialog) {
        this.open = open;
        getStyleClass().add("welcome-view");

        Label title = Ui.label("Jit", "welcome-title");
        Label subtitle = Ui.label("A fast and friendly Git client", "welcome-subtitle");
        VBox head = new VBox(2, title, subtitle);

        HBox actions = new HBox(12,
                action("Open Repository", "Open a local repository", Icons.FOLDER, openDialog),
                action("Clone Repository", "Clone a remote repository", Icons.CLONE, cloneDialog),
                action("New Repository", "Initialize a new repository", Icons.NEW_REPO, initDialog));

        Label recentTitle = Ui.label("Recent Repositories", "section-title");
        recent.getStyleClass().add("recent-list");
        recent.setPlaceholder(Ui.label("Repositories you open will appear here", "placeholder-text"));
        recent.setCellFactory(l -> new RecentCell());
        recent.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && recent.getSelectionModel().getSelectedItem() != null) {
                open.accept(Path.of(recent.getSelectionModel().getSelectedItem()));
            }
        });
        VBox.setVgrow(recent, Priority.ALWAYS);

        VBox column = new VBox(24, head, actions, new VBox(10, recentTitle, recent));
        VBox.setVgrow(column.getChildren().get(2), Priority.ALWAYS);
        VBox.setVgrow(recent, Priority.ALWAYS);
        column.setMaxWidth(760);
        column.setPadding(new Insets(40, 32, 32, 32));
        column.setAlignment(Pos.TOP_LEFT);
        HBox center = new HBox(column);
        center.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(column, Priority.ALWAYS);
        setCenter(center);
        reload();
    }

    public void reload() {
        recent.getItems().setAll(Settings.recentRepositories());
    }

    private static Button action(String title, String description, String icon, Runnable action) {
        Label t = Ui.label(title, "action-title");
        Label d = Ui.label(description, "action-description");
        d.setWrapText(true);
        Button b = new Button(null, new VBox(4, Icons.icon(icon, 26, "accent"), t, d));
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.getStyleClass().add("welcome-action");
        b.setPrefWidth(230);
        b.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        b.setOnAction(e -> action.run());
        return b;
    }

    private final class RecentCell extends ListCell<String> {
        RecentCell() {
            setPrefWidth(0);
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && getItem() != null) {
                    open.accept(Path.of(getItem()));
                }
            });
            setOnContextMenuRequested(e -> {
                if (getItem() == null) {
                    return;
                }
                String p = getItem();
                new ContextMenu(
                        Ui.item("Open", Icons.FOLDER, () -> open.accept(Path.of(p))),
                        Ui.item("Show in Explorer", Icons.FOLDER, () -> Ui.openInExplorer(Path.of(p))),
                        Ui.item("Remove from List", Icons.CLOSE, () -> {
                            Settings.removeRecent(p);
                            reload();
                        })).show(this, e.getScreenX(), e.getScreenY());
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Path p = Path.of(item);
            String name = p.getFileName() == null ? item : p.getFileName().toString();
            boolean exists = Files.isDirectory(p);
            Label n = Ui.label(name, "recent-name");
            Label path = Ui.label(item + (exists ? "" : "  (missing)"), "dim-text");
            HBox box = new HBox(10, Icons.icon(Icons.FOLDER, 18), new VBox(1, n, path));
            box.setAlignment(Pos.CENTER_LEFT);
            box.setOpacity(exists ? 1 : 0.55);
            setGraphic(box);
        }
    }
}
