package jit.ui;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import jit.git.Repo;

/** Lists the commits that touched a file and shows the file's diff for each of them. */
final class FileHistoryWindow {
    private final RepositoryView rv;
    private final String path;
    private final Stage stage = new Stage();
    private final ListView<Repo.FileRevision> list = new ListView<>();
    private final DiffView diff = new DiffView();

    FileHistoryWindow(RepositoryView rv, String path) {
        this.rv = rv;
        this.path = path;
        list.getStyleClass().add("file-list");
        list.setPlaceholder(Ui.label("Loading…", "placeholder-text"));
        list.setCellFactory(l -> new RevisionCell());
        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null) {
                rv.read(() -> rv.repo().commitDiff(n.commit(), n.change()), text -> {
                    if (n == list.getSelectionModel().getSelectedItem()) {
                        diff.setDiff(n.change().path(), text, DiffView.Mode.READ_ONLY);
                        diff.setExternalDiff(() -> rv.externalDiff(n.commit(), n.change()));
                    }
                });
            }
        });
        SplitPane split = new SplitPane(list, diff);
        split.setDividerPositions(0.33);
        Scene scene = new Scene(split, 1200, 750);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("History of " + path + " — " + rv.repo().name());
        if (rv.window() instanceof Stage owner) {
            stage.getIcons().setAll(owner.getIcons());
        }
    }

    void show() {
        stage.show();
        rv.read(() -> rv.repo().fileHistory(path, 2000), revisions -> {
            list.getItems().setAll(revisions);
            list.setPlaceholder(Ui.label("No history for this file", "placeholder-text"));
            if (!revisions.isEmpty()) {
                list.getSelectionModel().select(0);
            }
        });
    }

    private final class RevisionCell extends ListCell<Repo.FileRevision> {
        RevisionCell() {
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(Repo.FileRevision item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Label subject = Ui.label(item.commit().subject, "file-name");
            Label meta = Ui.label(item.commit().author + " · " + Ui.formatDate(item.commit().authorTime) + " · "
                    + item.commit().shortHash(), "dim-text");
            VBox text = new VBox(2, subject, meta);
            HBox box = new HBox(8, Ui.avatar(item.commit().author, item.commit().authorEmail, 26), text);
            box.setAlignment(Pos.CENTER_LEFT);
            setGraphic(box);
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && getItem() != null) {
                    rv.selectCommit(getItem().commit().hash);
                    if (rv.window() instanceof Stage s) {
                        s.toFront();
                    }
                }
            });
        }
    }
}
