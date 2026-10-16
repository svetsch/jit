package jit.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import jit.git.Repo;
import jit.git.StatusEntry;

/** Working tree view: unstaged / staged files, diff with hunk staging, commit message. */
public final class LocalChangesView extends SplitPane {
    private final RepositoryView rv;
    private final ListView<StatusEntry> unstaged = new ListView<>();
    private final ListView<StatusEntry> staged = new ListView<>();
    private final Label unstagedTitle = Ui.label("Unstaged Changes", "pane-header-text");
    private final Label stagedTitle = Ui.label("Staged Changes", "pane-header-text");
    private final Button stageAll;
    private final Button unstageAll;
    private final TextField subject = new TextField();
    private final TextArea description = new TextArea();
    private final CheckBox amend = new CheckBox("Amend last commit");
    private final CheckMenuItem keepMessage = new CheckMenuItem("Keep previous message (amend)");
    private final CheckMenuItem resetAuthor = new CheckMenuItem("Reset author to me (amend)");
    private final CheckMenuItem signoff = new CheckMenuItem("Add Signed-off-by line");
    private final CheckMenuItem noVerify = new CheckMenuItem("Skip commit hooks (--no-verify)");
    private final CheckMenuItem allowEmpty = new CheckMenuItem("Allow empty commit");
    private final CheckMenuItem pushAfter = new CheckMenuItem("Push after commit");
    private final MenuButton optionsButton = new MenuButton("Options");
    private final MenuButton recentButton = new MenuButton();
    private final Button commitButton = new Button("Commit");
    private final Label stateLabel = Ui.label("", "state-hint");
    private final DiffView diff = new DiffView();

    private List<StatusEntry> entries = List.of();
    private Repo.State state = Repo.State.NONE;
    private boolean selecting;

    public LocalChangesView(RepositoryView rv) {
        this.rv = rv;
        getStyleClass().add("local-changes");

        stageAll = Ui.smallButton("Stage All", Icons.PLUS, () -> stage(unstaged.getItems()));
        unstageAll = Ui.smallButton("Unstage All", Icons.MINUS, () -> unstage(staged.getItems()));

        setupList(unstaged, true);
        setupList(staged, false);

        VBox unstagedBox = pane(unstagedTitle, stageAll, unstaged);
        VBox stagedBox = pane(stagedTitle, unstageAll, staged);
        SplitPane lists = new SplitPane(unstagedBox, stagedBox);
        lists.setOrientation(Orientation.VERTICAL);
        lists.setDividerPositions(0.55);
        VBox.setVgrow(lists, Priority.ALWAYS);

        subject.setPromptText("Commit subject");
        subject.getStyleClass().add("commit-subject-field");
        description.setPromptText("Description");
        description.setPrefRowCount(4);
        description.setWrapText(true);
        commitButton.getStyleClass().add("commit-button");
        commitButton.setDefaultButton(false);
        commitButton.setMaxWidth(Double.MAX_VALUE);
        commitButton.setOnAction(e -> commit());
        BooleanBinding keeping = amend.selectedProperty().and(keepMessage.selectedProperty());
        commitButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> subject.getText().isBlank() && !keeping.get(), subject.textProperty(), keeping));
        subject.disableProperty().bind(keeping);
        description.disableProperty().bind(keeping);
        amend.selectedProperty().addListener((obs, o, n) -> {
            if (n && subject.getText().isBlank()) {
                rv.read(() -> rv.repo().lastCommitMessage(), this::setMessage);
            }
            updateCommitButton();
        });
        keepMessage.disableProperty().bind(amend.selectedProperty().not());
        resetAuthor.disableProperty().bind(amend.selectedProperty().not());
        pushAfter.setSelected(Boolean.parseBoolean(Settings.get("commit.pushAfter", "false")));
        pushAfter.selectedProperty().addListener((obs, o, n) -> Settings.put("commit.pushAfter", n.toString()));
        CheckMenuItem amendItem = new CheckMenuItem("Amend last commit");
        amendItem.selectedProperty().bindBidirectional(amend.selectedProperty());
        optionsButton.getItems().addAll(amendItem, keepMessage, resetAuthor, new SeparatorMenuItem(),
                signoff, noVerify, allowEmpty, new SeparatorMenuItem(), pushAfter);
        optionsButton.getStyleClass().add("options-button");
        optionsButton.setFocusTraversable(false);

        recentButton.setGraphic(Icons.icon(Icons.HISTORY, 14));
        recentButton.getStyleClass().add("recent-messages-button");
        recentButton.setTooltip(new Tooltip("Reuse a recent commit message"));
        recentButton.setFocusTraversable(false);
        recentButton.getItems().add(new MenuItem("…"));
        recentButton.setOnShowing(e -> fillRecentMessages());
        subject.addEventFilter(KeyEvent.KEY_PRESSED, this::commitShortcut);
        description.addEventFilter(KeyEvent.KEY_PRESSED, this::commitShortcut);
        stateLabel.setWrapText(true);
        stateLabel.setManaged(false);
        stateLabel.setVisible(false);

        amend.setText("Amend");
        amend.setTooltip(new Tooltip("Amend the last commit instead of creating a new one"));
        amend.setMinWidth(Region.USE_PREF_SIZE);
        optionsButton.setMinWidth(Region.USE_PREF_SIZE);
        optionsButton.setTooltip(new Tooltip("Commit options: amend, sign-off, skip hooks, push after commit …"));
        HBox commitRow = new HBox(8, amend, optionsButton, Ui.spacer(), commitButton);
        commitRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(commitButton, Priority.NEVER);
        commitButton.setMinWidth(Region.USE_PREF_SIZE);
        commitButton.setPrefWidth(150);
        HBox subjectRow = new HBox(4, subject, recentButton);
        HBox.setHgrow(subject, Priority.ALWAYS);
        subjectRow.setAlignment(Pos.CENTER_LEFT);
        VBox commitBox = new VBox(8, stateLabel, subjectRow, description, commitRow);
        commitBox.getStyleClass().add("commit-box");

        VBox left = new VBox(lists, commitBox);
        getItems().addAll(left, diff);
        setDividerPositions(0.34);
        SplitPane.setResizableWithParent(left, false);

        diff.setPatchHandler((patch, cached, reverse, what) ->
                rv.run(what, p -> rv.repo().applyPatch(patch, cached, reverse)));
    }

    private VBox pane(Label title, Button action, ListView<StatusEntry> list) {
        HBox header = new HBox(8, title, Ui.spacer(), action);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("pane-header");
        VBox box = new VBox(header, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    private void setupList(ListView<StatusEntry> list, boolean isUnstaged) {
        list.getStyleClass().add("file-list");
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPlaceholder(Ui.label(isUnstaged ? "No unstaged changes" : "No staged changes", "placeholder-text"));
        list.setCellFactory(l -> new StatusCell(isUnstaged));
        list.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (selecting) {
                return;
            }
            if (n != null) {
                selecting = true;
                (isUnstaged ? staged : unstaged).getSelectionModel().clearSelection();
                selecting = false;
            }
            showDiff();
        });
        list.setOnKeyPressed(e -> {
            List<StatusEntry> sel = new ArrayList<>(list.getSelectionModel().getSelectedItems());
            if (sel.isEmpty()) {
                return;
            }
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                if (isUnstaged) {
                    stage(sel);
                } else {
                    unstage(sel);
                }
                e.consume();
            } else if (e.getCode() == KeyCode.DELETE && isUnstaged) {
                discard(sel);
                e.consume();
            }
        });
    }

    private void commitShortcut(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER && e.isShortcutDown()) {
            e.consume();
            if (!commitButton.isDisabled()) {
                commit();
            }
        }
    }

    public void focusMessage() {
        subject.requestFocus();
    }

    public void setStatus(List<StatusEntry> newEntries, Repo.State newState, String preparedMessage) {
        boolean stateChanged = newState != state;
        this.entries = newEntries;
        this.state = newState;

        boolean unstagedFocus = !unstaged.getSelectionModel().isEmpty();
        boolean stagedFocus = !staged.getSelectionModel().isEmpty();
        List<String> selUnstaged = paths(unstaged.getSelectionModel().getSelectedItems());
        List<String> selStaged = paths(staged.getSelectionModel().getSelectedItems());
        int oldUnstagedIndex = unstaged.getSelectionModel().getSelectedIndex();
        int oldStagedIndex = staged.getSelectionModel().getSelectedIndex();

        List<StatusEntry> u = new ArrayList<>();
        List<StatusEntry> s = new ArrayList<>();
        for (StatusEntry e : newEntries) {
            if (e.hasUnstaged()) {
                u.add(e);
            }
            if (e.hasStaged()) {
                s.add(e);
            }
        }
        selecting = true;
        try {
            unstaged.getItems().setAll(u);
            staged.getItems().setAll(s);
            restoreSelection(unstaged, selUnstaged, unstagedFocus ? oldUnstagedIndex : -1);
            restoreSelection(staged, selStaged, stagedFocus ? oldStagedIndex : -1);
        } finally {
            selecting = false;
        }
        unstagedTitle.setText("Unstaged Changes  (" + u.size() + ")");
        stagedTitle.setText("Staged Changes  (" + s.size() + ")");
        stageAll.setDisable(u.isEmpty());
        unstageAll.setDisable(s.isEmpty());

        if (newState != Repo.State.NONE) {
            stateLabel.setText(newState.label + ". Resolve conflicts, stage the files and commit"
                    + (newState == Repo.State.MERGING ? "." : " (or use Continue in the banner above)."));
            stateLabel.setVisible(true);
            stateLabel.setManaged(true);
        } else {
            stateLabel.setVisible(false);
            stateLabel.setManaged(false);
        }
        if (stateChanged && preparedMessage != null && !preparedMessage.isBlank() && subject.getText().isBlank()) {
            setMessage(preparedMessage);
        }
        updateCommitButton();
        showDiff();
    }

    private static List<String> paths(List<StatusEntry> list) {
        List<String> r = new ArrayList<>();
        for (StatusEntry e : list) {
            if (e != null) {
                r.add(e.path());
            }
        }
        return r;
    }

    private static void restoreSelection(ListView<StatusEntry> list, List<String> paths, int fallbackIndex) {
        list.getSelectionModel().clearSelection();
        boolean any = false;
        for (int i = 0; i < list.getItems().size(); i++) {
            if (paths.contains(list.getItems().get(i).path())) {
                list.getSelectionModel().select(i);
                any = true;
            }
        }
        if (!any && fallbackIndex >= 0 && !list.getItems().isEmpty()) {
            list.getSelectionModel().select(Math.min(fallbackIndex, list.getItems().size() - 1));
        }
    }

    private void showDiff() {
        StatusEntry u = single(unstaged);
        StatusEntry s = single(staged);
        StatusEntry e = u != null ? u : s;
        boolean isStaged = u == null;
        if (e == null) {
            int count = unstaged.getSelectionModel().getSelectedItems().size()
                    + staged.getSelectionModel().getSelectedItems().size();
            diff.clear(count > 1 ? count + " files selected" : entries.isEmpty()
                    ? "No local changes" : "Select a file to see its changes");
            return;
        }
        rv.read(() -> rv.repo().workingDiff(e, isStaged), text -> {
            StatusEntry nowU = single(unstaged);
            StatusEntry nowS = single(staged);
            if (isStaged ? e.equals(nowS) : e.equals(nowU)) {
                DiffView.Mode mode = e.isConflict() ? DiffView.Mode.READ_ONLY
                        : isStaged ? DiffView.Mode.STAGED : DiffView.Mode.UNSTAGED;
                diff.setDiff(e.path(), text, mode);
                diff.setExternalDiff(() -> rv.externalDiff(e, isStaged));
            }
        });
    }

    private static StatusEntry single(ListView<StatusEntry> list) {
        return list.getSelectionModel().getSelectedItems().size() == 1 ? list.getSelectionModel().getSelectedItem() : null;
    }

    private void fillRecentMessages() {
        recentButton.getItems().setAll(new MenuItem("Loading…"));
        rv.read(() -> rv.repo().recentMessages(20), messages -> {
            recentButton.getItems().clear();
            if (messages.isEmpty()) {
                MenuItem none = new MenuItem("No previous messages");
                none.setDisable(true);
                recentButton.getItems().add(none);
            }
            for (String m : messages) {
                String first = m.lines().findFirst().orElse(m);
                MenuItem mi = new MenuItem(first.length() > 80 ? first.substring(0, 79) + "…" : first);
                mi.setMnemonicParsing(false);
                mi.setOnAction(e -> setMessage(m));
                recentButton.getItems().add(mi);
            }
            recentButton.getItems().addAll(new SeparatorMenuItem(),
                    Ui.item("Clear Message", Icons.CLOSE, this::clearMessage));
        });
    }

    private void updateCommitButton() {
        long count = entries.stream().filter(StatusEntry::hasStaged).count();
        if (amend.isSelected()) {
            commitButton.setText(count > 0 ? "Amend (+" + count + (count == 1 ? " file)" : " files)") : "Amend");
        } else if (count > 0) {
            commitButton.setText("Commit " + count + (count == 1 ? " file" : " files"));
        } else {
            commitButton.setText("Commit");
        }
    }

    private void setMessage(String msg) {
        if (msg == null) {
            return;
        }
        int nl = msg.indexOf('\n');
        if (nl < 0) {
            subject.setText(msg.strip());
            description.clear();
        } else {
            subject.setText(msg.substring(0, nl).strip());
            description.setText(msg.substring(nl + 1).strip());
        }
    }

    private void clearMessage() {
        subject.clear();
        description.clear();
        amend.setSelected(false);
        keepMessage.setSelected(false);
        resetAuthor.setSelected(false);
        allowEmpty.setSelected(false);
    }

    private Repo.CommitOptions options() {
        return new Repo.CommitOptions(amend.isSelected(), keepMessage.isSelected(), resetAuthor.isSelected(),
                signoff.isSelected(), noVerify.isSelected(), allowEmpty.isSelected());
    }

    private Runnable afterCommit() {
        boolean push = pushAfter.isSelected();
        return () -> {
            clearMessage();
            if (push) {
                rv.pushCurrentBranch();
            }
        };
    }

    private void commit() {
        String msg = subject.getText().strip();
        String body = description.getText().strip();
        String full = body.isEmpty() ? msg : msg + "\n\n" + body;
        boolean amending = amend.isSelected();
        Repo.CommitOptions opts = options();
        boolean nothingStaged = entries.stream().noneMatch(StatusEntry::hasStaged);
        if (nothingStaged && !amending && !opts.allowEmpty()) {
            if (entries.isEmpty()) {
                Dialogs.info(rv.window(), "Nothing to commit", "There are no local changes to commit.");
                return;
            }
            if (!Dialogs.confirm(rv.window(), "Commit", "No changes are staged. Stage all changes and commit them?",
                    "Stage All and Commit")) {
                return;
            }
            List<StatusEntry> all = new ArrayList<>(entries);
            rv.run("Commit", p -> {
                rv.repo().stage(all);
                rv.repo().commit(full, opts);
            }, afterCommit());
            return;
        }
        rv.run(amending ? "Amend commit" : "Commit", p -> rv.repo().commit(full, opts), afterCommit());
    }

    private void stage(List<StatusEntry> list) {
        List<StatusEntry> copy = new ArrayList<>(list);
        if (!copy.isEmpty()) {
            rv.run("Stage", p -> rv.repo().stage(copy));
        }
    }

    private void unstage(List<StatusEntry> list) {
        List<StatusEntry> copy = new ArrayList<>(list);
        if (!copy.isEmpty()) {
            rv.run("Unstage", p -> rv.repo().unstage(copy));
        }
    }

    private void discard(List<StatusEntry> list) {
        List<StatusEntry> copy = new ArrayList<>(list);
        if (copy.isEmpty()) {
            return;
        }
        String what = copy.size() == 1 ? copy.get(0).path() : copy.size() + " files";
        boolean untracked = copy.stream().anyMatch(StatusEntry::isUntracked);
        if (Dialogs.confirm(rv.window(), "Discard changes",
                "Discard all unstaged changes in " + what + "?"
                        + (untracked ? " Untracked files will be deleted." : "") + " This cannot be undone.",
                "Discard")) {
            rv.run("Discard changes", p -> rv.repo().discard(copy));
        }
    }

    private ContextMenu menu(boolean isUnstaged, List<StatusEntry> sel) {
        ContextMenu menu = new ContextMenu();
        if (isUnstaged) {
            menu.getItems().add(Ui.item(sel.stream().anyMatch(StatusEntry::isConflict) ? "Mark as Resolved" : "Stage",
                    Icons.PLUS, () -> stage(sel)));
            menu.getItems().add(Ui.item("Discard Changes…", Icons.UNDO, () -> discard(sel)));
        } else {
            menu.getItems().add(Ui.item("Unstage", Icons.MINUS, () -> unstage(sel)));
        }
        if (sel.size() == 1) {
            StatusEntry e = sel.get(0);
            if (e.isConflict()) {
                menu.getItems().addAll(new SeparatorMenuItem(),
                        Ui.item("Resolve Using Mine (ours)", Icons.CHECK,
                                () -> rv.run("Resolve " + e.path(), p -> rv.repo().resolve(e, true))),
                        Ui.item("Resolve Using Theirs", Icons.CHECK,
                                () -> rv.run("Resolve " + e.path(), p -> rv.repo().resolve(e, false))));
            }
            if (e.isUntracked()) {
                menu.getItems().addAll(new SeparatorMenuItem(),
                        Ui.item("Add to .gitignore", Icons.CLOSE, () -> rv.ignore(e.path())));
            }
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().addAll(rv.fileMenu(e.path(), null, () -> rv.externalDiff(e, !isUnstaged)).getItems());
        }
        return menu;
    }

    private final class StatusCell extends ListCell<StatusEntry> {
        private final boolean isUnstaged;

        StatusCell(boolean isUnstaged) {
            setPrefWidth(0);
            this.isUnstaged = isUnstaged;
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null) {
                    if (isUnstaged) {
                        stage(List.of(getItem()));
                    } else {
                        unstage(List.of(getItem()));
                    }
                }
            });
            setOnContextMenuRequested(e -> {
                if (getItem() == null) {
                    return;
                }
                ListView<StatusEntry> list = getListView();
                if (!list.getSelectionModel().getSelectedItems().contains(getItem())) {
                    list.getSelectionModel().clearAndSelect(getIndex());
                }
                List<StatusEntry> sel = new ArrayList<>(list.getSelectionModel().getSelectedItems());
                menu(isUnstaged, sel).show(this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }

        @Override
        protected void updateItem(StatusEntry item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            char st = isUnstaged ? item.unstagedStatus() : item.stagedStatus();
            setGraphic(Ui.fileRow(st, item.path(), isUnstaged ? null : item.origPath()));
        }
    }

    void selectFirstIfNone() {
        if (unstaged.getSelectionModel().isEmpty() && staged.getSelectionModel().isEmpty()) {
            if (!unstaged.getItems().isEmpty()) {
                unstaged.getSelectionModel().select(0);
            } else if (!staged.getItems().isEmpty()) {
                staged.getSelectionModel().select(0);
            }
        }
        Platform.runLater(() -> {
            if (!unstaged.getSelectionModel().isEmpty()) {
                unstaged.requestFocus();
            }
        });
    }
}
