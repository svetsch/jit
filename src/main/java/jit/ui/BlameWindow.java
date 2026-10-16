package jit.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** Shows {@code git blame} output: who changed each line last. */
final class BlameWindow {
    private static final Pattern LINE =
            Pattern.compile("^\\^?([0-9a-f]+)\\s+(?:\\S+\\s+)?\\((.*?)\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\d+)\\)\\s?(.*)$");

    private record BlameLine(String hash, String author, String date, int line, String text, boolean first) {
    }

    private final RepositoryView rv;
    private final String path;
    private final Stage stage = new Stage();
    private final ListView<BlameLine> list = new ListView<>();

    BlameWindow(RepositoryView rv, String path) {
        this.rv = rv;
        this.path = path;
        list.getStyleClass().addAll("diff-list", "blame-list");
        list.setPlaceholder(Ui.label("Loading…", "placeholder-text"));
        list.setCellFactory(l -> new BlameCell());
        Scene scene = new Scene(list, 1100, 750);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Blame " + path + " — " + rv.repo().name());
        if (rv.window() instanceof Stage owner) {
            stage.getIcons().setAll(owner.getIcons());
        }
    }

    void show() {
        stage.show();
        rv.read(() -> parse(rv.repo().blame(path)), lines -> {
            list.getItems().setAll(lines);
            list.setPlaceholder(Ui.label("Nothing to show", "placeholder-text"));
        });
    }

    private static List<BlameLine> parse(String out) {
        List<BlameLine> lines = new ArrayList<>();
        String previous = null;
        for (String raw : out.split("\n")) {
            if (raw.isEmpty()) {
                continue;
            }
            String s = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            Matcher m = LINE.matcher(s);
            if (m.matches()) {
                String hash = m.group(1);
                lines.add(new BlameLine(hash, m.group(2).strip(), m.group(3), Integer.parseInt(m.group(4)),
                        m.group(5).replace("\t", "    "), !hash.equals(previous)));
                previous = hash;
            } else {
                lines.add(new BlameLine("", "", "", 0, s, false));
            }
        }
        return lines;
    }

    private final class BlameCell extends ListCell<BlameLine> {
        private final Label hash = Ui.label("", "blame-hash");
        private final Label author = Ui.label("", "blame-author");
        private final Label date = Ui.label("", "blame-date");
        private final Label no = Ui.label("", "line-no");
        private final Label text = Ui.label("", "line-text");
        private final HBox box = new HBox(8, hash, author, date, no, text);

        BlameCell() {
            hash.setMinWidth(70);
            hash.setPrefWidth(70);
            author.setMinWidth(140);
            author.setPrefWidth(140);
            author.setMaxWidth(140);
            date.setMinWidth(80);
            text.setMinWidth(Region.USE_PREF_SIZE);
            box.setAlignment(Pos.CENTER_LEFT);
            setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && getItem() != null && !getItem().hash().isEmpty()) {
                    rv.selectCommit(getItem().hash());
                    if (rv.window() instanceof Stage s) {
                        s.toFront();
                    }
                }
            });
            setOnContextMenuRequested(e -> {
                if (getItem() != null && !getItem().hash().isEmpty()) {
                    String h = getItem().hash();
                    new ContextMenu(Ui.item("Show Commit", Icons.COMMIT, () -> rv.selectCommit(h)),
                            Ui.item("Copy SHA", Icons.COPY, () -> Ui.copy(h)))
                            .show(this, e.getScreenX(), e.getScreenY());
                }
            });
        }

        @Override
        protected void updateItem(BlameLine item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            hash.setText(item.first() ? item.hash().substring(0, Math.min(8, item.hash().length())) : "");
            author.setText(item.first() ? item.author() : "");
            date.setText(item.first() ? item.date() : "");
            no.setText(item.line() > 0 ? Integer.toString(item.line()) : "");
            text.setText(item.text().isEmpty() ? " " : item.text());
            setGraphic(box);
        }
    }
}
