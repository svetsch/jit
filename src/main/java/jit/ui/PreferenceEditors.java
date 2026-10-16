package jit.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;

/** List + form editors for the "Custom Commands" and "Issue Links" preference tabs. */
final class PreferenceEditors {
    private PreferenceEditors() {
    }

    /** Editor for {@link CustomCommands}; call {@link CommandsEditor#save()} to persist. */
    static final class CommandsEditor extends VBox {
        private final ObservableList<CustomCommands.Command> items =
                FXCollections.observableArrayList(CustomCommands.all());
        private final ListView<CustomCommands.Command> list = new ListView<>(items);
        private final TextField name = new TextField();
        private final ComboBox<CustomCommands.Target> target = new ComboBox<>();
        private final TextField command = new TextField();
        private final CheckBox terminal = new CheckBox("Run in a terminal window");
        private final CheckBox confirm = new CheckBox("Ask for confirmation before running");
        private final CheckBox output = new CheckBox("Show output when finished");
        private boolean loading;

        CommandsEditor() {
            super(10);
            setPadding(new Insets(16, 20, 12, 20));
            list.getStyleClass().add("file-list");
            list.setPrefWidth(200);
            list.setPlaceholder(Ui.label("No custom commands", "placeholder-text"));
            target.getItems().addAll(CustomCommands.Target.values());
            name.setPromptText("Name shown in menus");
            command.setPromptText("git log --oneline -5 $SHA   or   code \"$FILE_ABS\"");

            Dialogs.Form form = new Dialogs.Form();
            form.add("Name", name);
            form.add("Applies to", target);
            form.add("Command", command);
            form.add(null, terminal);
            form.add(null, confirm);
            form.add(null, output);
            VBox formBox = new VBox(form.node());
            HBox.setHgrow(formBox, Priority.ALWAYS);
            formBox.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());

            HBox buttons = new HBox(6,
                    Ui.smallButton("Add", Icons.PLUS, this::add),
                    Ui.smallButton("Remove", Icons.MINUS, this::remove),
                    Ui.smallButton("Up", Icons.ARROW_UP, () -> move(-1)),
                    Ui.smallButton("Down", Icons.ARROW_DOWN, () -> move(1)));
            buttons.setAlignment(Pos.CENTER_LEFT);
            VBox left = new VBox(6, list, buttons);
            VBox.setVgrow(list, Priority.ALWAYS);

            HBox main = new HBox(12, left, formBox);
            VBox.setVgrow(main, Priority.ALWAYS);
            Label help = Ui.label(CustomCommands.HELP, "form-note");
            help.setWrapText(true);
            getChildren().addAll(Ui.label("Custom commands appear in the Repository menu, the toolbar "
                    + "and the context menus of commits, branches and files.", "form-note"), main, help);

            list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> load(n));
            name.textProperty().addListener((obs, o, n) -> store());
            target.valueProperty().addListener((obs, o, n) -> store());
            command.textProperty().addListener((obs, o, n) -> store());
            terminal.selectedProperty().addListener((obs, o, n) -> store());
            confirm.selectedProperty().addListener((obs, o, n) -> store());
            output.selectedProperty().addListener((obs, o, n) -> store());
            if (!items.isEmpty()) {
                list.getSelectionModel().select(0);
            }
        }

        private void load(CustomCommands.Command c) {
            loading = true;
            try {
                name.setText(c == null ? "" : c.name());
                target.setValue(c == null ? CustomCommands.Target.REPOSITORY : c.target());
                command.setText(c == null ? "" : c.command());
                terminal.setSelected(c != null && c.inTerminal());
                confirm.setSelected(c != null && c.confirm());
                output.setSelected(c != null && c.showOutput());
            } finally {
                loading = false;
            }
        }

        private void store() {
            int i = list.getSelectionModel().getSelectedIndex();
            if (loading || i < 0) {
                return;
            }
            loading = true;
            try {
                items.set(i, new CustomCommands.Command(name.getText(), target.getValue(), command.getText(),
                        terminal.isSelected(), confirm.isSelected(), output.isSelected()));
                list.getSelectionModel().select(i);
            } finally {
                loading = false;
            }
        }

        private void add() {
            items.add(new CustomCommands.Command("New command", CustomCommands.Target.REPOSITORY, "git status",
                    false, false, true));
            list.getSelectionModel().select(items.size() - 1);
            name.requestFocus();
            name.selectAll();
        }

        private void remove() {
            int i = list.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                items.remove(i);
            }
        }

        private void move(int delta) {
            int i = list.getSelectionModel().getSelectedIndex();
            int j = i + delta;
            if (i < 0 || j < 0 || j >= items.size()) {
                return;
            }
            loading = true;
            try {
                CustomCommands.Command c = items.remove(i);
                items.add(j, c);
                list.getSelectionModel().select(j);
            } finally {
                loading = false;
            }
        }

        void save() {
            List<CustomCommands.Command> valid = new ArrayList<>();
            for (CustomCommands.Command c : items) {
                if (!c.name().isBlank() && !c.command().isBlank()) {
                    valid.add(c);
                }
            }
            CustomCommands.save(valid);
        }
    }

    /** Editor for {@link IssueLinks} rules with a live preview. */
    static final class IssueLinksEditor extends VBox {
        private final ObservableList<IssueLinks.Rule> items = FXCollections.observableArrayList(IssueLinks.rules());
        private final ListView<IssueLinks.Rule> list = new ListView<>(items);
        private final TextField pattern = new TextField();
        private final TextField url = new TextField();
        private final TextField sample = new TextField("PROJ-123: Fix login, see #42 and https://example.com");
        private final Label error = Ui.label("", "danger-text");
        private final VBox preview = new VBox();
        private boolean loading;

        IssueLinksEditor() {
            super(10);
            setPadding(new Insets(16, 20, 12, 20));
            list.getStyleClass().add("file-list");
            list.setPrefWidth(200);
            list.setPlaceholder(Ui.label("No rules", "placeholder-text"));
            list.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
                @Override
                protected void updateItem(IssueLinks.Rule r, boolean empty) {
                    super.updateItem(r, empty);
                    setText(empty || r == null ? null : r.pattern());
                }
            });
            pattern.setPromptText("[A-Z][A-Z0-9]+-\\d+");
            url.setPromptText("https://jira.example.com/browse/$0");

            Dialogs.Form form = new Dialogs.Form();
            form.add("Pattern (regex)", pattern);
            form.add("Link URL", url);
            form.add(null, error);
            form.add("Try it", sample);
            form.add(null, preview);
            VBox formBox = new VBox(form.node());
            HBox.setHgrow(formBox, Priority.ALWAYS);

            HBox buttons = new HBox(6,
                    Ui.smallButton("Add", Icons.PLUS, this::add),
                    Ui.smallButton("JIRA", Icons.PLUS, () -> {
                        items.add(new IssueLinks.Rule("\\b[A-Z][A-Z0-9]+-\\d+\\b",
                                "https://your-company.atlassian.net/browse/$0"));
                        list.getSelectionModel().select(items.size() - 1);
                    }),
                    Ui.smallButton("Remove", Icons.MINUS, () -> {
                        int i = list.getSelectionModel().getSelectedIndex();
                        if (i >= 0) {
                            items.remove(i);
                        }
                    }));
            VBox left = new VBox(6, list, buttons);
            VBox.setVgrow(list, Priority.ALWAYS);
            HBox main = new HBox(12, left, formBox);
            VBox.setVgrow(main, Priority.ALWAYS);
            Label help = Ui.label("Matches of the pattern in commit messages become links. In the URL, $0 is the "
                    + "whole match and $1…$9 are groups. #123 links to the issue tracker of GitHub, GitLab and "
                    + "Bitbucket remotes automatically, and web addresses are always clickable.", "form-note");
            help.setWrapText(true);
            getChildren().addAll(main, help);

            list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
                loading = true;
                pattern.setText(n == null ? "" : n.pattern());
                url.setText(n == null ? "" : n.url());
                loading = false;
                updatePreview();
            });
            pattern.textProperty().addListener((obs, o, n) -> store());
            url.textProperty().addListener((obs, o, n) -> store());
            sample.textProperty().addListener((obs, o, n) -> updatePreview());
            formBox.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
            if (!items.isEmpty()) {
                list.getSelectionModel().select(0);
            }
            updatePreview();
        }

        private void add() {
            items.add(new IssueLinks.Rule("", ""));
            list.getSelectionModel().select(items.size() - 1);
            pattern.requestFocus();
        }

        private void store() {
            int i = list.getSelectionModel().getSelectedIndex();
            if (loading || i < 0) {
                return;
            }
            loading = true;
            items.set(i, new IssueLinks.Rule(pattern.getText(), url.getText()));
            list.getSelectionModel().select(i);
            loading = false;
            updatePreview();
        }

        private void updatePreview() {
            String err = pattern.getText().isBlank() ? null : IssueLinks.validate(pattern.getText());
            error.setText(err == null ? "" : "Invalid pattern: " + err);
            TextFlow flow = IssueLinks.flow(sample.getText(), null, List.copyOf(items), "commit-body", u -> { });
            preview.getChildren().setAll(flow);
        }

        void save() {
            IssueLinks.saveRules(items);
        }
    }
}
