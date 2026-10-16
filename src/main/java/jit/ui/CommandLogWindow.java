package jit.ui;

import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import jit.git.CommandLog;

/** Shows every git command Jit has run, with its output, so nothing happens behind the user's back. */
public final class CommandLogWindow {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ENGLISH).withZone(ZoneId.systemDefault());
    private static CommandLogWindow instance;

    private final Stage stage = new Stage();
    private final ObservableList<CommandLog.Entry> entries = FXCollections.observableArrayList();
    private final FilteredList<CommandLog.Entry> filtered = new FilteredList<>(entries);
    private final TableView<CommandLog.Entry> table = new TableView<>(filtered);
    private final TextArea details = new TextArea();
    private final TextField search = new TextField();
    private final CheckBox onlyRepo = new CheckBox("Only this repository");
    private final CheckBox hideQueries = new CheckBox("Hide read-only queries");
    private final CheckBox follow = new CheckBox("Follow");
    private final Label count = Ui.label("", "dim-text");
    private Path repoRoot;

    /** Opens (or focuses) the history window, filtered to the given repository when not null. */
    public static void show(Path repoRoot, Image icon) {
        if (instance == null) {
            instance = new CommandLogWindow();
        }
        instance.repoRoot = repoRoot;
        instance.onlyRepo.setDisable(repoRoot == null);
        instance.onlyRepo.setSelected(repoRoot != null);
        instance.onlyRepo.setText(repoRoot == null ? "Only this repository"
                : "Only " + (repoRoot.getFileName() == null ? repoRoot : repoRoot.getFileName()));
        if (icon != null && instance.stage.getIcons().isEmpty()) {
            instance.stage.getIcons().add(icon);
        }
        Theme.apply(instance.stage.getScene());
        instance.updateFilter();
        instance.stage.show();
        instance.stage.toFront();
        instance.scrollToEnd();
    }

    /** Re-applies the theme if the window exists. */
    public static void refreshTheme() {
        if (instance != null) {
            Theme.apply(instance.stage.getScene());
        }
    }

    private CommandLogWindow() {
        entries.setAll(CommandLog.entries());
        CommandLog.addListener(e -> Platform.runLater(() -> add(e)));

        search.setPromptText("Filter commands…");
        search.getStyleClass().add("search-field");
        search.textProperty().addListener((obs, o, n) -> updateFilter());
        onlyRepo.selectedProperty().addListener((obs, o, n) -> updateFilter());
        hideQueries.selectedProperty().addListener((obs, o, n) -> updateFilter());
        follow.setSelected(true);

        Button copy = Ui.smallButton("Copy Command", Icons.COPY, this::copySelected);
        Button terminal = Ui.smallButton("Open Terminal", Icons.TERMINAL, this::openTerminal);
        Button clear = Ui.smallButton("Clear", Icons.CLOSE, () -> {
            CommandLog.clear();
            entries.clear();
            details.clear();
            updateCount();
        });
        HBox bar = new HBox(10, Icons.icon(Icons.SEARCH, 14), search, onlyRepo, hideQueries, follow, Ui.spacer(),
                count, copy, terminal, clear);
        HBox.setHgrow(search, Priority.SOMETIMES);
        search.setPrefWidth(260);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("search-bar");

        buildTable();
        details.setEditable(false);
        details.setWrapText(false);
        details.getStyleClass().addAll("mono", "command-details");

        SplitPane split = new SplitPane(table, details);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.62);

        BorderPane root = new BorderPane(split);
        root.setTop(bar);
        Scene scene = new Scene(root, 1150, 680);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Git Command History — Jit");
        updateCount();
    }

    private void buildTable() {
        table.getStyleClass().addAll("commit-table", "command-table");
        table.setFixedCellSize(24);
        table.setPlaceholder(Ui.label("No git commands yet", "placeholder-text"));
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        TableColumn<CommandLog.Entry, String> time = new TableColumn<>("Time");
        time.setCellValueFactory(c -> new ReadOnlyStringWrapper(TIME.format(c.getValue().time())));
        time.setPrefWidth(100);
        time.getStyleClass().add("sha-column");

        TableColumn<CommandLog.Entry, String> repo = new TableColumn<>("Repository");
        repo.setCellValueFactory(c -> new ReadOnlyStringWrapper(dirName(c.getValue().dir())));
        repo.setPrefWidth(120);

        TableColumn<CommandLog.Entry, CommandLog.Entry> command = new TableColumn<>("Command");
        command.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        command.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(CommandLog.Entry e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().removeAll("command-query", "command-write");
                if (empty || e == null) {
                    setText(null);
                    return;
                }
                setText(e.commandLine());
                getStyleClass().add(e.isQuery() ? "command-query" : "command-write");
            }
        });

        TableColumn<CommandLog.Entry, CommandLog.Entry> exit = new TableColumn<>("Exit");
        exit.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        exit.setPrefWidth(60);
        exit.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(CommandLog.Entry e, boolean empty) {
                super.updateItem(e, empty);
                getStyleClass().removeAll("exit-ok", "exit-failed");
                if (empty || e == null) {
                    setText(null);
                    return;
                }
                setText((e.ok() ? "✓ " : "✗ ") + e.exitCode());
                getStyleClass().add(e.ok() ? "exit-ok" : "exit-failed");
            }
        });

        TableColumn<CommandLog.Entry, String> duration = new TableColumn<>("Duration");
        duration.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().durationMs() + " ms"));
        duration.setPrefWidth(80);

        table.getColumns().add(time);
        table.getColumns().add(repo);
        table.getColumns().add(command);
        table.getColumns().add(exit);
        table.getColumns().add(duration);
        command.prefWidthProperty().bind(table.widthProperty().subtract(time.widthProperty())
                .subtract(repo.widthProperty()).subtract(exit.widthProperty()).subtract(duration.widthProperty())
                .subtract(18));
        for (TableColumn<?, ?> col : table.getColumns()) {
            col.setSortable(false);
        }
        table.setRowFactory(t -> {
            TableRow<CommandLog.Entry> row = new TableRow<>();
            row.setOnContextMenuRequested(ev -> {
                if (row.getItem() != null) {
                    CommandLog.Entry e = row.getItem();
                    new ContextMenu(
                            Ui.item("Copy Command", Icons.COPY, () -> Ui.copy(e.commandLine())),
                            Ui.item("Copy Details", Icons.COPY, () -> Ui.copy(describe(e))),
                            Ui.item("Open Terminal Here", Icons.TERMINAL, () -> openTerminal(e.dir())))
                            .show(row, ev.getScreenX(), ev.getScreenY());
                }
            });
            return row;
        });
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) ->
                details.setText(n == null ? "" : describe(n)));
    }

    private void add(CommandLog.Entry e) {
        entries.add(e);
        if (entries.size() > 5000) {
            entries.remove(0, entries.size() - 5000);
        }
        updateCount();
        if (follow.isSelected() && stage.isShowing()) {
            scrollToEnd();
        }
    }

    private void scrollToEnd() {
        if (!filtered.isEmpty()) {
            table.scrollTo(filtered.size() - 1);
        }
    }

    private void updateFilter() {
        String q = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
        File root = repoRoot == null ? null : repoRoot.toFile();
        boolean repoOnly = onlyRepo.isSelected() && root != null;
        boolean noQueries = hideQueries.isSelected();
        filtered.setPredicate(e -> (!repoOnly || root.equals(e.dir()))
                && (!noQueries || !e.isQuery())
                && (q.isEmpty() || e.commandLine().toLowerCase(Locale.ROOT).contains(q)));
        updateCount();
    }

    private void updateCount() {
        count.setText(filtered.size() + " of " + entries.size() + " commands");
    }

    private void copySelected() {
        CommandLog.Entry e = table.getSelectionModel().getSelectedItem();
        if (e != null) {
            Ui.copy(e.commandLine());
        }
    }

    private void openTerminal() {
        CommandLog.Entry e = table.getSelectionModel().getSelectedItem();
        openTerminal(e != null ? e.dir() : repoRoot == null ? null : repoRoot.toFile());
    }

    private void openTerminal(File dir) {
        Ui.openTerminal(dir != null ? dir.toPath() : Path.of(System.getProperty("user.home")));
    }

    private static String dirName(File dir) {
        return dir == null ? "" : dir.getName();
    }

    private static String describe(CommandLog.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (e.dir() != null) {
            sb.append("$ cd ").append(e.dir()).append('\n');
        }
        sb.append("$ ").append(e.commandLine()).append("\n\n");
        sb.append("exit code: ").append(e.exitCode())
                .append("    duration: ").append(e.durationMs()).append(" ms")
                .append("    output: ").append(e.stdoutBytes()).append(" bytes")
                .append("    at ").append(TIME.format(e.time())).append('\n');
        if (e.stdin() != null && !e.stdin().isEmpty()) {
            sb.append("\n--- standard input ---\n").append(e.stdin().replace("\0", "\n"));
            if (!e.stdin().endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (e.stderr() != null && !e.stderr().isBlank()) {
            sb.append("\n--- messages (stderr) ---\n").append(e.stderr().strip()).append('\n');
        }
        sb.append("\nNote: Jit also passes -c core.quotepath=false -c color.ui=false -c i18n.logOutputEncoding=UTF-8")
                .append("\nand sets GIT_TERMINAL_PROMPT=0, GIT_OPTIONAL_LOCKS=0, GIT_EDITOR=true.\n");
        return sb.toString();
    }
}
