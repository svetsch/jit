package jit.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import jit.git.Commit;
import jit.git.Ref;
import jit.graph.GraphRow;

/** The commit graph table with search, plus commit details below it. */
public final class HistoryView extends VBox {
    static final double ROW_HEIGHT = 26;
    static final double LANE_WIDTH = 16;

    private final RepositoryView rv;
    private final ObservableList<Commit> commits = FXCollections.observableArrayList();
    private final FilteredList<Commit> filtered = new FilteredList<>(commits, c -> true);
    private final TableView<Commit> table = new TableView<>(filtered);
    private final TextField search = new TextField();
    private final Label searchInfo = Ui.label("", "dim-text");
    private final CommitDetailsView details;
    private boolean filtering;
    private final HBox scopeBar = new HBox(8);
    private final Label scopeText = Ui.label("", "scope-text");
    private Runnable onShowAll = () -> { };

    public HistoryView(RepositoryView rv) {
        this.rv = rv;
        this.details = new CommitDetailsView(rv);
        getStyleClass().add("history-view");

        search.setPromptText("Search commits by message, author or SHA  (Ctrl+F)");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((obs, o, n) -> applyFilter());
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                search.clear();
                table.requestFocus();
            } else if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.DOWN) {
                table.requestFocus();
                if (table.getSelectionModel().isEmpty() && !filtered.isEmpty()) {
                    table.getSelectionModel().select(0);
                }
            }
        });
        HBox searchBar = new HBox(8, Icons.icon(Icons.SEARCH, 14), search, searchInfo);
        HBox.setHgrow(search, Priority.ALWAYS);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.getStyleClass().add("search-bar");

        buildTable();

        javafx.scene.control.Button showAll = Ui.smallButton("Show All", Icons.EYE, () -> onShowAll.run());
        scopeText.setMinWidth(0);
        scopeBar.getChildren().addAll(Icons.icon(Icons.FILTER, 14, "accent"), scopeText, Ui.spacer(), showAll);
        scopeBar.setAlignment(Pos.CENTER_LEFT);
        scopeBar.getStyleClass().addAll("state-banner", "info-banner");
        setScope(null);

        SplitPane split = new SplitPane(table, details);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(searchBar, scopeBar, split);
    }

    private void buildTable() {
        table.getStyleClass().add("commit-table");
        table.setFixedCellSize(ROW_HEIGHT);
        table.setPlaceholder(Ui.label("No commits yet", "placeholder-text"));
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        TableColumn<Commit, Commit> desc = new TableColumn<>("Description");
        desc.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        desc.setCellFactory(c -> new DescriptionCell());
        desc.setSortable(false);
        desc.setResizable(false);

        TableColumn<Commit, String> sha = new TableColumn<>("Commit");
        sha.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().shortHash()));
        sha.setPrefWidth(80);
        sha.setSortable(false);
        sha.getStyleClass().add("sha-column");

        TableColumn<Commit, String> author = new TableColumn<>("Author");
        author.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().author));
        author.setPrefWidth(150);
        author.setSortable(false);

        TableColumn<Commit, String> date = new TableColumn<>("Date");
        date.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().isWorkingTree() ? "" : Ui.formatDate(c.getValue().authorTime)));
        date.setPrefWidth(150);
        date.setSortable(false);

        table.getColumns().add(desc);
        table.getColumns().add(sha);
        table.getColumns().add(author);
        table.getColumns().add(date);
        desc.prefWidthProperty().bind(table.widthProperty()
                .subtract(sha.widthProperty()).subtract(author.widthProperty()).subtract(date.widthProperty())
                .subtract(18));

        table.setRowFactory(t -> {
            TableRow<Commit> row = new TableRow<>();
            row.setOnContextMenuRequested(e -> {
                Commit c = row.getItem();
                if (c != null && !c.isWorkingTree()) {
                    ContextMenu menu = rv.commitMenu(c);
                    menu.show(row, e.getScreenX(), e.getScreenY());
                    e.consume();
                }
            });
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && row.getItem() != null
                        && row.getItem().isWorkingTree()) {
                    rv.showLocalChanges();
                }
            });
            return row;
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n == null) {
                return;
            }
            if (n.isWorkingTree()) {
                details.clear();
                rv.showLocalChanges();
            } else {
                details.show(n);
            }
        });
    }

    public void setOnShowAll(Runnable onShowAll) {
        this.onShowAll = onShowAll;
    }

    /** Shows which branches the history is limited to; {@code null} hides the bar. */
    public void setScope(String text) {
        boolean show = text != null;
        scopeText.setText(show ? text : "");
        scopeBar.setVisible(show);
        scopeBar.setManaged(show);
    }

    public void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    public void setCommits(List<Commit> list) {
        Commit selected = table.getSelectionModel().getSelectedItem();
        String selectedHash = selected == null ? null : selected.hash;
        commits.setAll(list);
        if (selectedHash != null && !Commit.WORKING_TREE.equals(selectedHash)) {
            for (int i = 0; i < filtered.size(); i++) {
                if (filtered.get(i).hash.equals(selectedHash)) {
                    table.getSelectionModel().select(i);
                    break;
                }
            }
        }
        table.refresh();
    }

    /** Selects and reveals a commit. Returns false if it is not loaded. */
    public boolean select(String hash) {
        for (int i = 0; i < filtered.size(); i++) {
            Commit c = filtered.get(i);
            if (c.hash.equals(hash) || (hash.length() >= 7 && c.hash.startsWith(hash))) {
                table.getSelectionModel().clearAndSelect(i);
                table.scrollTo(Math.max(0, i - 5));
                table.requestFocus();
                return true;
            }
        }
        return false;
    }

    public Commit selected() {
        return table.getSelectionModel().getSelectedItem();
    }

    public void selectFirstCommit() {
        if (table.getSelectionModel().isEmpty() && !filtered.isEmpty()) {
            int i = filtered.get(0).isWorkingTree() && filtered.size() > 1 ? 1 : 0;
            if (!filtered.get(i).isWorkingTree()) {
                table.getSelectionModel().select(i);
            }
        }
    }

    public void clearWorkingTreeSelection() {
        Commit c = table.getSelectionModel().getSelectedItem();
        if (c != null && c.isWorkingTree()) {
            table.getSelectionModel().clearSelection();
        }
    }

    public void refreshCells() {
        table.refresh();
    }

    private void applyFilter() {
        String q = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        filtering = !q.isEmpty();
        if (!filtering) {
            filtered.setPredicate(c -> true);
            searchInfo.setText("");
        } else {
            filtered.setPredicate(c -> !c.isWorkingTree() && (c.subject.toLowerCase(Locale.ROOT).contains(q)
                    || c.author.toLowerCase(Locale.ROOT).contains(q)
                    || c.authorEmail.toLowerCase(Locale.ROOT).contains(q)
                    || c.hash.startsWith(q)
                    || c.refs.stream().anyMatch(r -> r.name.toLowerCase(Locale.ROOT).contains(q))));
            searchInfo.setText(filtered.size() + " matching");
        }
        table.refresh();
    }

    // ------------------------------------------------------------------ graph & description cell

    private final class DescriptionCell extends TableCell<Commit, Commit> {
        private final Canvas canvas = new Canvas(0, ROW_HEIGHT);
        private final HBox badges = new HBox(4);
        private final Label subject = new Label();
        private final HBox box = new HBox(4, canvas, badges, subject);

        DescriptionCell() {
            getStyleClass().add("graph-cell");
            box.setAlignment(Pos.CENTER_LEFT);
            badges.setAlignment(Pos.CENTER_LEFT);
            badges.setMinWidth(Region.USE_PREF_SIZE);
            subject.setMinWidth(0);
            subject.getStyleClass().add("commit-subject-cell");
            setText(null);
        }

        @Override
        protected void updateItem(Commit c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setGraphic(null);
                return;
            }
            subject.setText(c.subject);
            subject.getStyleClass().remove("working-tree-subject");
            if (c.isWorkingTree()) {
                subject.getStyleClass().add("working-tree-subject");
            }
            badges.getChildren().setAll(buildBadges(c));
            if (filtering || c.graph == null) {
                canvas.setWidth(4);
                canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), ROW_HEIGHT);
            } else {
                canvas.setWidth(c.graph.width * LANE_WIDTH + 4);
                drawGraph(canvas.getGraphicsContext2D(), c, c.graph);
            }
            setGraphic(box);
        }
    }

    private List<Label> buildBadges(Commit c) {
        List<Label> list = new ArrayList<>();
        if (c.refs.isEmpty()) {
            return list;
        }
        String color = c.graph != null ? Theme.laneHex(c.graph.lane) : Theme.laneHex(0);
        List<Ref> consumedRemotes = new ArrayList<>();
        for (Ref r : c.refs) {
            if (r.kind != Ref.Kind.LOCAL) {
                continue;
            }
            Label l = new Label(r.name);
            l.getStyleClass().add("ref-badge");
            Ref twin = null;
            for (Ref o : c.refs) {
                if (o.kind == Ref.Kind.REMOTE && o.name.equals(r.upstream)) {
                    twin = o;
                }
            }
            HBox graphics = new HBox(3);
            graphics.setAlignment(Pos.CENTER_LEFT);
            if (r.head) {
                graphics.getChildren().add(Icons.icon(Icons.CHECK, 11, "badge-icon-head"));
                l.getStyleClass().add("head-badge");
                l.setStyle("-fx-background-color: " + color + "; -fx-border-color: " + color + ";");
            } else {
                l.setStyle("-fx-background-color: " + color + "26; -fx-border-color: " + color + ";");
            }
            if (twin != null) {
                consumedRemotes.add(twin);
                l.setText(r.name);
                graphics.getChildren().add(Icons.icon(Icons.CLOUD, 11, r.head ? "badge-icon-head" : null));
            }
            if (!graphics.getChildren().isEmpty()) {
                l.setGraphic(graphics);
            }
            list.add(l);
        }
        for (Ref r : c.refs) {
            if (r.kind == Ref.Kind.REMOTE && !consumedRemotes.contains(r)) {
                Label l = new Label(r.name, Icons.icon(Icons.CLOUD, 11));
                l.getStyleClass().addAll("ref-badge", "remote-badge");
                l.setStyle("-fx-background-color: " + color + "14; -fx-border-color: " + color + "99;");
                list.add(l);
            }
        }
        for (Ref r : c.refs) {
            if (r.kind == Ref.Kind.TAG) {
                Label l = new Label(r.name, Icons.icon(Icons.TAG, 11));
                l.getStyleClass().addAll("ref-badge", "tag-badge");
                list.add(l);
            }
        }
        for (Label l : list) {
            l.setMinWidth(Region.USE_PREF_SIZE);
        }
        return list;
    }

    private static double laneX(int lane) {
        return 4 + lane * LANE_WIDTH + LANE_WIDTH / 2;
    }

    private void drawGraph(GraphicsContext g, Commit c, GraphRow r) {
        double h = ROW_HEIGHT;
        double mid = h / 2;
        g.clearRect(0, 0, g.getCanvas().getWidth(), h);
        g.setLineWidth(2);
        double x = laneX(r.lane);

        for (int i = 0; i < r.passFrom.length; i++) {
            int from = r.passFrom[i];
            int to = r.passTo[i];
            g.setStroke(Theme.lane(from));
            if (from == to) {
                g.strokeLine(laneX(from), 0, laneX(from), h);
            } else {
                g.beginPath();
                g.moveTo(laneX(from), 0);
                g.bezierCurveTo(laneX(from), mid, laneX(to), mid, laneX(to), h);
                g.stroke();
            }
        }
        for (int j : r.incoming) {
            g.setStroke(Theme.lane(j));
            if (j == r.lane) {
                g.strokeLine(x, 0, x, mid);
            } else {
                g.beginPath();
                g.moveTo(laneX(j), 0);
                g.quadraticCurveTo(laneX(j), mid, x, mid);
                g.stroke();
            }
        }
        for (int p = 0; p < r.outgoing.length; p++) {
            int k = r.outgoing[p];
            g.setStroke(Theme.lane(p == 0 ? r.lane : k));
            if (k == r.lane) {
                g.strokeLine(x, mid, x, h);
            } else {
                g.beginPath();
                g.moveTo(x, mid);
                g.quadraticCurveTo(laneX(k), mid, laneX(k), h);
                g.stroke();
            }
        }

        Color color = Theme.lane(r.lane);
        Color bg = Theme.background();
        boolean head = c.refs.stream().anyMatch(ref -> ref.head) || c.hash.equals(rv.headHash());
        if (c.isWorkingTree()) {
            double rad = 5;
            g.setFill(bg);
            g.fillOval(x - rad, mid - rad, rad * 2, rad * 2);
            g.setStroke(color);
            g.setLineWidth(2);
            g.setLineDashes(2, 2);
            g.strokeOval(x - rad, mid - rad, rad * 2, rad * 2);
            g.setLineDashes((double[]) null);
        } else if (head) {
            double rad = 6;
            g.setFill(bg);
            g.fillOval(x - rad - 1, mid - rad - 1, rad * 2 + 2, rad * 2 + 2);
            g.setStroke(color);
            g.setLineWidth(2.5);
            g.strokeOval(x - rad + 1, mid - rad + 1, rad * 2 - 2, rad * 2 - 2);
            g.setFill(color);
            g.fillOval(x - 2.5, mid - 2.5, 5, 5);
        } else {
            double rad = c.isMerge() ? 3.5 : 4.5;
            g.setFill(color);
            g.fillOval(x - rad, mid - rad, rad * 2, rad * 2);
        }
    }
}
