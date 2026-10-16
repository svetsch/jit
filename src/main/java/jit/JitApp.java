package jit;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import jit.git.Git;
import jit.git.GitException;
import jit.git.Repo;
import jit.ui.AboutDialog;
import jit.ui.AppIcon;
import jit.ui.CommandLogWindow;
import jit.ui.Dialogs;
import jit.ui.Icons;
import jit.ui.PreferencesDialog;
import jit.ui.RepositoryView;
import jit.ui.Settings;
import jit.ui.Theme;
import jit.ui.Ui;
import jit.ui.WelcomeView;

/** Main window: menu bar and one tab per repository. */
public final class JitApp extends Application {
    private Stage stage;
    private Scene scene;
    private final TabPane tabs = new TabPane();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        Ui.setHostServices(getHostServices());
        String gitExe = Settings.get("gitExecutable", "");
        if (!gitExe.isBlank()) {
            Git.setExecutable(gitExe);
        }
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        tabs.getStyleClass().add("repo-tabs");

        Button newTab = new Button(null, Icons.icon(Icons.PLUS, 14));
        newTab.getStyleClass().add("new-tab-button");
        newTab.setTooltip(new Tooltip("New tab (Ctrl+T)"));
        newTab.setFocusTraversable(false);
        newTab.setOnAction(e -> newTab());
        StackPane tabArea = new StackPane(tabs, newTab);
        StackPane.setAlignment(newTab, Pos.TOP_RIGHT);
        StackPane.setMargin(newTab, new Insets(4, 8, 0, 0));

        BorderPane root = new BorderPane(tabArea);
        root.setTop(buildMenu());
        root.getStyleClass().add("app-root");

        scene = new Scene(root, Settings.getDouble("width", 1400), Settings.getDouble("height", 880));
        // drop repository folders onto the window to open them in new tabs
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.LINK, javafx.scene.input.TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            boolean done = false;
            for (File f : e.getDragboard().getFiles()) {
                String n = f.getName().toLowerCase();
                RepositoryView current = currentRepo();
                if (f.isFile() && current != null && (n.endsWith(".patch") || n.endsWith(".diff"))) {
                    Platform.runLater(() -> current.applyPatch(f.toPath()));
                    done = true;
                    continue;
                }
                Path p = f.isDirectory() ? f.toPath() : f.toPath().getParent();
                if (p != null) {
                    openRepository(p, true);
                    done = true;
                }
            }
            e.setDropCompleted(done);
            e.consume();
        });
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("Jit");
        stage.getIcons().setAll(AppIcon.images());
        if (Settings.getDouble("maximized", 0) > 0) {
            stage.setMaximized(true);
        }

        tabs.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            updateTitle();
            RepositoryView rv = currentRepo();
            if (rv != null && o != null) {
                rv.refresh();
            }
        });
        stage.focusedProperty().addListener((obs, o, focused) -> {
            RepositoryView rv = currentRepo();
            if (focused && rv != null) {
                rv.refresh();
            }
        });
        stage.setOnCloseRequest(e -> saveState());

        for (String p : Settings.openTabs()) {
            if (Files.isDirectory(Path.of(p))) {
                openRepository(Path.of(p), false);
            }
        }
        for (String arg : getParameters().getRaw()) {
            Path p = Path.of(arg).toAbsolutePath();
            if (Files.isDirectory(p)) {
                openRepository(p, true);
            }
        }
        if (tabs.getTabs().isEmpty()) {
            newTab();
        }
        String selected = Settings.get("selectedTab", "");
        for (Tab t : tabs.getTabs()) {
            if (t.getUserData() instanceof RepositoryView rv && rv.repo().root().toString().equals(selected)
                    && getParameters().getRaw().isEmpty()) {
                tabs.getSelectionModel().select(t);
            }
        }
        stage.show();
        String snapshots = System.getProperty("jit.snapshots");
        if (snapshots != null) {
            runSnapshots(Path.of(snapshots));
        }
    }

    /** Developer aid: walks through the main screens, saves PNG snapshots and exits. */
    private void runSnapshots(Path dir) {
        List<Runnable> steps = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Consumer<String> shot = name -> {
            try {
                Files.createDirectories(dir);
                Scene target = scene;
                if (name.startsWith("log")) {
                    for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                        if (w instanceof Stage st && st.getTitle() != null && st.getTitle().startsWith("Git Command")) {
                            target = st.getScene();
                        }
                    }
                }
                jit.dev.PngWriter.write(target.snapshot(null), dir.resolve(name + ".png"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
        steps.add(() -> { });
        names.add("history-light");
        steps.add(() -> {
            RepositoryView rv = currentRepo();
            if (rv != null) {
                // simulated network operation reporting progress, to capture the progress UI
                rv.run("Fetch", p -> {
                    for (int i = 0; i <= 100; i += 2) {
                        p.accept("Receiving objects:  " + i + "% (" + i * 12 + "/1200), 3.10 MiB | 2.40 MiB/s");
                        Thread.sleep(80);
                    }
                }, null, true);
            }
        });
        names.add("progress-light");
        steps.add(() -> {
            RepositoryView rv = currentRepo();
            if (rv != null) {
                rv.showLocalChanges();
            }
        });
        names.add("changes-light");
        steps.add(() -> {
            RepositoryView rv = currentRepo();
            if (rv != null) {
                rv.showCommandLog();
            }
        });
        names.add("log-light");
        steps.add(() -> {
            setTheme(true);
            RepositoryView rv = currentRepo();
            if (rv != null) {
                rv.showHistory();
            }
        });
        names.add("history-dark");
        steps.add(() -> {
            RepositoryView rv = currentRepo();
            if (rv != null) {
                rv.showLocalChanges();
            }
        });
        names.add("changes-dark");
        steps.add(() -> {
            setTheme(false);
            newTab();
        });
        names.add("welcome-light");
        javafx.animation.Timeline timeline = new javafx.animation.Timeline();
        for (int i = 0; i < steps.size(); i++) {
            int index = i;
            timeline.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(2.5 + i * 2.5),
                    e -> steps.get(index).run()));
            timeline.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(4.5 + i * 2.5),
                    e -> shot.accept(names.get(index))));
        }
        timeline.setOnFinished(e -> {
            tabs.getTabs().remove(tabs.getTabs().size() - 1);
            Platform.exit();
        });
        timeline.play();
    }

    // ------------------------------------------------------------------ menu

    private MenuBar buildMenu() {
        Menu file = new Menu("File");
        file.getItems().addAll(
                item("New Tab", "Shortcut+T", this::newTab),
                item("Open Repository…", "Shortcut+O", this::openDialog),
                item("Clone Repository…", "Shortcut+Shift+O", this::cloneDialog),
                item("New Repository…", null, this::initDialog),
                new SeparatorMenuItem(),
                item("Close Tab", "Shortcut+W", this::closeCurrentTab),
                item("Next Tab", "Shortcut+PAGE_DOWN", () -> selectTab(1)),
                item("Previous Tab", "Shortcut+PAGE_UP", () -> selectTab(-1)),
                new SeparatorMenuItem(),
                item("Preferences…", "Shortcut+Comma", () -> {
                    if (PreferencesDialog.show(stage, PreferencesDialog.TAB_GENERAL)) {
                        for (Tab t : tabs.getTabs()) {
                            if (t.getUserData() instanceof RepositoryView rv) {
                                rv.restartAutoFetch();
                            }
                        }
                    }
                }),
                new SeparatorMenuItem(),
                item("Exit", null, () -> {
                    saveState();
                    Platform.exit();
                }));

        Menu repository = new Menu("Repository");
        repository.getItems().addAll(
                repoItem("Refresh", "F5", rv -> rv.refresh(true)),
                repoItem("Local Changes", "Shortcut+1", RepositoryView::showLocalChanges),
                repoItem("All Commits", "Shortcut+2", RepositoryView::showHistory),
                repoItem("Search Commits", "Shortcut+F", RepositoryView::focusSearch),
                repoItem("Commit…", "Shortcut+Shift+C", RepositoryView::focusCommitMessage),
                new SeparatorMenuItem(),
                repoItem("Fetch…", "Shortcut+Shift+F", RepositoryView::fetch),
                repoItem("Pull…", "Shortcut+Shift+L", RepositoryView::pull),
                repoItem("Push…", "Shortcut+Shift+P", RepositoryView::push),
                new SeparatorMenuItem(),
                repoItem("Stash Changes…", "Shortcut+Shift+S", RepositoryView::stash),
                repoItem("Pop Stash", null, RepositoryView::popLatestStash),
                new SeparatorMenuItem(),
                repoItem("New Branch…", "Shortcut+Shift+B", rv -> rv.createBranch("HEAD", "HEAD")),
                repoItem("Add Remote…", null, RepositoryView::addRemote),
                repoItem("Repository Settings…", null, RepositoryView::showRepositorySettings),
                repoItem("Abort Merge / Rebase", null, RepositoryView::abortOperation),
                new SeparatorMenuItem(),
                repoItem("Apply Patch…", "Shortcut+Shift+A", RepositoryView::applyPatch),
                repoItem("Save Local Changes as Patch…", null, RepositoryView::saveAllLocalChanges),
                new SeparatorMenuItem(),
                repoItem("Open in Terminal", "Shortcut+Alt+T", rv -> Ui.openTerminal(rv.repo().root())),
                repoItem("Show in Explorer", "Shortcut+Alt+E", rv -> Ui.openInExplorer(rv.repo().root())));
        repository.setOnShowing(e -> {
            boolean none = currentRepo() == null;
            repository.getItems().forEach(mi -> mi.setDisable(none));
        });

        Menu view = new Menu("View");
        ToggleGroup themes = new ToggleGroup();
        RadioMenuItem light = new RadioMenuItem("Light Theme");
        RadioMenuItem dark = new RadioMenuItem("Dark Theme");
        light.setToggleGroup(themes);
        dark.setToggleGroup(themes);
        (Theme.isDark() ? dark : light).setSelected(true);
        light.setOnAction(e -> setTheme(false));
        dark.setOnAction(e -> setTheme(true));
        view.getItems().addAll(light, dark, new SeparatorMenuItem(),
                item("Git Command History", "Shortcut+Shift+H", () -> {
                    RepositoryView rv = currentRepo();
                    if (rv != null) {
                        rv.showCommandLog();
                    } else {
                        CommandLogWindow.show(null);
                    }
                }));

        Menu help = new Menu("Help");
        help.getItems().add(item("About Jit", null, () -> AboutDialog.show(stage, AppIcon.image(128))));

        MenuBar bar = new MenuBar(file, repository, view, help);
        bar.getStyleClass().add("app-menu");
        return bar;
    }

    private static MenuItem item(String text, String accelerator, Runnable action) {
        MenuItem mi = new MenuItem(text);
        if (accelerator != null) {
            mi.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private MenuItem repoItem(String text, String accelerator, Consumer<RepositoryView> action) {
        return item(text, accelerator, () -> {
            RepositoryView rv = currentRepo();
            if (rv != null) {
                action.accept(rv);
            }
        });
    }

    private void setTheme(boolean dark) {
        Theme.setDark(dark);
        Theme.apply(scene);
        CommandLogWindow.refreshTheme();
        for (Tab t : tabs.getTabs()) {
            if (t.getUserData() instanceof RepositoryView rv) {
                rv.refreshCells();
            }
        }
    }

    // ------------------------------------------------------------------ tabs

    private RepositoryView currentRepo() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        return t != null && t.getUserData() instanceof RepositoryView rv ? rv : null;
    }

    private void updateTitle() {
        RepositoryView rv = currentRepo();
        stage.setTitle(rv == null ? "Jit" : rv.repo().name() + " — Jit");
    }

    private void newTab() {
        WelcomeView welcome = new WelcomeView(p -> openRepository(p, true), this::openDialog, this::cloneDialog,
                this::initDialog);
        Tab tab = new Tab("Repository Manager", welcome);
        tab.setGraphic(Icons.icon(Icons.LIST, 13));
        tab.setUserData(welcome);
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    private void closeCurrentTab() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        if (t != null) {
            closeTabs(List.of(t));
        }
    }

    private void closeTabs(List<Tab> toClose) {
        for (Tab t : new ArrayList<>(toClose)) {
            if (t.getUserData() instanceof RepositoryView rv) {
                rv.dispose();
            }
            tabs.getTabs().remove(t);
        }
        saveTabs();
        if (tabs.getTabs().isEmpty()) {
            newTab();
        }
    }

    private void selectTab(int delta) {
        int n = tabs.getTabs().size();
        if (n > 1) {
            int i = tabs.getSelectionModel().getSelectedIndex();
            tabs.getSelectionModel().select(Math.floorMod(i + delta, n));
        }
    }

    private ContextMenu tabMenu(Tab tab, RepositoryView view) {
        return new ContextMenu(
                Ui.item("Close Tab", Icons.CLOSE, () -> closeTabs(List.of(tab))),
                Ui.item("Close Other Tabs", null, () -> closeTabs(tabs.getTabs().stream().filter(t -> t != tab).toList())),
                Ui.item("Close Tabs to the Right", null, () -> closeTabs(
                        tabs.getTabs().subList(tabs.getTabs().indexOf(tab) + 1, tabs.getTabs().size()))),
                Ui.item("Close All Tabs", null, () -> closeTabs(tabs.getTabs())),
                new SeparatorMenuItem(),
                Ui.item("New Tab", Icons.PLUS, this::newTab),
                new SeparatorMenuItem(),
                Ui.item("Copy Path", Icons.COPY, () -> Ui.copy(view.repo().root().toString())),
                Ui.item("Show in Explorer", Icons.FOLDER, () -> Ui.openInExplorer(view.repo().root())),
                Ui.item("Open in Terminal", Icons.TERMINAL, () -> Ui.openTerminal(view.repo().root())),
                Ui.item("Repository Settings…", Icons.EDIT, view::showRepositorySettings));
    }

    private void openRepository(Path dir, boolean interactive) {
        Path root;
        try {
            root = Repo.findRoot(dir);
        } catch (GitException e) {
            if (!interactive) {
                return;
            }
            if (Files.isDirectory(dir) && Dialogs.confirm(stage, "Not a Git repository",
                    dir + " is not a Git repository. Initialize a new repository there?", "Initialize")) {
                try {
                    Repo.init(dir);
                    root = Repo.findRoot(dir);
                } catch (GitException ex) {
                    Dialogs.error(stage, "Cannot initialize repository", ex.getMessage());
                    return;
                }
            } else {
                return;
            }
        }
        for (Tab t : tabs.getTabs()) {
            if (t.getUserData() instanceof RepositoryView rv && rv.repo().root().equals(root)) {
                tabs.getSelectionModel().select(t);
                return;
            }
        }
        Repo repo = new Repo(root);
        RepositoryView view = new RepositoryView(repo);
        Tab tab = new Tab(repo.name(), view);
        tab.setGraphic(Icons.icon(Icons.FOLDER, 13));
        tab.setTooltip(new Tooltip(root.toString()));
        tab.setUserData(view);
        tab.setContextMenu(tabMenu(tab, view));
        tab.setOnClosed(e -> {
            view.dispose();
            saveTabs();
            if (tabs.getTabs().isEmpty()) {
                newTab();
            }
        });
        Tab current = tabs.getSelectionModel().getSelectedItem();
        if (interactive && current != null && current.getUserData() instanceof WelcomeView) {
            int index = tabs.getTabs().indexOf(current);
            tabs.getTabs().set(index, tab);
        } else {
            tabs.getTabs().add(tab);
        }
        tabs.getSelectionModel().select(tab);
        Settings.addRecent(root.toString());
        refreshWelcomeTabs();
        saveTabs();
    }

    private void refreshWelcomeTabs() {
        for (Tab t : tabs.getTabs()) {
            if (t.getUserData() instanceof WelcomeView w) {
                w.reload();
            }
        }
    }

    private void saveTabs() {
        List<String> open = new ArrayList<>();
        for (Tab t : tabs.getTabs()) {
            if (t.getUserData() instanceof RepositoryView rv) {
                open.add(rv.repo().root().toString());
            }
        }
        Settings.setOpenTabs(open);
    }

    private void saveState() {
        saveTabs();
        RepositoryView rv = currentRepo();
        Settings.put("selectedTab", rv == null ? "" : rv.repo().root().toString());
        Settings.putDouble("maximized", stage.isMaximized() ? 1 : 0);
        if (!stage.isMaximized()) {
            Settings.putDouble("width", stage.getWidth());
            Settings.putDouble("height", stage.getHeight());
        }
    }

    // ------------------------------------------------------------------ open / clone / init

    private File initialDirectory() {
        File f = new File(Settings.get("lastDir", System.getProperty("user.home")));
        return f.isDirectory() ? f : new File(System.getProperty("user.home"));
    }

    private void openDialog() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Repository");
        chooser.setInitialDirectory(initialDirectory());
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            Settings.put("lastDir", dir.getParent() == null ? dir.toString() : dir.getParent());
            openRepository(dir.toPath(), true);
        }
    }

    private void initDialog() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a folder for the new repository");
        chooser.setInitialDirectory(initialDirectory());
        File dir = chooser.showDialog(stage);
        if (dir == null) {
            return;
        }
        try {
            Repo.init(dir.toPath());
            openRepository(dir.toPath(), true);
        } catch (GitException e) {
            Dialogs.error(stage, "Cannot initialize repository", e.getMessage());
        }
    }

    private void cloneDialog() {
        Dialogs.Form form = new Dialogs.Form();
        TextField url = form.text("Repository URL", "");
        url.setPromptText("https://github.com/user/repo.git");
        TextField parent = new TextField(initialDirectory().toString());
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setInitialDirectory(new File(parent.getText()).isDirectory() ? new File(parent.getText())
                    : initialDirectory());
            File d = chooser.showDialog(stage);
            if (d != null) {
                parent.setText(d.toString());
            }
        });
        HBox parentBox = new HBox(6, parent, browse);
        HBox.setHgrow(parent, Priority.ALWAYS);
        form.add("Parent folder", parentBox);
        TextField name = form.text("Name", "");
        url.textProperty().addListener((obs, o, n) -> name.setText(nameFromUrl(n)));
        Platform.runLater(url::requestFocus);
        if (!form.show(stage, "Clone Repository", "Clone a remote repository", "Clone",
                url.textProperty().isNotEmpty().and(name.textProperty().isNotEmpty())
                        .and(parent.textProperty().isNotEmpty()))) {
            return;
        }
        Path target = Path.of(parent.getText().strip(), name.getText().strip());
        if (Files.exists(target)) {
            try (var s = Files.list(target)) {
                if (s.findAny().isPresent()) {
                    Dialogs.error(stage, "Clone", target + " already exists and is not empty.");
                    return;
                }
            } catch (Exception ignored) {
                // fall through and let git report the problem
            }
        }
        Settings.put("lastDir", parent.getText().strip());
        String u = url.getText().strip();

        Stage progress = new Stage(StageStyle.UTILITY);
        progress.initOwner(stage);
        progress.initModality(Modality.WINDOW_MODAL);
        progress.setTitle("Cloning " + name.getText().strip());
        Label line = Ui.label("Starting…", "dim-text");
        line.setMaxWidth(440);
        ProgressBar bar = new ProgressBar();
        bar.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(12, Ui.label("Cloning " + u, "form-value"), bar, line);
        box.setPadding(new Insets(18));
        box.setPrefWidth(480);
        box.getStyleClass().add("progress-box");
        Scene ps = new Scene(box);
        Theme.apply(ps);
        progress.setScene(ps);
        progress.setOnCloseRequest(e -> e.consume());
        progress.show();

        Thread t = new Thread(() -> {
            try {
                Repo.clone(u, target, l -> Platform.runLater(() -> {
                    jit.git.Progress p = jit.git.Progress.parse(l);
                    line.setText(p.label());
                    bar.setProgress(p.percent() < 0 ? ProgressBar.INDETERMINATE_PROGRESS : p.percent() / 100.0);
                }));
                Platform.runLater(() -> {
                    progress.close();
                    openRepository(target, true);
                });
            } catch (GitException ex) {
                Platform.runLater(() -> {
                    progress.close();
                    Dialogs.error(stage, "Clone failed", ex.getMessage());
                });
            }
        }, "git-clone");
        t.setDaemon(true);
        t.start();
    }

    private static String nameFromUrl(String url) {
        String s = url.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        int i = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        return i >= 0 ? s.substring(i + 1) : s;
    }
}
