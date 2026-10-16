package jit.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import jit.git.GitConfig;
import jit.git.GitException;

/**
 * Editor for git configuration of one scope: identity and common options as a form, plus every entry
 * of the scope in an editable table. Changes are written by {@link #save()}.
 */
final class GitConfigPane extends VBox {
    private static final String UNSET = "(not set)";

    private final GitConfig config;
    private final Map<String, String> inherited;
    private final Map<String, String> original = new LinkedHashMap<>();
    /** Working copy of all entries, edited by the form and the table. */
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, TextField> textFields = new LinkedHashMap<>();
    private final Map<String, ComboBox<String>> choiceFields = new LinkedHashMap<>();
    private final TableView<Map.Entry<String, String>> table = new TableView<>();
    private boolean syncing;

    /**
     * @param inherited values from the wider scope, shown as hints (may be empty)
     */
    GitConfigPane(GitConfig config, Map<String, String> inherited) throws GitException {
        this.config = config;
        this.inherited = inherited;
        original.putAll(config.list());
        values.putAll(original);
        setSpacing(12);
        setPadding(new Insets(16, 20, 12, 20));

        boolean global = config.scope() == GitConfig.Scope.GLOBAL;
        Label intro = Ui.label(global
                ? "Global settings apply to all repositories of your user account (~/.gitconfig)."
                : "Repository settings override your global settings for this repository only. "
                        + "Leave a field empty to use the global value.", "form-note");
        intro.setWrapText(true);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(8);
        ColumnConstraints c1 = new ColumnConstraints();
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c1, c2);
        int row = 0;
        form.add(Ui.label("Identity", "section-title"), 0, row++, 2, 1);
        row = text(form, row, "Name", "user.name", "Your Name");
        row = text(form, row, "Email", "user.email", "you@example.com");
        form.add(Ui.label("Options", "section-title"), 0, row++, 2, 1);
        if (global) {
            row = text(form, row, "Default branch", "init.defaultBranch", "master");
        }
        row = choice(form, row, "Line endings (core.autocrlf)", "core.autocrlf", "true", "input", "false");
        row = choice(form, row, "Pull strategy (pull.rebase)", "pull.rebase", "false", "true", "merges");
        row = choice(form, row, "Prune on fetch (fetch.prune)", "fetch.prune", "true", "false");
        row = choice(form, row, "Auto set upstream on push", "push.autoSetupRemote", "true", "false");
        row = choice(form, row, "Sign commits (commit.gpgsign)", "commit.gpgsign", "true", "false");
        choice(form, row, "Ignore file mode (core.filemode)", "core.fileMode", "true", "false");

        Label all = Ui.label("All " + (global ? "global" : "repository") + " settings", "section-title");
        buildTable();
        HBox tableButtons = new HBox(6,
                Ui.smallButton("Add…", Icons.PLUS, this::addEntry),
                Ui.smallButton("Edit…", Icons.EDIT, this::editEntry),
                Ui.smallButton("Remove", Icons.MINUS, this::removeEntry));
        tableButtons.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(table, Priority.ALWAYS);
        getChildren().addAll(intro, form, all, table, tableButtons);
        refreshTable();
    }

    private int text(GridPane form, int row, String label, String key, String prompt) {
        TextField f = new TextField(values.getOrDefault(key, ""));
        String hint = inherited.get(key);
        f.setPromptText(hint != null ? hint + "  (global)" : prompt);
        f.textProperty().addListener((obs, o, n) -> {
            if (!syncing) {
                put(key, n.strip().isEmpty() ? null : n.strip());
            }
        });
        textFields.put(key, f);
        form.addRow(row, Ui.label(label, "form-label"), f);
        return row + 1;
    }

    private int choice(GridPane form, int row, String label, String key, String... options) {
        ComboBox<String> box = new ComboBox<>();
        String hint = inherited.get(key);
        box.getItems().add(hint != null ? UNSET + " — global: " + hint : UNSET);
        box.getItems().addAll(options);
        selectChoice(box, values.get(key));
        box.valueProperty().addListener((obs, o, n) -> {
            if (!syncing && n != null) {
                put(key, n.startsWith(UNSET) ? null : n);
            }
        });
        choiceFields.put(key, box);
        form.addRow(row, Ui.label(label, "form-label"), box);
        return row + 1;
    }

    private static void selectChoice(ComboBox<String> box, String value) {
        if (value == null) {
            box.getSelectionModel().select(0);
        } else {
            if (!box.getItems().contains(value)) {
                box.getItems().add(value);
            }
            box.getSelectionModel().select(value);
        }
    }

    private void put(String key, String value) {
        String existing = findKey(key);
        if (value == null) {
            values.remove(existing);
        } else {
            values.put(existing, value);
        }
        refreshTable();
    }

    /** git keys are case-insensitive in section and name; reuse the spelling already present. */
    private String findKey(String key) {
        for (String k : values.keySet()) {
            if (k.equalsIgnoreCase(key)) {
                return k;
            }
        }
        return key;
    }

    private void syncForm() {
        syncing = true;
        try {
            textFields.forEach((key, f) -> f.setText(values.getOrDefault(findKey(key), "")));
            choiceFields.forEach((key, box) -> selectChoice(box, values.get(findKey(key))));
        } finally {
            syncing = false;
        }
    }

    private void buildTable() {
        TableColumn<Map.Entry<String, String>, String> key = new TableColumn<>("Key");
        key.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getKey()));
        key.setPrefWidth(240);
        TableColumn<Map.Entry<String, String>, String> value = new TableColumn<>("Value");
        value.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().getValue()));
        value.prefWidthProperty().bind(table.widthProperty().subtract(key.widthProperty()).subtract(18));
        table.getColumns().add(key);
        table.getColumns().add(value);
        table.getStyleClass().addAll("commit-table", "config-table");
        table.setPrefHeight(200);
        table.setFixedCellSize(24);
        table.setPlaceholder(Ui.label("No settings in this scope", "placeholder-text"));
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editEntry();
            }
        });
    }

    private void refreshTable() {
        table.getItems().setAll(new ArrayList<>(values.entrySet()).stream()
                .map(e -> Map.entry(e.getKey(), e.getValue())).toList());
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void addEntry() {
        Dialogs.Form f = new Dialogs.Form();
        TextField key = f.text("Key", "");
        key.setPromptText("section.name, e.g. core.editor");
        TextField value = f.text("Value", "");
        if (f.show(window(), "Add Setting", "Add a git setting", "Add",
                key.textProperty().isNotEmpty().and(javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> key.getText().contains("."), key.textProperty())))) {
            put(key.getText().strip(), value.getText());
            syncForm();
        }
    }

    private void editEntry() {
        Map.Entry<String, String> e = table.getSelectionModel().getSelectedItem();
        if (e == null) {
            return;
        }
        Dialogs.input(window(), "Edit " + e.getKey(), "Value", e.getValue(), "Save").ifPresent(v -> {
            put(e.getKey(), v);
            syncForm();
        });
    }

    private void removeEntry() {
        Map.Entry<String, String> e = table.getSelectionModel().getSelectedItem();
        if (e != null) {
            put(e.getKey(), null);
            syncForm();
        }
    }

    /** Writes every changed entry. */
    void save() throws GitException {
        List<String> removed = new ArrayList<>();
        for (String k : original.keySet()) {
            if (!values.containsKey(k)) {
                removed.add(k);
            }
        }
        for (String k : removed) {
            config.unset(k);
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!Objects.equals(original.get(e.getKey()), e.getValue())) {
                config.set(e.getKey(), e.getValue());
            }
        }
        original.clear();
        original.putAll(values);
    }
}
