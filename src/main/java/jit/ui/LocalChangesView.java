package jit.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import jit.git.Repo;
import jit.git.StatusEntry;

/**
 * Working tree view: unstaged / staged files (as a folder tree or a flat list) beside the diff with hunk
 * staging, and the commit message across the full width below.
 */
public final class LocalChangesView extends SplitPane {
    private final RepositoryView rv;
    private final FileTree<StatusEntry> unstaged = new FileTree<>(StatusEntry::path, StatusEntry::unstagedStatus,
            StatusEntry::origPath, "No unstaged changes");
    private final FileTree<StatusEntry> staged = new FileTree<>(StatusEntry::path, StatusEntry::stagedStatus,
            StatusEntry::origPath, "No staged changes");
    private final BooleanProperty treeMode = FileTree.treeModeSetting("changes.tree");
    private final Label unstagedTitle = Ui.label("Unstaged Changes", "pane-header-text");
    private final Label stagedTitle = Ui.label("Staged Changes", "pane-header-text");
    private final Button stageSelection;
    private final Button stageAll;
    private final Button unstageAll;
    private final TextField subject = new TextField();
    private final TextArea description = new TextArea();
    private final CheckBox amend = new CheckBox("Amend");
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
    private boolean diffStale;

    public LocalChangesView(RepositoryView rv) {
        this.rv = rv;
        getStyleClass().add("local-changes");
        setOrientation(Orientation.VERTICAL);

        stageSelection = Ui.smallButton("Stage Selection", Icons.CHECK, () -> stage(unstaged.selectedEntries()));
        stageSelection.setTooltip(new Tooltip("Stage the selected files (Enter)"));
        stageSelection.setDisable(true);
        stageAll = Ui.smallButton("Stage All", Icons.PLUS, () -> stage(unstaged.entries()));
        unstageAll = Ui.smallButton("Unstage All", Icons.MINUS, () -> unstage(staged.entries()));

        setupTree(unstaged, true);
        setupTree(staged, false);
        unstaged.setTreeMode(treeMode.get());
        staged.setTreeMode(treeMode.get());
        treeMode.addListener((obs, o, n) -> {
            unstaged.setTreeMode(n);
            staged.setTreeMode(n);
        });

        VBox unstagedBox = pane(unstagedTitle, unstaged, stageSelection, stageAll);
        VBox stagedBox = pane(stagedTitle, staged, unstageAll);
        SplitPane lists = new SplitPane(unstagedBox, stagedBox);
        lists.setOrientation(Orientation.VERTICAL);
        lists.setDividerPositions(0.55);

        SplitPane files = new SplitPane(lists, diff);
        files.setDividerPositions(0.34);
        SplitPane.setResizableWithParent(lists, false);

        VBox commitBox = buildCommitBox();
        getItems().addAll(files, commitBox);
        setDividerPositions(0.76);
        SplitPane.setResizableWithParent(commitBox, false);

        diff.setPatchHandler((patch, cached, reverse, what) ->
                rv.run(what, p -> rv.repo().applyPatch(patch, cached, reverse)));
        // the diff is only loaded while this view is shown; catch up when it appears
        visibleProperty().addListener((obs, o, visible) -> {
            if (visible && diffStale) {
                showDiff();
            }
        });
    }

    private VBox buildCommitBox() {
        subject.setPromptText("Commit subject");
        subject.getStyleClass().add("commit-subject-field");
        description.setPromptText("Description");
        description.setPrefRowCount(3);
        description.setWrapText(true);
        commitButton.getStyleClass().add("commit-button");
        commitButton.setOnAction(e -> commit());
        BooleanBinding keeping = amend.selectedProperty().and(keepMessage.selectedProperty());
        commitButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> subject.getText().isBlank() && !keeping.get(), subject.textProperty(), keeping));
        subject.disableProperty().bind(keeping);
        description.disableProperty().bind(keeping);
        amend.setTooltip(new Tooltip("Amend the last commit instead of creating a new one"));
        amend.setMinWidth(Region.USE_PREF_SIZE);
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
        optionsButton.setMinWidth(Region.USE_PREF_SIZE);
        optionsButton.setTooltip(new Tooltip("Commit options: amend, sign-off, skip hooks, push after commit …"));

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

        commitButton.setMinWidth(Region.USE_PREF_SIZE);
        commitButton.setPrefWidth(170);
        HBox subjectRow = new HBox(6, subject, recentButton);
        HBox.setHgrow(subject, Priority.ALWAYS);
        subjectRow.setAlignment(Pos.CENTER_LEFT);
        HBox commitRow = new HBox(10, amend, optionsButton, Ui.spacer(), commitButton);
        commitRow.setAlignment(Pos.CENTER_LEFT);
        VBox commitBox = new VBox(8, stateLabel, subjectRow, description, commitRow);
        VBox.setVgrow(description, Priority.ALWAYS);
        commitBox.getStyleClass().add("commit-box");
        commitBox.setMinHeight(Region.USE_PREF_SIZE);
        return commitBox;
    }

    private VBox pane(Label title, FileTree<StatusEntry> tree, Button... actions) {
        ToggleButton layout = FileTree.layoutToggle(treeMode);
        HBox header = new HBox(8, title, Ui.spacer(), layout);
        header.getChildren().addAll(actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("pane-header");
        StackPane body = new StackPane(tree, tree.placeholder());
        VBox box = new VBox(header, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        return box;
    }

    private void setupTree(FileTree<StatusEntry> tree, boolean isUnstaged) {
        tree.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<Object>) c -> {
                    stageSelection.setDisable(!unstaged.hasSelection());
                    if (selecting) {
                        return;
                    }
                    if (tree.hasSelection()) {
                        selecting = true;
                        (isUnstaged ? staged : unstaged).getSelectionModel().clearSelection();
                        selecting = false;
                    }
                    showDiff();
                });
        tree.setOnKeyPressed(e -> {
            List<StatusEntry> sel = tree.selectedEntries();
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
        // double-click on a file stages / unstages it (on a folder it just expands)
        tree.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && tree.singleEntry() != null
                    && e.getTarget() instanceof javafx.scene.Node n && insideCell(n)) {
                StatusEntry entry = tree.singleEntry();
                if (isUnstaged) {
                    stage(List.of(entry));
                } else {
                    unstage(List.of(entry));
                }
            }
        });
        tree.setOnContextMenuRequested(e -> {
            List<StatusEntry> sel = tree.selectedEntries();
            if (!sel.isEmpty()) {
                menu(isUnstaged, sel, tree.singleEntry()).show(tree, e.getScreenX(), e.getScreenY());
            }
            e.consume();
        });
    }

    private static boolean insideCell(javafx.scene.Node n) {
        for (javafx.scene.Node p = n; p != null; p = p.getParent()) {
            if (p instanceof TreeCell<?> cell) {
                return !cell.isEmpty();
            }
        }
        return false;
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
            // rebuilding an unchanged tree costs time and flickers; most refreshes change nothing here
            if (!u.equals(unstaged.entries())) {
                unstaged.setEntries(u);
            }
            if (!s.equals(staged.entries())) {
                staged.setEntries(s);
            }
        } finally {
            selecting = false;
        }
        stageSelection.setDisable(!unstaged.hasSelection());
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
        // the file may have changed even when its status did not, so reload the diff, but only when it is seen
        if (isVisible()) {
            showDiff();
        } else {
            diffStale = true;
        }
    }

    private void showDiff() {
        diffStale = false;
        StatusEntry u = unstaged.singleEntry();
        StatusEntry s = staged.singleEntry();
        StatusEntry e = u != null ? u : s;
        boolean isStaged = u == null;
        if (e == null) {
            int count = unstaged.selectedEntries().size() + staged.selectedEntries().size();
            diff.clear(count > 1 ? count + " files selected" : entries.isEmpty()
                    ? "No local changes" : "Select a file to see its changes");
            return;
        }
        rv.read(() -> rv.repo().workingDiff(e, isStaged), text -> {
            StatusEntry nowU = unstaged.singleEntry();
            StatusEntry nowS = staged.singleEntry();
            if (isStaged ? e.equals(nowS) : e.equals(nowU)) {
                DiffView.Mode mode = e.isConflict() ? DiffView.Mode.READ_ONLY
                        : isStaged ? DiffView.Mode.STAGED : DiffView.Mode.UNSTAGED;
                diff.setDiff(e.path(), text, mode);
                diff.setExternalDiff(() -> rv.externalDiff(e, isStaged));
            }
        });
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
        if (amending && nothingStaged && !opts.resetAuthor()) {
            rv.read(() -> rv.repo().lastCommitMessage(), last -> {
                if (amendChangesNothing(last, full, opts) && !Dialogs.confirm(rv.window(), "Nothing to amend",
                        "No changes are staged and the message is the same as the last commit's, so amending "
                                + "would only change its date and hash."
                                + (entries.isEmpty() ? "" : " Stage the changes you want to add to the commit first."),
                        "Amend Anyway")) {
                    return;
                }
                rv.run("Amend commit", p -> rv.repo().commit(full, opts), afterCommit());
            });
            return;
        }
        rv.run(amending ? "Amend commit" : "Commit", p -> rv.repo().commit(full, opts), afterCommit());
    }

    /** Whether amending with nothing staged would leave the last commit's message as it is. */
    private static boolean amendChangesNothing(String last, String message, Repo.CommitOptions opts) {
        if (opts.signoff() && !last.contains("Signed-off-by: ")) {
            return false;
        }
        return opts.keepMessage() || normalized(last).equals(normalized(message));
    }

    private static String normalized(String message) {
        return message.strip().replaceAll("\\s+", " ");
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

    private ContextMenu menu(boolean isUnstaged, List<StatusEntry> sel, StatusEntry single) {
        ContextMenu menu = new ContextMenu();
        if (isUnstaged) {
            menu.getItems().add(Ui.item(sel.stream().anyMatch(StatusEntry::isConflict) ? "Mark as Resolved" : "Stage",
                    Icons.PLUS, () -> stage(sel)));
            menu.getItems().add(Ui.item("Discard Changes…", Icons.UNDO, () -> discard(sel)));
        } else {
            menu.getItems().add(Ui.item("Unstage", Icons.MINUS, () -> unstage(sel)));
        }
        menu.getItems().add(Ui.item("Save as Patch…", Icons.FILE,
                () -> rv.saveWorkingPatch(sel, !isUnstaged)));
        if (single != null) {
            StatusEntry e = single;
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
        menu.getItems().addAll(new SeparatorMenuItem(),
                Ui.item(treeMode.get() ? "Show as Flat List" : "Show as Folder Tree",
                        treeMode.get() ? Icons.LIST : Icons.FOLDER, () -> treeMode.set(!treeMode.get())));
        return menu;
    }

    void selectFirstIfNone() {
        if (!unstaged.hasSelection() && !staged.hasSelection()) {
            if (!unstaged.entries().isEmpty()) {
                unstaged.selectFirstFile();
            } else if (!staged.entries().isEmpty()) {
                staged.selectFirstFile();
            }
        }
        Platform.runLater(() -> {
            if (unstaged.hasSelection()) {
                unstaged.requestFocus();
            }
        });
    }
}
