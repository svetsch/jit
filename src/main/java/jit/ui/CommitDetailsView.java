package jit.ui;

import java.util.List;

import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import jit.git.Commit;
import jit.git.FileChange;

/** Bottom pane of the history: commit metadata, changed files and the selected file's diff. */
public final class CommitDetailsView extends SplitPane {
    private final RepositoryView rv;
    private final VBox info = new VBox(10);
    private final FileTree<FileChange> files = new FileTree<>(FileChange::path, FileChange::status,
            FileChange::oldPath, "No changed files");
    private final BooleanProperty treeMode = FileTree.treeModeSetting("commitFiles.tree");
    private final Label filesHeader = Ui.label("Changes", "pane-header-text");
    private final DiffView diff = new DiffView();
    private Commit commit;
    private long token;

    public CommitDetailsView(RepositoryView rv) {
        this.rv = rv;
        getStyleClass().add("commit-details");
        info.getStyleClass().add("commit-info");
        info.setPadding(new Insets(12, 14, 12, 14));
        ScrollPane infoScroll = new ScrollPane(info);
        infoScroll.setFitToWidth(true);
        infoScroll.getStyleClass().add("plain-scroll");

        files.setTreeMode(treeMode.get());
        treeMode.addListener((obs, o, n) -> files.setTreeMode(n));
        files.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<Object>) c -> loadDiff(files.singleEntry()));
        files.setOnContextMenuRequested(e -> {
            Commit c = commit;
            FileChange f = files.singleEntry();
            ContextMenu menu = f != null && c != null
                    ? rv.fileMenu(f.path(), c.hash, () -> rv.externalDiff(c, f)) : new ContextMenu();
            menu.getItems().addAll(new SeparatorMenuItem(),
                    Ui.item(treeMode.get() ? "Show as Flat List" : "Show as Folder Tree",
                            treeMode.get() ? Icons.LIST : Icons.FOLDER, () -> treeMode.set(!treeMode.get())));
            menu.show(files, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        // double-click on a file opens it in the external diff tool (on a folder it just expands)
        files.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            FileChange f = files.singleEntry();
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && f != null && commit != null
                    && e.getTarget() instanceof javafx.scene.Node n && insideFilledCell(n)) {
                rv.externalDiff(commit, f);
            }
        });
        filesHeader.setMinWidth(0);
        HBox header = new HBox(8, filesHeader, Ui.spacer(), FileTree.layoutToggle(treeMode));
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("pane-header");
        StackPane body = new StackPane(files, files.placeholder());
        VBox filesBox = new VBox(header, body);
        VBox.setVgrow(body, Priority.ALWAYS);

        SplitPane left = new SplitPane(infoScroll, filesBox);
        left.setOrientation(Orientation.VERTICAL);
        left.setDividerPositions(0.42);
        getItems().addAll(left, diff);
        setDividerPositions(0.34);
        SplitPane.setResizableWithParent(left, false);
        clear();
    }

    public Commit commit() {
        return commit;
    }

    public void clear() {
        commit = null;
        token++;
        info.getChildren().setAll(Ui.label("No commit selected", "placeholder-text"));
        files.setEntries(List.of());
        filesHeader.setText("Changes");
        diff.clear("Select a file to see its changes");
    }

    public void show(Commit c) {
        if (c == null || c.isWorkingTree()) {
            clear();
            return;
        }
        if (commit != null && commit.hash.equals(c.hash)) {
            return;
        }
        commit = c;
        long t = ++token;
        buildInfo(c, c.subject);
        // the previous files stay until the new ones arrive, so the selected path can carry over
        filesHeader.setText("Changes");
        rv.read(() -> rv.repo().commitMessage(c.hash), msg -> {
            if (t == token) {
                buildInfo(c, msg);
            }
        });
        rv.read(() -> rv.repo().commitChanges(c), changes -> {
            if (t == token) {
                files.setEntries(changes);
                filesHeader.setText(changes.size() + (changes.size() == 1 ? " changed file" : " changed files"));
                if (files.singleEntry() == null) {
                    files.selectFirstFile();
                }
                loadDiff(files.singleEntry());
            }
        });
    }

    private void buildInfo(Commit c, String message) {
        info.getChildren().clear();

        Label author = Ui.label(c.author, "author-name");
        Label email = Ui.label("<" + c.authorEmail + ">", "dim-text");
        Label date = Ui.label(Ui.formatDate(c.authorTime), "dim-text");
        VBox who = new VBox(2, new HBox(6, author, email), date);
        HBox top = new HBox(10, Ui.avatar(c.author, c.authorEmail, 38), who);
        top.setAlignment(Pos.CENTER_LEFT);

        String subject = message;
        String body = "";
        int nl = message.indexOf('\n');
        if (nl >= 0) {
            subject = message.substring(0, nl).strip();
            body = message.substring(nl + 1).strip();
        }
        info.getChildren().addAll(top,
                IssueLinks.flow(subject, rv.hosted(), "commit-subject", Ui::openUrl));
        if (!body.isEmpty()) {
            info.getChildren().add(IssueLinks.flow(body, rv.hosted(), "commit-body", Ui::openUrl));
        }
        String fullMessage = message;
        info.setOnContextMenuRequested(e -> {
            new ContextMenu(Ui.item("Copy message", Icons.COPY, () -> Ui.copy(fullMessage)),
                    Ui.item("Copy SHA", Icons.COPY, () -> Ui.copy(c.hash)))
                    .show(info, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        GridPane meta = new GridPane();
        meta.setHgap(10);
        meta.setVgap(4);
        meta.getStyleClass().add("commit-meta");
        javafx.scene.layout.ColumnConstraints keyColumn = new javafx.scene.layout.ColumnConstraints();
        keyColumn.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        meta.getColumnConstraints().add(keyColumn);
        int row = 0;
        Hyperlink sha = new Hyperlink(c.hash);
        sha.getStyleClass().add("mono-link");
        sha.setOnAction(e -> {
            Ui.copy(c.hash);
            rv.status("Copied " + c.shortHash() + " to the clipboard");
        });
        meta.addRow(row++, Ui.label("SHA", "meta-key"), sha);
        if (c.parents.length > 0) {
            FlowPane parents = new FlowPane(6, 0);
            for (String p : c.parents) {
                Hyperlink link = new Hyperlink(p.substring(0, Math.min(10, p.length())));
                link.getStyleClass().add("mono-link");
                link.setOnAction(e -> rv.selectCommit(p));
                parents.getChildren().add(link);
            }
            meta.addRow(row++, Ui.label(c.parents.length > 1 ? "Parents" : "Parent", "meta-key"), parents);
        }
        if (!c.committer.equals(c.author) || !c.committerEmail.equals(c.authorEmail)) {
            meta.addRow(row++, Ui.label("Committer", "meta-key"),
                    Ui.label(c.committer + " <" + c.committerEmail + ">", "meta-value"));
        }
        if (c.commitTime != c.authorTime) {
            meta.addRow(row, Ui.label("Committed", "meta-key"), Ui.label(Ui.formatDate(c.commitTime), "meta-value"));
        }
        info.getChildren().add(meta);
    }

    private void loadDiff(FileChange f) {
        Commit c = commit;
        if (f == null || c == null) {
            diff.clear("Select a file to see its changes");
            return;
        }
        long t = token;
        rv.read(() -> rv.repo().commitDiff(c, f), text -> {
            if (t == token && f.equals(files.singleEntry())) {
                diff.setDiff(f.path(), text, DiffView.Mode.READ_ONLY);
                diff.setExternalDiff(() -> rv.externalDiff(c, f));
            }
        });
    }

    private static boolean insideFilledCell(javafx.scene.Node n) {
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) {
            if (p instanceof javafx.scene.control.TreeCell<?> cell) {
                return !cell.isEmpty();
            }
        }
        return false;
    }
}
