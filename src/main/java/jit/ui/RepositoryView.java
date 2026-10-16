package jit.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.geometry.Orientation;
import javafx.stage.Window;

import jit.git.Commit;
import jit.git.FileChange;
import jit.git.Ref;
import jit.git.Repo;
import jit.git.Stash;
import jit.git.StatusEntry;
import jit.graph.GraphBuilder;
import jit.graph.GraphRow;
import jit.hosting.HostedRepo;

/** One open repository: toolbar, sidebar, history / local changes, status bar. */
public final class RepositoryView extends BorderPane {
    private static final int MAX_COMMITS = 10_000;
    private static final ExecutorService READERS = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "git-read");
        t.setDaemon(true);
        return t;
    });

    /** A blocking git operation that may report progress lines. */
    public interface GitOp {
        void run(Consumer<String> progress) throws Exception;
    }

    private record Snapshot(List<Ref> refs, List<Commit> commits, String signature, List<StatusEntry> status,
                            List<Stash> stashes, Map<String, String> remotes, String headBranch, String headHash,
                            Repo.State state, String preparedMessage) {
    }

    private final Repo repo;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "git-write");
        t.setDaemon(true);
        return t;
    });

    private final Sidebar sidebar = new Sidebar();
    private final HistoryView history;
    private final LocalChangesView changes;
    private final CommitDetailsView stashDetails;
    private final StackPane content = new StackPane();
    private final Label statusText = Ui.label("Ready", "status-text");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final HBox banner = new HBox(10);
    private final Label bannerText = Ui.label("", "banner-text");
    private final HBox undoBar = new HBox(10);
    private final Label undoText = Ui.label("", "banner-text");
    private String undoTarget;
    private final Label repoLabel = Ui.label("", "repo-name");
    private final MenuButton branchButton = new MenuButton();
    private final MenuButton hostingButton = toolMenu("Remote", Icons.CLOUD, "Open the remote repository in the browser");
    private final MenuButton commandsButton = toolMenu("Commands", Icons.LIST, "Custom commands");

    private int busy;
    private boolean refreshing;
    private boolean refreshPending;
    private boolean pendingForce;
    private String renderedKey;

    private List<Ref> refs = List.of();
    private List<Commit> logCommits = List.of();
    private List<StatusEntry> status = List.of();
    private List<Stash> stashes = List.of();
    private Map<String, String> remotes = Map.of();
    private String headBranch;
    private String headHash;
    private Repo.State state = Repo.State.NONE;
    private String logSignature;
    private boolean loadedOnce;

    public RepositoryView(Repo repo) {
        this.repo = repo;
        getStyleClass().add("repository-view");
        history = new HistoryView(this);
        changes = new LocalChangesView(this);
        stashDetails = new CommitDetailsView(this);

        setTop(buildToolbar());

        sidebar.setOnSelect(this::onSidebarSelect);
        sidebar.setOnOpen(this::onSidebarOpen);
        sidebar.setMenus(this::sidebarMenu);

        content.getChildren().addAll(history, changes, stashDetails);
        buildBanner();
        buildUndoBar();
        VBox main = new VBox(banner, undoBar, content);
        VBox.setVgrow(content, Priority.ALWAYS);

        SplitPane split = new SplitPane(sidebar, main);
        split.setDividerPositions(0.2);
        SplitPane.setResizableWithParent(sidebar, false);
        setCenter(split);
        setBottom(buildStatusBar());

        showView(history);
        sidebar.selectAllCommits();
        refresh(true);
    }

    // ------------------------------------------------------------------ layout

    private HBox buildToolbar() {
        repoLabel.setText(repo.name());
        branchButton.getStyleClass().add("branch-button");
        branchButton.setGraphic(Icons.icon(Icons.BRANCH, 13));
        branchButton.setOnShowing(e -> fillBranchMenu());
        hostingButton.setOnShowing(e -> fillHostingMenu());
        commandsButton.setOnShowing(e -> fillCommandsMenu());
        branchButton.getItems().add(new MenuItem("…"));
        VBox repoBox = new VBox(1, repoLabel, branchButton);
        repoBox.setAlignment(Pos.CENTER_LEFT);
        repoBox.getStyleClass().add("repo-box");
        repoBox.setMinWidth(170);

        HBox bar = new HBox(2,
                repoBox,
                sep(),
                Ui.toolButton("Fetch", Icons.FETCH, "Fetch from remotes (Ctrl+Shift+F)", this::fetch),
                Ui.toolButton("Pull", Icons.PULL, "Pull the current branch (Ctrl+Shift+L)", this::pull),
                Ui.toolButton("Push", Icons.PUSH, "Push a branch (Ctrl+Shift+P)", this::push),
                sep(),
                Ui.toolButton("Stash", Icons.STASH, "Stash local changes (Ctrl+Shift+S)", this::stash),
                Ui.toolButton("Pop", Icons.POP, "Pop the latest stash", this::popLatestStash),
                sep(),
                Ui.toolButton("Branch", Icons.BRANCH, "Create a branch at HEAD (Ctrl+Shift+B)",
                        () -> createBranch("HEAD", headBranch == null ? "HEAD" : headBranch)),
                Ui.toolButton("Tag", Icons.TAG, "Create a tag at HEAD",
                        () -> createTag("HEAD", headBranch == null ? "HEAD" : headBranch)),
                sep(),
                Ui.toolButton("Terminal", Icons.TERMINAL, "Open a terminal here", () -> Ui.openTerminal(repo.root())),
                Ui.toolButton("Explorer", Icons.FOLDER, "Show in file explorer", () -> Ui.openInExplorer(repo.root())),
                sep(),
                hostingButton,
                commandsButton,
                Ui.spacer(),
                Ui.toolButton("Refresh", Icons.REFRESH, "Refresh (F5)", () -> refresh(true)));
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("repo-toolbar");
        return bar;
    }

    private static MenuButton toolMenu(String text, String icon, String tooltip) {
        MenuButton b = new MenuButton(text, Icons.icon(icon, 20));
        b.setContentDisplay(javafx.scene.control.ContentDisplay.TOP);
        b.getStyleClass().addAll("tool-button", "tool-menu");
        b.setFocusTraversable(false);
        b.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        b.getItems().add(new MenuItem("…"));
        return b;
    }

    private void fillHostingMenu() {
        hostingButton.getItems().clear();
        HostedRepo h = hosted();
        if (h == null) {
            MenuItem none = new MenuItem("No GitHub, GitLab, Bitbucket or Azure DevOps remote");
            none.setDisable(true);
            hostingButton.getItems().add(none);
            return;
        }
        hostingButton.getItems().addAll(
                Ui.item("Open Repository on " + h.provider.title, Icons.CLOUD, () -> Ui.openUrl(h.repositoryUrl())),
                Ui.item(h.provider.reviews, Icons.MERGE, () -> Ui.openUrl(h.pullRequestsUrl())),
                Ui.item("Create " + h.provider.review + "…", Icons.PLUS, () -> createPullRequest(headBranch)),
                new SeparatorMenuItem(),
                Ui.item("Issues", Icons.WARNING, () -> Ui.openUrl(h.issuesUrl())),
                Ui.item(h.provider.ci, Icons.REFRESH, () -> Ui.openUrl(h.ciUrl())));
        if (headBranch != null) {
            hostingButton.getItems().add(Ui.item("Current Branch on " + h.provider.title, Icons.BRANCH,
                    () -> Ui.openUrl(h.branchUrl(remoteBranchName(headBranch)))));
        }
    }

    private void fillCommandsMenu() {
        commandsButton.getItems().clear();
        for (CustomCommands.Command c : CustomCommands.forTarget(CustomCommands.Target.REPOSITORY)) {
            commandsButton.getItems().add(Ui.item(c.name(), () -> runCustom(c, null, null, null)));
        }
        if (!commandsButton.getItems().isEmpty()) {
            commandsButton.getItems().add(new SeparatorMenuItem());
        }
        commandsButton.getItems().addAll(
                Ui.item("Edit Custom Commands…", Icons.EDIT,
                        () -> PreferencesDialog.show(window(), PreferencesDialog.TAB_COMMANDS)),
                Ui.item("Git Command History", Icons.HISTORY, this::showCommandLog),
                Ui.item("Repository Settings…", Icons.EDIT, this::showRepositorySettings));
    }

    private static Separator sep() {
        Separator s = new Separator(Orientation.VERTICAL);
        s.getStyleClass().add("toolbar-separator");
        return s;
    }

    private void buildBanner() {
        Button cont = Ui.smallButton("Continue", Icons.CHECK, () -> continueOrAbort(false));
        Button skip = Ui.smallButton("Skip Commit", null, () -> run("Skip commit", p -> repo.skipRebaseCommit()));
        Button abort = Ui.smallButton("Abort", Icons.CLOSE, () -> continueOrAbort(true));
        abort.getStyleClass().add("danger");
        skip.visibleProperty().bind(bannerText.textProperty().map(t -> t.startsWith("Rebase")));
        skip.managedProperty().bind(skip.visibleProperty());
        banner.getChildren().addAll(Icons.icon(Icons.WARNING, 16, "warning"), bannerText, Ui.spacer(), cont, skip, abort);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().add("state-banner");
        banner.setVisible(false);
        banner.setManaged(false);
    }

    private void buildUndoBar() {
        Button undo = Ui.smallButton("Undo", Icons.UNDO, this::undoRebase);
        Button dismiss = Ui.smallButton(null, Icons.CLOSE, this::hideUndoBar);
        undoBar.getChildren().addAll(Icons.icon(Icons.CHECK, 16, "accent"), undoText, Ui.spacer(), undo, dismiss);
        undoBar.setAlignment(Pos.CENTER_LEFT);
        undoBar.getStyleClass().addAll("state-banner", "info-banner");
        hideUndoBar();
    }

    private void showUndoBar(String text, String target) {
        undoTarget = target;
        undoText.setText(text);
        undoBar.setVisible(true);
        undoBar.setManaged(true);
    }

    private void hideUndoBar() {
        undoTarget = null;
        undoBar.setVisible(false);
        undoBar.setManaged(false);
    }

    private void undoRebase() {
        String target = undoTarget;
        if (target == null) {
            return;
        }
        if (Dialogs.confirm(window(), "Undo rebase", "Move '" + current() + "' back to "
                + target.substring(0, 7) + ", where it was before the rebase? Local changes are kept.", "Undo")) {
            hideUndoBar();
            run("Undo rebase", p -> repo.resetKeep(target));
        }
    }

    private HBox buildStatusBar() {
        spinner.setPrefSize(14, 14);
        spinner.setMaxSize(14, 14);
        spinner.setVisible(false);
        Label path = Ui.label(repo.root().toString(), "status-path");
        Button log = Ui.smallButton("Git Log", Icons.HISTORY, this::showCommandLog);
        log.getStyleClass().add("status-button");
        log.setTooltip(new javafx.scene.control.Tooltip("Show every git command Jit has run"));
        Button term = Ui.smallButton("Terminal", Icons.TERMINAL, () -> Ui.openTerminal(repo.root()));
        term.getStyleClass().add("status-button");
        statusText.setOnMouseClicked(e -> showCommandLog());
        HBox bar = new HBox(8, spinner, statusText, Ui.spacer(), path, log, term);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void fillBranchMenu() {
        branchButton.getItems().clear();
        for (Ref r : refs) {
            if (r.kind == Ref.Kind.LOCAL) {
                MenuItem mi = Ui.item(r.name, () -> checkout(r));
                mi.setGraphic(Icons.icon(r.head ? Icons.CHECK : Icons.BRANCH, 13, r.head ? "accent" : null));
                mi.setDisable(r.head);
                branchButton.getItems().add(mi);
            }
        }
        if (!branchButton.getItems().isEmpty()) {
            branchButton.getItems().add(new SeparatorMenuItem());
        }
        branchButton.getItems().add(Ui.item("New Branch…", Icons.PLUS,
                () -> createBranch("HEAD", headBranch == null ? "HEAD" : headBranch)));
    }

    private void showView(javafx.scene.Node view) {
        for (javafx.scene.Node n : content.getChildren()) {
            n.setVisible(n == view);
        }
    }

    // ------------------------------------------------------------------ accessors used by child views

    public Repo repo() {
        return repo;
    }

    public String headHash() {
        return headHash;
    }

    public Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    public void status(String text) {
        statusText.setText(text);
    }

    public void showLocalChanges() {
        showView(changes);
        sidebar.selectLocalChanges();
        changes.selectFirstIfNone();
    }

    public void showHistory() {
        history.clearWorkingTreeSelection();
        showView(history);
        sidebar.selectAllCommits();
    }

    public void focusSearch() {
        showHistory();
        history.focusSearch();
    }

    public void focusCommitMessage() {
        showLocalChanges();
        changes.focusMessage();
    }

    public void selectCommit(String hash) {
        history.clearWorkingTreeSelection();
        showView(history);
        if (!history.select(hash)) {
            status("Commit " + hash.substring(0, Math.min(10, hash.length())) + " is not loaded in the history");
        }
    }

    public void showFileHistory(String path) {
        new FileHistoryWindow(this, path).show();
    }

    public void showBlame(String path) {
        new BlameWindow(this, path).show();
    }

    /** Runs a read-only git query in the background and hands the result to the UI thread. */
    public <T> void read(Callable<T> task, Consumer<T> onSuccess) {
        READERS.submit(() -> {
            try {
                T result = task.call();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> status("Error: " + firstLine(e.getMessage())));
            }
        });
    }

    /** Like {@link #read(Callable, Consumer)} but reports failures in an error dialog. */
    public <T> void read(Callable<T> task, Consumer<T> onSuccess, String errorTitle) {
        READERS.submit(() -> {
            try {
                T result = task.call();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                Platform.runLater(() -> Dialogs.error(window(), errorTitle, e.getMessage()));
            }
        });
    }

    public void run(String title, GitOp op) {
        run(title, op, null);
    }

    /** Runs a modifying git operation (serialised per repository) and refreshes afterwards. */
    public void run(String title, GitOp op, Runnable onSuccess) {
        hideUndoBar();
        setBusy(+1);
        status(title + "…");
        writer.submit(() -> {
            try {
                op.run(line -> Platform.runLater(() -> statusText.setText(title + ": " + line)));
                Platform.runLater(() -> {
                    setBusy(-1);
                    status(title + " — done");
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    refresh(false);
                });
            } catch (Exception ex) {
                Repo.State after;
                try {
                    after = repo.state();
                } catch (Exception e) {
                    after = Repo.State.NONE;
                }
                Repo.State finalState = after;
                Platform.runLater(() -> {
                    setBusy(-1);
                    status(title + " failed");
                    refresh(false);
                    String msg = ex.getMessage() == null ? "" : ex.getMessage();
                    if (finalState != Repo.State.NONE) {
                        Dialogs.error(window(), title + " stopped — conflicts need to be resolved", ex.getMessage());
                        showLocalChanges();
                    } else if (msg.contains("Please tell me who you are") || msg.contains("empty ident")) {
                        if (Dialogs.confirm(window(), title + " failed", "Git does not know your name and email yet. "
                                + "Set them now in Preferences → Git?", "Open Preferences")) {
                            PreferencesDialog.show(window(), PreferencesDialog.TAB_GIT);
                        }
                    } else {
                        Dialogs.error(window(), title + " failed", ex.getMessage());
                    }
                });
            }
        });
    }

    private void setBusy(int delta) {
        busy = Math.max(0, busy + delta);
        spinner.setVisible(busy > 0);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "unknown error";
        }
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    public void dispose() {
        writer.shutdown();
    }

    public void showCommandLog() {
        CommandLogWindow.show(repo.root());
    }

    public void showRepositorySettings() {
        PreferencesDialog.showRepositorySettings(window(), repo);
    }

    // ------------------------------------------------------------------ interactive rebase

    /**
     * Plans an interactive rebase. With {@code target == null} the commits after {@code base} are rebased;
     * otherwise {@code target} is pre-set to {@code action} and the rebase starts at its parent.
     */
    public void interactiveRebase(Commit base, Commit target, Repo.RebaseAction action) {
        if (state != Repo.State.NONE) {
            Dialogs.error(window(), "Interactive rebase", state.label + ". Finish or abort it first.");
            return;
        }
        if (headBranch == null) {
            Dialogs.error(window(), "Interactive rebase", "HEAD is detached. Check out a branch first.");
            return;
        }
        String startHead = headHash;
        String branch = headBranch;
        boolean dirty = status.stream().anyMatch(e -> !e.isUntracked());
        read(() -> {
            String from = target != null ? (target.parents.length > 0 ? target.parents[0] : null) : base.hash;
            if (!repo.isAncestorOfHead(target != null ? target.hash : base.hash)) {
                throw new IllegalStateException("The commit is not part of '" + branch + "'.");
            }
            List<Commit> commits = repo.rebaseCommits(from);
            List<String> messages = new ArrayList<>();
            for (Commit c : commits) {
                messages.add(repo.commitMessage(c.hash));
            }
            return new Object[]{from, commits, messages, repo.mergeCount(from)};
        }, data -> {
            String from = (String) data[0];
            @SuppressWarnings("unchecked") List<Commit> commits = (List<Commit>) data[1];
            @SuppressWarnings("unchecked") List<String> messages = (List<String>) data[2];
            int merges = (Integer) data[3];
            if (commits.isEmpty()) {
                Dialogs.info(window(), "Interactive rebase", "There are no commits to rebase after this one.");
                return;
            }
            String title = "Rebase " + commits.size() + (commits.size() == 1 ? " commit" : " commits") + " of '"
                    + branch + "' onto " + (from == null ? "the root" : from.substring(0, 7));
            InteractiveRebaseDialog.show(window(), title, commits, messages, target == null ? null : target.hash,
                    action, dirty, merges).ifPresent(plan ->
                        run("Interactive rebase", p -> repo.interactiveRebase(from, plan.steps(), plan.autostash()),
                                () -> showUndoBar("Rebase of '" + branch + "' completed.", startHead)));
        }, "Interactive rebase");
    }

    public void abortOperation() {
        if (state == Repo.State.NONE) {
            Dialogs.info(window(), "Abort", "No merge, rebase, cherry-pick or revert is in progress.");
        } else {
            continueOrAbort(true);
        }
    }

    /** Pushes the current branch to its upstream (or asks where to push when it has none). */
    public void pushCurrentBranch() {
        Ref head = headBranch == null ? null : localRef(headBranch);
        if (head == null) {
            return;
        }
        if (head.upstream != null && !head.upstreamGone) {
            String remote = head.upstream.substring(0, head.upstream.indexOf('/'));
            String target = remoteBranchName(headBranch);
            String branch = headBranch;
            run("Push " + branch, p -> repo.push(remote, branch, target, false, false, false, p));
        } else {
            push(headBranch);
        }
    }

    // ------------------------------------------------------------------ hosting

    /** Hosting service of the default remote, or {@code null}. */
    public HostedRepo hosted() {
        String r = defaultRemote();
        return r == null ? null : HostedRepo.parse(remotes.get(r));
    }

    private HostedRepo hosted(String remote) {
        return HostedRepo.parse(remotes.get(remote));
    }

    /** Name of a local branch on its remote (the upstream's name when it has one). */
    private String remoteBranchName(String local) {
        Ref r = localRef(local);
        if (r != null && r.upstream != null) {
            int slash = r.upstream.indexOf('/');
            return slash < 0 ? r.upstream : r.upstream.substring(slash + 1);
        }
        return local;
    }

    private String guessDefaultBranch(String remote) {
        List<String> branches = remoteBranchesOf(remote);
        for (String candidate : List.of("main", "master", "develop", "trunk")) {
            if (branches.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Opens the hosting service's page for a new pull / merge request from {@code localBranch}. */
    public void createPullRequest(String localBranch) {
        HostedRepo h = hosted();
        String remote = defaultRemote();
        if (h == null || remote == null) {
            Dialogs.info(window(), "Pull Request",
                    "The default remote is not on GitHub, GitLab, Bitbucket or Azure DevOps.");
            return;
        }
        if (localBranch == null) {
            Dialogs.error(window(), "Pull Request", "HEAD is detached. Check out a branch first.");
            return;
        }
        Ref local = localRef(localBranch);
        String source = remoteBranchName(localBranch);
        Dialogs.Form f = new Dialogs.Form();
        f.add("Source", Ui.label(source, "form-value"));
        ComboBox<String> target = new ComboBox<>();
        target.setEditable(true);
        target.getItems().addAll(remoteBranchesOf(remote));
        target.getItems().remove(source);
        target.setValue(guessDefaultBranch(remote));
        target.setMaxWidth(Double.MAX_VALUE);
        f.add("Target", target);
        boolean pushed = local != null && local.upstream != null && !local.upstreamGone;
        CheckBox push = f.check("Push '" + localBranch + "' to " + remote + " first",
                !pushed || local.ahead > 0);
        if (!f.show(window(), "Create " + h.provider.review,
                "Create a " + h.provider.review + " on " + h.provider.title, "Open in Browser", null)) {
            return;
        }
        String typed = target.getEditor().getText();
        String t = typed == null || typed.isBlank() ? null : typed.strip();
        String url = h.newPullRequestUrl(source, t);
        if (push.isSelected()) {
            run("Push " + localBranch, p -> repo.push(remote, localBranch, source, !pushed, false, false, p),
                    () -> Ui.openUrl(url));
        } else {
            Ui.openUrl(url);
        }
    }

    // ------------------------------------------------------------------ external diff

    private boolean ensureDiffTool() {
        if (ExternalDiffTool.isConfigured()) {
            return true;
        }
        if (Dialogs.confirm(window(), "External diff tool", "No external diff tool is configured yet. "
                + "Choose one now (WinMerge, Beyond Compare, VS Code, …)?", "Configure…")) {
            PreferencesDialog.show(window(), PreferencesDialog.TAB_DIFF);
        }
        return ExternalDiffTool.isConfigured();
    }

    private static String fileName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static Path tempDiffDir() throws IOException {
        Path dir = Files.createTempDirectory("jit-diff");
        dir.toFile().deleteOnExit();
        return dir;
    }

    private static Path tempFile(Path dir, String side, String name) throws IOException {
        Path p = dir.resolve(side).resolve(name);
        Files.createDirectories(p.getParent());
        p.getParent().toFile().deleteOnExit();
        p.toFile().deleteOnExit();
        return p;
    }

    private static Path emptyFile(Path dir, String side, String name) throws IOException {
        Path p = tempFile(dir, side, name);
        Files.write(p, new byte[0]);
        return p;
    }

    private void launchDiff(String title, Callable<Path[]> prepare, String leftTitle, String rightTitle) {
        if (!ensureDiffTool()) {
            return;
        }
        status("Opening " + title + " in " + ExternalDiffTool.toolName() + "…");
        READERS.submit(() -> {
            try {
                Path[] files = prepare.call();
                ExternalDiffTool.launch(files[0], files[1], leftTitle, rightTitle);
                Platform.runLater(() -> status("Opened " + title + " in the external diff tool"));
            } catch (Exception e) {
                Platform.runLater(() -> Dialogs.error(window(), "Cannot open external diff tool",
                        e.getMessage() + "\n\nCheck the diff tool in Preferences."));
            }
        });
    }

    /** Compares a file of a commit with its first parent in the external tool. */
    public void externalDiff(Commit c, FileChange f) {
        String name = fileName(f.path());
        String parent = c.parents.length > 0 ? c.parents[0] : null;
        String parentLabel = parent == null ? "empty" : parent.substring(0, 7);
        String oldPath = f.oldPath() != null ? f.oldPath() : f.path();
        launchDiff(name, () -> {
            Path dir = tempDiffDir();
            Path left = parent == null ? emptyFile(dir, "empty", name) : tempFile(dir, parentLabel, name);
            if (parent != null) {
                repo.exportRevision(parent, oldPath, left);
            }
            Path right = tempFile(dir, c.shortHash(), name);
            repo.exportRevision(c.hash, f.path(), right);
            return new Path[]{left, right};
        }, name + " (" + parentLabel + ")", name + " (" + c.shortHash() + ")");
    }

    /** Compares a local change in the external tool; for unstaged files the right side is the real file. */
    public void externalDiff(StatusEntry e, boolean staged) {
        String name = fileName(e.path());
        String headPath = e.origPath() != null ? e.origPath() : e.path();
        boolean hasHead = headHash != null;
        launchDiff(name, () -> {
            Path dir = tempDiffDir();
            if (staged) {
                Path left = hasHead ? tempFile(dir, "HEAD", name) : emptyFile(dir, "HEAD", name);
                if (hasHead) {
                    repo.exportRevision("HEAD", headPath, left);
                }
                Path right = tempFile(dir, "index", name);
                repo.exportRevision("", e.path(), right);
                return new Path[]{left, right};
            }
            Path left = e.isUntracked() ? emptyFile(dir, "index", name) : tempFile(dir, "index", name);
            if (!e.isUntracked()) {
                repo.exportRevision("", e.path(), left);
            }
            Path right = repo.root().resolve(e.path());
            if (!Files.exists(right)) {
                right = emptyFile(dir, "deleted", name);
            }
            return new Path[]{left, right};
        }, name + (staged ? " (HEAD)" : " (staged)"), name + (staged ? " (staged)" : " (working tree)"));
    }

    // ------------------------------------------------------------------ custom commands

    /** Runs a user command; the selection arguments may be null when not applicable. */
    public void runCustom(CustomCommands.Command c, Commit commit, Ref ref, String file) {
        Map<String, String> values = new HashMap<>();
        values.put("REPO", repo.root().toString());
        values.put("BRANCH", headBranch == null ? "HEAD" : headBranch);
        String sha = commit != null ? commit.hash : ref != null ? ref.target : headHash;
        values.put("SHA", sha == null ? "" : sha);
        values.put("SHORT_SHA", sha == null ? "" : sha.substring(0, Math.min(7, sha.length())));
        values.put("REF", ref != null ? ref.name : headBranch == null ? "HEAD" : headBranch);
        values.put("FILE", file == null ? "" : file);
        values.put("FILE_ABS", file == null ? "" : repo.root().resolve(file).toString());
        String remote = defaultRemote();
        values.put("REMOTE", remote == null ? "" : remote);
        values.put("REMOTE_URL", remote == null ? "" : remotes.getOrDefault(remote, ""));
        String line = CustomCommands.expand(c.command(), values);
        if (c.confirm() && !Dialogs.confirm(window(), c.name(), "Run this command?\n\n" + line, "Run")) {
            return;
        }
        if (c.inTerminal()) {
            try {
                CustomCommands.executeInTerminal(line, repo.root());
            } catch (Exception ex) {
                Dialogs.error(window(), c.name() + " failed", ex.getMessage());
            }
            return;
        }
        String[] output = new String[1];
        run(c.name(), p -> output[0] = CustomCommands.execute(line, repo.root()), () -> {
            if (c.showOutput()) {
                Dialogs.output(window(), c.name(), line, output[0]);
            }
        });
    }

    private void addCustom(ContextMenu m, CustomCommands.Target target, Commit commit, Ref ref, String file) {
        javafx.scene.control.Menu custom = CustomCommands.menu(target, c -> runCustom(c, commit, ref, file));
        if (custom != null) {
            m.getItems().addAll(new SeparatorMenuItem(), custom);
        }
    }

    /** Context menu for a file of a commit (rev != null) or of the working tree (rev == null). */
    ContextMenu fileMenu(String path, String rev, Runnable externalDiff) {
        ContextMenu m = new ContextMenu();
        if (externalDiff != null) {
            m.getItems().addAll(Ui.item("Open in External Diff Tool", Icons.COPY, externalDiff),
                    new SeparatorMenuItem());
        }
        m.getItems().addAll(
                Ui.item("File History", Icons.HISTORY, () -> showFileHistory(path)),
                Ui.item("Blame", Icons.EDIT, () -> showBlame(path)),
                new SeparatorMenuItem(),
                Ui.item("Open File", Icons.FILE, () -> Ui.openFile(repo.root().resolve(path))),
                Ui.item("Show in Explorer", Icons.FOLDER, () -> Ui.openInExplorer(repo.root().resolve(path))),
                Ui.item("Copy Path", Icons.COPY, () -> Ui.copy(path)));
        HostedRepo h = hosted();
        if (h != null) {
            String at = rev != null ? rev : headBranch != null ? remoteBranchName(headBranch) : headHash;
            if (at != null) {
                m.getItems().add(Ui.item("Open File on " + h.provider.title, Icons.CLOUD,
                        () -> Ui.openUrl(h.fileUrl(at, path))));
            }
        }
        addCustom(m, CustomCommands.Target.FILE, null, null, path);
        return m;
    }

    // ------------------------------------------------------------------ refresh

    public void refresh() {
        refresh(false);
    }

    public void refresh(boolean forceLog) {
        if (refreshing) {
            refreshPending = true;
            pendingForce |= forceLog;
            return;
        }
        refreshing = true;
        String oldSignature = logSignature;
        READERS.submit(() -> {
            try {
                Snapshot s = load(oldSignature, forceLog);
                Platform.runLater(() -> {
                    apply(s);
                    finishRefresh();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    status("Refresh failed: " + firstLine(ex.getMessage()));
                    finishRefresh();
                });
            }
        });
    }

    private void finishRefresh() {
        refreshing = false;
        if (refreshPending) {
            refreshPending = false;
            boolean f = pendingForce;
            pendingForce = false;
            refresh(f);
        }
    }

    private Snapshot load(String oldSignature, boolean force) throws Exception {
        List<Ref> r = repo.refs();
        String hb = repo.headBranch();
        String hh = repo.headHash();
        StringBuilder sig = new StringBuilder().append(hb).append('|').append(hh).append('|');
        for (Ref ref : r) {
            sig.append(ref.fullName).append('=').append(ref.target).append(';');
        }
        String signature = sig.toString();
        List<Commit> commits = null;
        if (force || !signature.equals(oldSignature)) {
            commits = repo.log(MAX_COMMITS);
        }
        List<StatusEntry> st = repo.status();
        List<Stash> sl = repo.stashes();
        Map<String, String> rm = repo.remotes();
        Repo.State state = repo.state();
        String prepared = state != Repo.State.NONE ? repo.preparedMessage() : null;
        return new Snapshot(r, commits, signature, st, sl, rm, hb, hh, state, prepared);
    }

    private void apply(Snapshot s) {
        refs = s.refs();
        headBranch = s.headBranch();
        headHash = s.headHash();
        status = s.status();
        stashes = s.stashes();
        remotes = s.remotes();
        state = s.state();
        boolean logChanged = s.commits() != null;
        if (logChanged) {
            logCommits = s.commits();
            logSignature = s.signature();
        }
        String key = logSignature + "#" + status.size();
        if (logChanged || !key.equals(renderedKey)) {
            renderedKey = key;
            rebuildHistory();
        }
        sidebar.update(refs, remotes, stashes, status.size());
        changes.setStatus(status, state, s.preparedMessage());

        branchButton.setText(headBranch != null ? headBranch
                : headHash != null ? "detached at " + headHash.substring(0, 7) : "no commits yet");
        HostedRepo hostedRepo = hosted();
        hostingButton.setText(hostedRepo == null ? "Remote" : hostedRepo.provider.title);
        if (state != Repo.State.NONE) {
            bannerText.setText(state.label);
            banner.setVisible(true);
            banner.setManaged(true);
        } else {
            banner.setVisible(false);
            banner.setManaged(false);
        }
        if (!loadedOnce) {
            loadedOnce = true;
            history.selectFirstCommit();
            status(logCommits.size() + " commits loaded");
        }
    }

    private void rebuildHistory() {
        List<Commit> list = new ArrayList<>(logCommits.size() + 1);
        if (!status.isEmpty()) {
            list.add(Commit.workingTree(headHash, status.size()));
        }
        list.addAll(logCommits);
        Map<String, List<Ref>> byHash = new HashMap<>();
        for (Ref r : refs) {
            byHash.computeIfAbsent(r.target, k -> new ArrayList<>()).add(r);
        }
        GraphRow[] rows = GraphBuilder.build(list);
        for (int i = 0; i < list.size(); i++) {
            Commit c = list.get(i);
            c.refs = byHash.getOrDefault(c.hash, List.of());
            c.graph = rows[i];
        }
        history.setCommits(list);
    }

    public void refreshCells() {
        history.refreshCells();
    }

    // ------------------------------------------------------------------ sidebar

    private void onSidebarSelect(Sidebar.Item item) {
        switch (item.kind) {
            case LOCAL_CHANGES -> {
                showView(changes);
                changes.selectFirstIfNone();
            }
            case ALL_COMMITS -> {
                history.clearWorkingTreeSelection();
                showView(history);
            }
            case BRANCH, REMOTE_BRANCH, TAG -> selectCommit(item.ref.target);
            case STASH -> {
                showView(stashDetails);
                Stash s = item.stash;
                read(() -> repo.commit(s.hash()), stashDetails::show);
            }
            default -> {
            }
        }
    }

    private void onSidebarOpen(Sidebar.Item item) {
        switch (item.kind) {
            case BRANCH -> {
                if (!item.ref.head) {
                    checkout(item.ref);
                }
            }
            case REMOTE_BRANCH -> checkoutRemote(item.ref);
            case STASH -> applyStash(item.stash, false);
            default -> {
            }
        }
    }

    private ContextMenu sidebarMenu(Sidebar.Item item) {
        return switch (item.kind) {
            case BRANCH -> branchMenu(item.ref);
            case REMOTE_BRANCH -> remoteBranchMenu(item.ref);
            case TAG -> tagMenu(item.ref);
            case STASH -> stashMenu(item.stash);
            case REMOTE -> remoteMenu(item.remote);
            case SECTION -> switch (item.key) {
                case "sec:branches" -> new ContextMenu(Ui.item("New Branch…", Icons.PLUS,
                        () -> createBranch("HEAD", headBranch == null ? "HEAD" : headBranch)));
                case "sec:remotes" -> new ContextMenu(Ui.item("Add Remote…", Icons.PLUS, this::addRemote),
                        Ui.item("Fetch All", Icons.FETCH, () -> run("Fetch", p -> repo.fetch(null, true, false, p))));
                case "sec:stashes" -> new ContextMenu(Ui.item("Stash Changes…", Icons.STASH, this::stash));
                default -> null;
            };
            case LOCAL_CHANGES -> new ContextMenu(Ui.item("Stash Changes…", Icons.STASH, this::stash));
            default -> null;
        };
    }

    private String current() {
        return headBranch == null ? "HEAD" : headBranch;
    }

    private ContextMenu branchMenu(Ref r) {
        ContextMenu m = new ContextMenu();
        MenuItem checkout = Ui.item("Checkout '" + r.name + "'", Icons.CHECK, () -> checkout(r));
        checkout.setDisable(r.head);
        m.getItems().add(checkout);
        if (!r.head) {
            m.getItems().addAll(new SeparatorMenuItem(),
                    Ui.item("Merge '" + r.name + "' into '" + current() + "'…", Icons.MERGE, () -> merge(r.name)),
                    Ui.item("Rebase '" + current() + "' onto '" + r.name + "'…", Icons.BRANCH, () -> rebase(r.name)));
        }
        m.getItems().addAll(new SeparatorMenuItem(),
                Ui.item("Push '" + r.name + "'…", Icons.PUSH, () -> push(r.name)));
        if (r.head) {
            m.getItems().add(Ui.item("Pull…", Icons.PULL, this::pull));
        }
        m.getItems().addAll(new SeparatorMenuItem(),
                Ui.item("New Branch from '" + r.name + "'…", Icons.PLUS, () -> createBranch(r.name, r.name)),
                Ui.item("Create Tag…", Icons.TAG, () -> createTag(r.name, r.name)),
                Ui.item("Rename…", Icons.EDIT, () -> renameBranch(r)));
        MenuItem delete = Ui.item("Delete…", Icons.CLOSE, () -> deleteBranch(r));
        delete.setDisable(r.head);
        m.getItems().addAll(delete, new SeparatorMenuItem(),
                Ui.item("Copy Branch Name", Icons.COPY, () -> Ui.copy(r.name)));
        HostedRepo h = hosted();
        if (h != null) {
            m.getItems().addAll(new SeparatorMenuItem(),
                    Ui.item("Create " + h.provider.review + "…", Icons.MERGE, () -> createPullRequest(r.name)));
            if (r.upstream != null) {
                m.getItems().add(Ui.item("Open Branch on " + h.provider.title, Icons.CLOUD,
                        () -> Ui.openUrl(h.branchUrl(remoteBranchName(r.name)))));
            }
        }
        addCustom(m, CustomCommands.Target.BRANCH, null, r, null);
        return m;
    }

    private ContextMenu remoteBranchMenu(Ref r) {
        ContextMenu m = new ContextMenu(
                Ui.item("Checkout '" + r.name + "'…", Icons.CHECK, () -> checkoutRemote(r)),
                new SeparatorMenuItem(),
                Ui.item("Merge '" + r.name + "' into '" + current() + "'…", Icons.MERGE, () -> merge(r.name)),
                Ui.item("Rebase '" + current() + "' onto '" + r.name + "'…", Icons.BRANCH, () -> rebase(r.name)),
                new SeparatorMenuItem(),
                Ui.item("New Branch from '" + r.name + "'…", Icons.PLUS, () -> createBranch(r.name, r.name)),
                Ui.item("Delete from Remote…", Icons.CLOSE, () -> deleteRemoteBranch(r)),
                new SeparatorMenuItem(),
                Ui.item("Copy Branch Name", Icons.COPY, () -> Ui.copy(r.name)));
        HostedRepo h = hosted(r.remote());
        if (h != null) {
            m.getItems().add(Ui.item("Open Branch on " + h.provider.title, Icons.CLOUD,
                    () -> Ui.openUrl(h.branchUrl(r.remoteBranch()))));
        }
        addCustom(m, CustomCommands.Target.BRANCH, null, r, null);
        return m;
    }

    private ContextMenu tagMenu(Ref r) {
        ContextMenu m = new ContextMenu(
                Ui.item("Checkout Tag (detached HEAD)…", Icons.CHECK, () -> checkoutDetached(r.name, "tag " + r.name)),
                Ui.item("New Branch from Tag…", Icons.PLUS, () -> createBranch(r.name, r.name)),
                Ui.item("Merge into '" + current() + "'…", Icons.MERGE, () -> merge(r.name)),
                new SeparatorMenuItem(),
                Ui.item("Push Tag…", Icons.PUSH, () -> pushTag(r)),
                Ui.item("Delete Tag…", Icons.CLOSE, () -> deleteTag(r)),
                new SeparatorMenuItem(),
                Ui.item("Copy Tag Name", Icons.COPY, () -> Ui.copy(r.name)));
        HostedRepo h = hosted();
        if (h != null) {
            m.getItems().add(Ui.item("Open Tag on " + h.provider.title, Icons.CLOUD,
                    () -> Ui.openUrl(h.branchUrl(r.name))));
        }
        addCustom(m, CustomCommands.Target.BRANCH, null, r, null);
        return m;
    }

    private ContextMenu stashMenu(Stash s) {
        return new ContextMenu(
                Ui.item("Apply Stash", Icons.POP, () -> applyStash(s, false)),
                Ui.item("Pop Stash", Icons.POP, () -> applyStash(s, true)),
                new SeparatorMenuItem(),
                Ui.item("Delete Stash…", Icons.CLOSE, () -> dropStash(s)),
                Ui.item("Copy Message", Icons.COPY, () -> Ui.copy(s.message())));
    }

    private ContextMenu remoteMenu(String remote) {
        ContextMenu m = new ContextMenu(
                Ui.item("Fetch '" + remote + "'", Icons.FETCH,
                        () -> run("Fetch " + remote, p -> repo.fetch(remote, true, false, p))),
                new SeparatorMenuItem(),
                Ui.item("Edit URL…", Icons.EDIT, () -> editRemote(remote)),
                Ui.item("Remove Remote…", Icons.CLOSE, () -> removeRemote(remote)),
                new SeparatorMenuItem(),
                Ui.item("Copy URL", Icons.COPY, () -> Ui.copy(remotes.getOrDefault(remote, ""))),
                Ui.item("Add Remote…", Icons.PLUS, this::addRemote));
        HostedRepo h = hosted(remote);
        if (h != null) {
            m.getItems().addAll(new SeparatorMenuItem(),
                    Ui.item("Open on " + h.provider.title, Icons.CLOUD, () -> Ui.openUrl(h.repositoryUrl())),
                    Ui.item(h.provider.reviews, Icons.MERGE, () -> Ui.openUrl(h.pullRequestsUrl())));
        }
        return m;
    }

    /** Context menu for a commit in the history table. */
    public ContextMenu commitMenu(Commit c) {
        ContextMenu m = new ContextMenu();
        for (Ref r : c.refs) {
            if (r.kind == Ref.Kind.LOCAL && !r.head) {
                m.getItems().add(Ui.item("Checkout '" + r.name + "'", Icons.CHECK, () -> checkout(r)));
            }
        }
        m.getItems().addAll(
                Ui.item("Checkout Commit (detached HEAD)…", Icons.COMMIT,
                        () -> checkoutDetached(c.hash, "commit " + c.shortHash())),
                Ui.item("New Branch Here…", Icons.PLUS, () -> createBranch(c.hash, c.shortHash())),
                Ui.item("New Tag Here…", Icons.TAG, () -> createTag(c.hash, c.shortHash())),
                new SeparatorMenuItem());
        boolean isHead = c.hash.equals(headHash);
        if (!isHead) {
            m.getItems().addAll(
                    Ui.item("Merge into '" + current() + "'…", Icons.MERGE, () -> merge(c.hash)),
                    Ui.item("Rebase '" + current() + "' onto Here…", Icons.BRANCH, () -> rebase(c.hash)),
                    Ui.item("Cherry-pick Commit…", Icons.COMMIT, () -> cherryPick(c)));
        }
        m.getItems().add(new SeparatorMenuItem());
        javafx.scene.control.Menu edit = new javafx.scene.control.Menu("Rewrite History");
        edit.setGraphic(Icons.icon(Icons.EDIT, 14));
        edit.getItems().addAll(
                Ui.item("Interactive Rebase from Here…", Icons.LIST, () -> interactiveRebase(c, null, null)),
                new SeparatorMenuItem(),
                Ui.item("Reword Commit…", Icons.EDIT, () -> interactiveRebase(c, c, Repo.RebaseAction.REWORD)),
                Ui.item("Edit Commit (stop to amend)…", Icons.EDIT, () -> interactiveRebase(c, c, Repo.RebaseAction.EDIT)),
                Ui.item("Squash into Parent…", Icons.MERGE, () -> interactiveRebase(c, c, Repo.RebaseAction.SQUASH)),
                Ui.item("Fixup into Parent…", Icons.MERGE, () -> interactiveRebase(c, c, Repo.RebaseAction.FIXUP)),
                Ui.item("Drop Commit…", Icons.CLOSE, () -> interactiveRebase(c, c, Repo.RebaseAction.DROP)));
        m.getItems().add(edit);
        m.getItems().addAll(
                Ui.item("Revert Commit…", Icons.UNDO, () -> revert(c)),
                new SeparatorMenuItem(),
                Ui.item("Reset '" + current() + "' to Here…", Icons.REFRESH, () -> reset(c)),
                new SeparatorMenuItem(),
                Ui.item("Copy SHA", Icons.COPY, () -> Ui.copy(c.hash)),
                Ui.item("Copy Message", Icons.COPY, () -> read(() -> repo.commitMessage(c.hash), Ui::copy)));
        HostedRepo h = hosted();
        if (h != null) {
            m.getItems().add(Ui.item("Open Commit on " + h.provider.title, Icons.CLOUD,
                    () -> Ui.openUrl(h.commitUrl(c.hash))));
        }
        addCustom(m, CustomCommands.Target.COMMIT, c, null, null);
        return m;
    }

    // ------------------------------------------------------------------ operations

    private List<String> localBranches() {
        List<String> list = new ArrayList<>();
        for (Ref r : refs) {
            if (r.kind == Ref.Kind.LOCAL) {
                list.add(r.name);
            }
        }
        return list;
    }

    private Ref localRef(String name) {
        for (Ref r : refs) {
            if (r.kind == Ref.Kind.LOCAL && r.name.equals(name)) {
                return r;
            }
        }
        return null;
    }

    private List<String> remoteBranchesOf(String remote) {
        List<String> list = new ArrayList<>();
        for (Ref r : refs) {
            if (r.kind == Ref.Kind.REMOTE && r.remote().equals(remote)) {
                list.add(r.remoteBranch());
            }
        }
        return list;
    }

    private String defaultRemote() {
        Ref head = headBranch == null ? null : localRef(headBranch);
        if (head != null && head.upstream != null) {
            for (String r : remotes.keySet()) {
                if (head.upstream.startsWith(r + "/")) {
                    return r;
                }
            }
        }
        if (remotes.containsKey("origin")) {
            return "origin";
        }
        return remotes.isEmpty() ? null : remotes.keySet().iterator().next();
    }

    private static boolean validRefName(String s) {
        return s != null && !s.isBlank() && !s.contains(" ") && !s.contains("..") && !s.endsWith("/")
                && !s.startsWith("-") && !s.contains("~") && !s.contains("^") && !s.contains(":");
    }

    public void fetch() {
        Dialogs.Form f = new Dialogs.Form();
        ComboBox<String> remote = new ComboBox<>();
        remote.getItems().add("All remotes");
        remote.getItems().addAll(remotes.keySet());
        remote.getSelectionModel().select(0);
        remote.setMaxWidth(Double.MAX_VALUE);
        f.add("Remote", remote);
        CheckBox prune = f.check("Prune remote branches that no longer exist", true);
        CheckBox tags = f.check("Fetch all tags", false);
        if (remotes.isEmpty()) {
            Dialogs.info(window(), "Fetch", "This repository has no remotes. Add one from the Remotes section.");
            return;
        }
        if (f.show(window(), "Fetch", "Fetch from remote", "Fetch", null)) {
            String r = remote.getSelectionModel().getSelectedIndex() == 0 ? null : remote.getValue();
            boolean p = prune.isSelected();
            boolean t = tags.isSelected();
            run("Fetch", pr -> repo.fetch(r, p, t, pr));
        }
    }

    public void pull() {
        if (headBranch == null) {
            Dialogs.error(window(), "Pull", "HEAD is detached. Check out a branch before pulling.");
            return;
        }
        if (remotes.isEmpty()) {
            Dialogs.info(window(), "Pull", "This repository has no remotes. Add one from the Remotes section.");
            return;
        }
        Ref head = localRef(headBranch);
        Dialogs.Form f = new Dialogs.Form();
        ComboBox<String> remote = new ComboBox<>();
        remote.getItems().addAll(remotes.keySet());
        remote.setMaxWidth(Double.MAX_VALUE);
        f.add("Remote", remote);
        ComboBox<String> branch = new ComboBox<>();
        branch.setEditable(true);
        branch.setMaxWidth(Double.MAX_VALUE);
        f.add("Branch", branch);
        remote.valueProperty().addListener((obs, o, n) -> {
            if (n != null) {
                branch.getItems().setAll(remoteBranchesOf(n));
            }
        });
        String dr = defaultRemote();
        remote.setValue(dr);
        String upstreamBranch = head != null && head.upstream != null && dr != null && head.upstream.startsWith(dr + "/")
                ? head.upstream.substring(dr.length() + 1) : headBranch;
        branch.setValue(upstreamBranch);
        f.add("Into", Ui.label(headBranch, "form-value"));
        CheckBox rebase = f.check("Rebase instead of merge", false);
        CheckBox autostash = f.check("Stash and reapply local changes", true);
        if (f.show(window(), "Pull", "Pull into '" + headBranch + "'", "Pull", remote.valueProperty().isNotNull())) {
            String r = remote.getValue();
            String b = branch.getEditor().getText().isBlank() ? null : branch.getEditor().getText().strip();
            boolean rb = rebase.isSelected();
            boolean as = autostash.isSelected();
            run("Pull", p -> repo.pull(r, b, rb, as, p));
        }
    }

    public void push() {
        push(headBranch);
    }

    public void push(String localBranch) {
        if (remotes.isEmpty()) {
            if (Dialogs.confirm(window(), "Push", "This repository has no remote. Add one now?", "Add Remote…")) {
                addRemote();
            }
            return;
        }
        if (localBranch == null) {
            Dialogs.error(window(), "Push", "HEAD is detached. Check out a branch before pushing.");
            return;
        }
        Dialogs.Form f = new Dialogs.Form();
        ComboBox<String> local = new ComboBox<>();
        local.getItems().addAll(localBranches());
        local.setValue(localBranch);
        local.setMaxWidth(Double.MAX_VALUE);
        f.add("Branch", local);
        ComboBox<String> remote = new ComboBox<>();
        remote.getItems().addAll(remotes.keySet());
        remote.setMaxWidth(Double.MAX_VALUE);
        f.add("To remote", remote);
        TextField remoteBranch = f.text("Remote branch", localBranch);
        CheckBox upstream = f.check("Track this remote branch (set upstream)", true);
        CheckBox force = f.check("Force push (with lease)", false);
        CheckBox tags = f.check("Push annotated tags", false);
        Runnable sync = () -> {
            Ref r = localRef(local.getValue());
            String dr = defaultRemote();
            if (r != null && r.upstream != null) {
                for (String rn : remotes.keySet()) {
                    if (r.upstream.startsWith(rn + "/")) {
                        remote.setValue(rn);
                        remoteBranch.setText(r.upstream.substring(rn.length() + 1));
                        upstream.setSelected(false);
                        return;
                    }
                }
            }
            remote.setValue(dr);
            remoteBranch.setText(local.getValue());
            upstream.setSelected(true);
        };
        local.valueProperty().addListener((obs, o, n) -> sync.run());
        sync.run();
        if (f.show(window(), "Push", "Push branch to remote", "Push",
                remote.valueProperty().isNotNull().and(remoteBranch.textProperty().isNotEmpty()))) {
            String l = local.getValue();
            String r = remote.getValue();
            String rb = remoteBranch.getText().strip();
            boolean u = upstream.isSelected();
            boolean fo = force.isSelected();
            boolean t = tags.isSelected();
            run("Push", p -> repo.push(r, l, rb, u, fo, t, p));
        }
    }

    public void stash() {
        if (status.isEmpty()) {
            Dialogs.info(window(), "Stash", "There are no local changes to stash.");
            return;
        }
        Dialogs.Form f = new Dialogs.Form();
        TextField msg = f.text("Message", "");
        msg.setPromptText("Optional description");
        CheckBox untracked = f.check("Include untracked files", true);
        CheckBox keepIndex = f.check("Keep staged changes", false);
        if (f.show(window(), "Stash", "Stash local changes", "Stash", null)) {
            String m = msg.getText();
            boolean u = untracked.isSelected();
            boolean k = keepIndex.isSelected();
            run("Stash", p -> repo.stash(m, u, k));
        }
    }

    public void popLatestStash() {
        if (stashes.isEmpty()) {
            Dialogs.info(window(), "Pop stash", "There are no stashes.");
            return;
        }
        Stash s = stashes.get(0);
        run("Pop stash", p -> repo.stashApply(s, true));
    }

    private void applyStash(Stash s, boolean pop) {
        run(pop ? "Pop stash" : "Apply stash", p -> repo.stashApply(s, pop));
    }

    private void dropStash(Stash s) {
        if (Dialogs.confirm(window(), "Delete stash", "Delete stash '" + s.message() + "'? This cannot be undone.",
                "Delete")) {
            run("Delete stash", p -> repo.stashDrop(s));
        }
    }

    public void createBranch(String startRev, String startLabel) {
        if (headHash == null) {
            Dialogs.info(window(), "New branch", "Create a first commit before creating branches.");
            return;
        }
        Dialogs.Form f = new Dialogs.Form();
        TextField name = f.text("Name", "");
        name.setPromptText("feature/my-branch");
        f.add("Starting at", Ui.label(startLabel, "form-value"));
        CheckBox checkout = f.check("Check out after create", true);
        Platform.runLater(name::requestFocus);
        if (f.show(window(), "New Branch", "Create a new branch", "Create Branch",
                javafx.beans.binding.Bindings.createBooleanBinding(() -> validRefName(name.getText()),
                        name.textProperty()))) {
            String n = name.getText().strip();
            boolean co = checkout.isSelected();
            run("Create branch " + n, p -> repo.createBranch(n, startRev, co));
        }
    }

    public void createTag(String rev, String label) {
        if (headHash == null) {
            Dialogs.info(window(), "New tag", "Create a first commit before creating tags.");
            return;
        }
        Dialogs.Form f = new Dialogs.Form();
        TextField name = f.text("Name", "");
        name.setPromptText("v1.0.0");
        f.add("Commit", Ui.label(label, "form-value"));
        TextField message = f.text("Message", "");
        message.setPromptText("Optional — creates an annotated tag");
        CheckBox pushIt = f.check("Push to " + (defaultRemote() == null ? "remote" : defaultRemote()), false);
        pushIt.setDisable(defaultRemote() == null);
        Platform.runLater(name::requestFocus);
        if (f.show(window(), "New Tag", "Create a new tag", "Create Tag",
                javafx.beans.binding.Bindings.createBooleanBinding(() -> validRefName(name.getText()),
                        name.textProperty()))) {
            String n = name.getText().strip();
            String m = message.getText();
            boolean ps = pushIt.isSelected();
            String remote = defaultRemote();
            run("Create tag " + n, p -> {
                repo.createTag(n, rev, m);
                if (ps && remote != null) {
                    repo.pushTag(remote, n, p);
                }
            });
        }
    }

    private void checkout(Ref r) {
        run("Checkout " + r.name, p -> repo.checkout(r.name));
    }

    private void checkoutRemote(Ref r) {
        String local = r.remoteBranch();
        Ref existing = localRef(local);
        if (existing != null && r.name.equals(existing.upstream)) {
            if (!existing.head) {
                checkout(existing);
            }
            return;
        }
        Dialogs.Form f = new Dialogs.Form();
        TextField name = f.text("Local branch", existing == null ? local : "");
        f.add("Tracking", Ui.label(r.name, "form-value"));
        if (existing != null) {
            f.note("A local branch named '" + local + "' already exists. Choose another name.");
        }
        if (f.show(window(), "Checkout Remote Branch", "Check out '" + r.name + "'", "Checkout",
                javafx.beans.binding.Bindings.createBooleanBinding(
                        () -> validRefName(name.getText()) && localRef(name.getText().strip()) == null,
                        name.textProperty()))) {
            String n = name.getText().strip();
            run("Checkout " + r.name, p -> repo.checkoutTracking(r, n));
        }
    }

    private void checkoutDetached(String rev, String label) {
        if (Dialogs.confirm(window(), "Checkout " + label,
                "This will check out " + label + " in a detached HEAD state. New commits will not belong to any "
                        + "branch unless you create one.", "Checkout")) {
            run("Checkout " + label, p -> repo.checkoutDetached(rev));
        }
    }

    private void merge(String rev) {
        String label = rev.length() == 40 ? rev.substring(0, 7) : rev;
        Dialogs.Form f = new Dialogs.Form();
        f.note("Merge '" + label + "' into '" + current() + "'.");
        CheckBox noFf = f.check("Create a merge commit even if fast-forward is possible (--no-ff)", false);
        CheckBox squash = f.check("Squash commits (--squash)", false);
        noFf.disableProperty().bind(squash.selectedProperty());
        if (f.show(window(), "Merge", "Merge into '" + current() + "'", "Merge", null)) {
            boolean n = noFf.isSelected();
            boolean s = squash.isSelected();
            run("Merge " + label, p -> repo.merge(rev, n, s));
        }
    }

    private void rebase(String rev) {
        String label = rev.length() == 40 ? rev.substring(0, 7) : rev;
        if (Dialogs.confirm(window(), "Rebase", "Rebase '" + current() + "' onto '" + label
                + "'? Commits of '" + current() + "' will be rewritten.", "Rebase")) {
            run("Rebase onto " + label, p -> repo.rebase(rev));
        }
    }

    private void cherryPick(Commit c) {
        if (Dialogs.confirm(window(), "Cherry-pick", "Apply the changes of " + c.shortHash() + " \"" + c.subject
                + "\" on top of '" + current() + "'?", "Cherry-pick")) {
            run("Cherry-pick " + c.shortHash(), p -> repo.cherryPick(c));
        }
    }

    private void revert(Commit c) {
        if (Dialogs.confirm(window(), "Revert", "Create a new commit that reverts " + c.shortHash() + " \""
                + c.subject + "\"?", "Revert")) {
            run("Revert " + c.shortHash(), p -> repo.revert(c));
        }
    }

    private void reset(Commit c) {
        Dialogs.Form f = new Dialogs.Form();
        f.note("Reset '" + current() + "' to " + c.shortHash() + " \"" + c.subject + "\".");
        ToggleGroup group = new ToggleGroup();
        RadioButton soft = new RadioButton("Soft — keep all changes staged");
        RadioButton mixed = new RadioButton("Mixed — keep changes in the working tree, unstaged");
        RadioButton hard = new RadioButton("Hard — discard all local changes");
        soft.setToggleGroup(group);
        mixed.setToggleGroup(group);
        hard.setToggleGroup(group);
        mixed.setSelected(true);
        hard.getStyleClass().add("danger-text");
        f.add("Mode", new VBox(8, soft, mixed, hard));
        if (f.show(window(), "Reset", "Reset current branch", "Reset", null)) {
            Repo.ResetMode mode = soft.isSelected() ? Repo.ResetMode.SOFT
                    : hard.isSelected() ? Repo.ResetMode.HARD : Repo.ResetMode.MIXED;
            if (mode == Repo.ResetMode.HARD && !status.isEmpty() && !Dialogs.confirm(window(), "Hard reset",
                    "All uncommitted changes will be lost. Continue?", "Reset")) {
                return;
            }
            run("Reset to " + c.shortHash(), p -> repo.reset(c.hash, mode));
        }
    }

    private void renameBranch(Ref r) {
        Dialogs.input(window(), "Rename Branch", "New name", r.name, "Rename")
                .filter(n -> !n.equals(r.name))
                .ifPresent(n -> run("Rename " + r.name, p -> repo.renameBranch(r.name, n)));
    }

    private void deleteBranch(Ref r) {
        Dialogs.Form f = new Dialogs.Form();
        f.note("Delete local branch '" + r.name + "'?");
        CheckBox force = f.check("Force delete even if not fully merged", false);
        Ref upstream = null;
        if (r.upstream != null) {
            for (Ref o : refs) {
                if (o.kind == Ref.Kind.REMOTE && o.name.equals(r.upstream)) {
                    upstream = o;
                }
            }
        }
        CheckBox remoteToo = upstream == null ? null : f.check("Also delete '" + upstream.name + "' on the remote", false);
        if (f.show(window(), "Delete Branch", "Delete branch", "Delete", null)) {
            boolean fo = force.isSelected();
            Ref up = upstream;
            boolean rt = remoteToo != null && remoteToo.isSelected();
            run("Delete " + r.name, p -> {
                repo.deleteBranch(r.name, fo);
                if (rt) {
                    repo.deleteRemoteBranch(up, p);
                }
            });
        }
    }

    private void deleteRemoteBranch(Ref r) {
        if (Dialogs.confirm(window(), "Delete Remote Branch", "Delete branch '" + r.remoteBranch() + "' from remote '"
                + r.remote() + "'? This affects everyone using the remote.", "Delete")) {
            run("Delete " + r.name, p -> repo.deleteRemoteBranch(r, p));
        }
    }

    private void pushTag(Ref tag) {
        Dialogs.Form f = new Dialogs.Form();
        ComboBox<String> remote = new ComboBox<>();
        remote.getItems().addAll(remotes.keySet());
        remote.setValue(defaultRemote());
        remote.setMaxWidth(Double.MAX_VALUE);
        f.add("Remote", remote);
        if (f.show(window(), "Push Tag", "Push tag '" + tag.name + "'", "Push", remote.valueProperty().isNotNull())) {
            String r = remote.getValue();
            run("Push tag " + tag.name, p -> repo.pushTag(r, tag.name, p));
        }
    }

    private void deleteTag(Ref tag) {
        Dialogs.Form f = new Dialogs.Form();
        f.note("Delete tag '" + tag.name + "'?");
        String remote = defaultRemote();
        CheckBox remoteToo = remote == null ? null : f.check("Also delete it from '" + remote + "'", false);
        if (f.show(window(), "Delete Tag", "Delete tag", "Delete", null)) {
            boolean rt = remoteToo != null && remoteToo.isSelected();
            run("Delete tag " + tag.name, p -> {
                repo.deleteTag(tag.name);
                if (rt) {
                    repo.deleteRemoteTag(remote, tag.name, p);
                }
            });
        }
    }

    public void addRemote() {
        Dialogs.Form f = new Dialogs.Form();
        TextField name = f.text("Name", remotes.containsKey("origin") ? "" : "origin");
        TextField url = f.text("URL", "");
        url.setPromptText("https://github.com/user/repo.git");
        if (f.show(window(), "Add Remote", "Add a remote repository", "Add",
                name.textProperty().isNotEmpty().and(url.textProperty().isNotEmpty()))) {
            String n = name.getText().strip();
            String u = url.getText().strip();
            run("Add remote " + n, p -> {
                repo.addRemote(n, u);
                repo.fetch(n, false, false, p);
            });
        }
    }

    private void editRemote(String remote) {
        Dialogs.input(window(), "Edit Remote", "URL", remotes.getOrDefault(remote, ""), "Save")
                .ifPresent(u -> run("Edit remote " + remote, p -> repo.setRemoteUrl(remote, u)));
    }

    private void removeRemote(String remote) {
        if (Dialogs.confirm(window(), "Remove Remote", "Remove remote '" + remote
                + "' and its remote-tracking branches?", "Remove")) {
            run("Remove remote " + remote, p -> repo.removeRemote(remote));
        }
    }

    private void continueOrAbort(boolean abort) {
        Repo.State st = state;
        if (abort && !Dialogs.confirm(window(), "Abort", "Abort the " + st.label.toLowerCase()
                + " and restore the previous state?", "Abort")) {
            return;
        }
        run((abort ? "Abort " : "Continue ") + st.label.toLowerCase().replace(" in progress", ""),
                p -> repo.continueOrAbort(st, abort));
    }

    void ignore(String path) {
        run("Ignore " + path, p -> {
            Path gi = repo.root().resolve(".gitignore");
            String existing = Files.exists(gi) ? Files.readString(gi, StandardCharsets.UTF_8) : "";
            String line = "/" + path + System.lineSeparator();
            if (!existing.isEmpty() && !existing.endsWith("\n")) {
                line = System.lineSeparator() + line;
            }
            try {
                Files.writeString(gi, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IOException("Cannot update .gitignore: " + e.getMessage(), e);
            }
        });
    }
}
