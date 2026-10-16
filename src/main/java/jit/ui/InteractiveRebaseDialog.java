package jit.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import jit.git.Commit;
import jit.git.Repo;
import jit.git.Repo.RebaseAction;

/** Lets the user plan an interactive rebase: reorder, reword, edit, squash, fixup or drop commits. */
final class InteractiveRebaseDialog {

    /** Result of the dialog. */
    record Plan(List<Repo.RebaseStep> steps, boolean autostash) {
    }

    private static final class Step {
        RebaseAction action;
        final Commit commit;
        String message;

        Step(Commit commit, String message) {
            this.action = RebaseAction.PICK;
            this.commit = commit;
            this.message = message;
        }
    }

    private InteractiveRebaseDialog() {
    }

    /**
     * @param commits  commits to replay, oldest first, with their full messages in {@code messages}
     * @param preset   commit to pre-select with {@code presetAction}, or null
     */
    static Optional<Plan> show(Window owner, String title, List<Commit> commits, List<String> messages,
                               String preset, RebaseAction presetAction, boolean dirty, int merges) {
        ObservableList<Step> steps = FXCollections.observableArrayList();
        for (int i = 0; i < commits.size(); i++) {
            Step s = new Step(commits.get(i), messages.get(i));
            if (commits.get(i).hash.equals(preset) && presetAction != null) {
                s.action = presetAction;
            }
            steps.add(s);
        }

        ListView<Step> list = new ListView<>(steps);
        list.getStyleClass().addAll("file-list", "rebase-list");
        list.setPrefSize(720, 360);
        list.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        TextArea message = new TextArea();
        message.setPrefRowCount(5);
        message.setWrapText(true);
        message.setPromptText("Select a commit set to Reword to edit its message");
        Label problem = Ui.label("", "danger-text");
        boolean[] loading = {false};

        Runnable validate = () -> {
            String error = null;
            boolean seenPick = false;
            for (Step s : steps) {
                if (s.action == RebaseAction.DROP) {
                    continue;
                }
                if ((s.action == RebaseAction.SQUASH || s.action == RebaseAction.FIXUP) && !seenPick) {
                    error = "The first kept commit cannot be squashed or fixed up — there is nothing above it.";
                    break;
                }
                seenPick = true;
            }
            if (error == null && steps.stream().allMatch(s -> s.action == RebaseAction.DROP)) {
                error = "All commits are dropped.";
            }
            problem.setText(error == null ? "" : error);
        };

        list.setCellFactory(v -> new ListCell<>() {
            private final ComboBox<RebaseAction> action = new ComboBox<>();
            private final Label sha = Ui.label("", "mono", "dim-text");
            private final Label subject = Ui.label("", "file-name");
            private final Label author = Ui.label("", "dim-text");
            private final HBox box = new HBox(10, action, sha, subject, Ui.spacer(), author);

            {
                setPrefWidth(0);
                action.getItems().addAll(RebaseAction.values());
                action.setPrefWidth(110);
                action.setFocusTraversable(false);
                action.setButtonCell(actionCell());
                action.setCellFactory(c -> actionCell());
                action.valueProperty().addListener((obs, o, n) -> {
                    Step s = getItem();
                    if (s != null && n != null && s.action != n) {
                        s.action = n;
                        list.getSelectionModel().clearAndSelect(getIndex());
                        validate.run();
                        refreshStyle(s);
                    }
                });
                subject.setMinWidth(0);
                author.setMinWidth(Region.USE_PREF_SIZE);
                box.setAlignment(Pos.CENTER_LEFT);
            }

            private void refreshStyle(Step s) {
                getStyleClass().removeAll("rebase-drop", "rebase-squash");
                if (s.action == RebaseAction.DROP) {
                    getStyleClass().add("rebase-drop");
                } else if (s.action == RebaseAction.SQUASH || s.action == RebaseAction.FIXUP) {
                    getStyleClass().add("rebase-squash");
                }
            }

            @Override
            protected void updateItem(Step s, boolean empty) {
                super.updateItem(s, empty);
                setText(null);
                if (empty || s == null) {
                    setGraphic(null);
                    getStyleClass().removeAll("rebase-drop", "rebase-squash");
                    return;
                }
                action.setValue(s.action);
                sha.setText(s.commit.shortHash());
                String first = s.message.lines().findFirst().orElse(s.commit.subject);
                subject.setText(s.action == RebaseAction.SQUASH || s.action == RebaseAction.FIXUP
                        ? "↑ " + first : first);
                author.setText(s.commit.author + " · " + Ui.formatDate(s.commit.authorTime));
                refreshStyle(s);
                setGraphic(box);
            }
        });

        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            loading[0] = true;
            message.setText(n == null ? "" : n.message);
            message.setDisable(n == null || n.action != RebaseAction.REWORD);
            loading[0] = false;
        });
        message.textProperty().addListener((obs, o, n) -> {
            Step s = list.getSelectionModel().getSelectedItem();
            if (!loading[0] && s != null && s.action == RebaseAction.REWORD) {
                s.message = n;
                list.refresh();
            }
        });

        HBox actions = new HBox(6);
        for (RebaseAction a : RebaseAction.values()) {
            actions.getChildren().add(Ui.smallButton(label(a), null, () -> {
                for (Step s : list.getSelectionModel().getSelectedItems()) {
                    s.action = a;
                }
                validate.run();
                list.refresh();
                Step sel = list.getSelectionModel().getSelectedItem();
                message.setDisable(sel == null || sel.action != RebaseAction.REWORD);
            }));
        }
        actions.getChildren().addAll(Ui.spacer(),
                Ui.smallButton("Move Up", Icons.ARROW_UP, () -> move(list, steps, -1, validate)),
                Ui.smallButton("Move Down", Icons.ARROW_DOWN, () -> move(list, steps, 1, validate)));
        actions.setAlignment(Pos.CENTER_LEFT);
        list.setOnKeyPressed(e -> {
            if (e.isAltDown() && e.getCode() == KeyCode.UP) {
                move(list, steps, -1, validate);
                e.consume();
            } else if (e.isAltDown() && e.getCode() == KeyCode.DOWN) {
                move(list, steps, 1, validate);
                e.consume();
            }
        });

        Label help = Ui.label("Commits are replayed from top to bottom. Squash and Fixup combine a commit with "
                + "the one above it (Fixup discards its message). Edit stops so you can amend the commit, then "
                + "use Continue in the banner. You can abort the whole rebase at any stop with Abort.", "form-note");
        help.setWrapText(true);
        CheckBox autostash = new CheckBox("Stash local changes before rebasing and reapply them afterwards");
        autostash.setSelected(dirty);
        autostash.setVisible(dirty);
        autostash.setManaged(dirty);
        VBox content = new VBox(10, help);
        if (merges > 0) {
            Label warn = Ui.label(merges + (merges == 1 ? " merge commit" : " merge commits")
                    + " in this range will be flattened (merge commits are not kept).", "state-hint");
            warn.setWrapText(true);
            content.getChildren().add(warn);
        }
        content.getChildren().addAll(actions, list, Ui.label("Message (Reword)", "form-label"), message,
                autostash, problem);
        VBox.setVgrow(list, Priority.ALWAYS);
        content.setPadding(new Insets(14, 20, 6, 20));

        Dialog<ButtonType> d = new Dialog<>();
        if (owner != null) {
            d.initOwner(owner);
        }
        d.setTitle("Interactive Rebase");
        d.setHeaderText(title);
        d.setResizable(true);
        d.getDialogPane().getStylesheets().setAll(Theme.stylesheets());
        d.getDialogPane().getStyleClass().add("jit-dialog");
        d.getDialogPane().setContent(content);
        ButtonType start = new ButtonType("Start Rebase", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(start, ButtonType.CANCEL);
        d.getDialogPane().lookupButton(start).disableProperty().bind(problem.textProperty().isNotEmpty());
        validate.run();
        if (preset != null) {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i).commit.hash.equals(preset)) {
                    list.getSelectionModel().select(i);
                    list.scrollTo(i);
                }
            }
        }
        if (d.showAndWait().filter(b -> b == start).isEmpty()) {
            return Optional.empty();
        }
        List<Repo.RebaseStep> result = new ArrayList<>();
        for (Step s : steps) {
            result.add(new Repo.RebaseStep(s.action, s.commit, s.message));
        }
        return Optional.of(new Plan(result, autostash.isSelected()));
    }

    private static void move(ListView<Step> list, ObservableList<Step> steps, int delta, Runnable validate) {
        int i = list.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= steps.size()) {
            return;
        }
        Step s = steps.remove(i);
        steps.add(j, s);
        list.getSelectionModel().clearAndSelect(j);
        validate.run();
    }

    static String label(RebaseAction a) {
        String n = a.name().toLowerCase();
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static ListCell<RebaseAction> actionCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(RebaseAction a, boolean empty) {
                super.updateItem(a, empty);
                setText(empty || a == null ? null : label(a));
            }
        };
    }
}
