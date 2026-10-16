package jit.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.geometry.Insets;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import jit.git.Git;
import jit.git.GitConfig;
import jit.git.GitException;
import jit.git.Repo;
import jit.settings.SettingsLocation;

/** Application preferences and global git configuration. */
public final class PreferencesDialog {
    public static final String TAB_GENERAL = "General";
    public static final String TAB_GIT = "Git";
    public static final String TAB_DIFF = "Diff Tool";
    public static final String TAB_TERMINAL = "Terminal";
    public static final String TAB_COMMANDS = "Custom Commands";
    public static final String TAB_ISSUES = "Issue Links";
    public static final String TAB_STORAGE = "Storage";

    private PreferencesDialog() {
    }

    /** Shows the dialog; returns true when the user saved. */
    public static boolean show(Window owner, String initialTab) {
        Dialog<ButtonType> d = new Dialog<>();
        if (owner != null) {
            d.initOwner(owner);
        }
        d.setTitle("Preferences");
        d.setResizable(true);
        d.getDialogPane().getStylesheets().setAll(Theme.stylesheets());
        d.getDialogPane().getStyleClass().addAll("jit-dialog", "preferences-dialog");

        // General ---------------------------------------------------------------
        Dialogs.Form general = new Dialogs.Form();
        TextField gitExe = new TextField(Settings.get("gitExecutable", ""));
        gitExe.setPromptText("git (from PATH)");
        general.add("Git executable", withBrowse(gitExe, owner, "Choose the git executable"));
        Label gitVersion = general.note("");
        Runnable checkGit = () -> gitVersion.setText("Detected: " + gitVersion(gitExe.getText()));
        gitExe.textProperty().addListener((obs, o, n) -> checkGit.run());
        checkGit.run();

        // Git config ------------------------------------------------------------
        GitConfigPane gitPane;
        try {
            gitPane = new GitConfigPane(GitConfig.global(), Map.of());
        } catch (GitException e) {
            Dialogs.error(owner, "Cannot read git configuration", e.getMessage());
            return false;
        }

        // Diff tool -------------------------------------------------------------
        Dialogs.Form diff = new Dialogs.Form();
        ComboBox<String> preset = new ComboBox<>();
        preset.getItems().add("None");
        ExternalDiffTool.PRESETS.forEach(p -> preset.getItems().add(p.name()));
        preset.getItems().add(ExternalDiffTool.CUSTOM);
        String current = ExternalDiffTool.toolName();
        preset.setValue(!ExternalDiffTool.isConfigured() ? "None"
                : preset.getItems().contains(current) ? current : ExternalDiffTool.CUSTOM);
        preset.setMaxWidth(Double.MAX_VALUE);
        diff.add("Tool", preset);
        TextField diffCommand = new TextField(ExternalDiffTool.command());
        diffCommand.setPromptText("Path to the executable");
        diff.add("Executable", withBrowse(diffCommand, owner, "Choose the diff tool executable"));
        TextField diffArgs = diff.text("Arguments", ExternalDiffTool.arguments());
        Label diffNote = diff.note("Placeholders: $LOCAL and $REMOTE (files to compare), $LOCAL_TITLE and "
                + "$REMOTE_TITLE (labels). For local changes $REMOTE is your working file, so edits made in "
                + "the tool are kept.");
        Label detected = diff.note("");
        preset.valueProperty().addListener((obs, o, n) -> {
            detected.setText("");
            if (n == null || n.equals(ExternalDiffTool.CUSTOM)) {
                return;
            }
            if (n.equals("None")) {
                diffCommand.clear();
                return;
            }
            for (ExternalDiffTool.Preset p : ExternalDiffTool.PRESETS) {
                if (p.name().equals(n)) {
                    String found = p.detect();
                    diffCommand.setText(found != null ? found : ExternalDiffTool.expand(p.candidates().get(0)));
                    diffArgs.setText(p.arguments());
                    detected.setText(found != null ? "Found " + found
                            : n + " was not found in its usual location — check the executable path.");
                }
            }
        });
        diffNote.setMaxWidth(460);

        // Terminal --------------------------------------------------------------
        Dialogs.Form terminal = new Dialogs.Form();
        ComboBox<String> term = new ComboBox<>();
        term.getItems().addAll(Terminal.choices());
        term.setValue(Terminal.choice());
        term.setMaxWidth(Double.MAX_VALUE);
        terminal.add("Terminal", term);
        TextField termCustom = terminal.text("Custom command", Terminal.customCommand());
        termCustom.setPromptText("e.g. alacritty --working-directory \"$DIR\"");
        termCustom.disableProperty().bind(term.valueProperty().isNotEqualTo(Terminal.CUSTOM));
        terminal.note("$DIR is replaced by the repository folder. The terminal also starts in that folder.");

        PreferenceEditors.CommandsEditor commands = new PreferenceEditors.CommandsEditor();
        PreferenceEditors.IssueLinksEditor issues = new PreferenceEditors.IssueLinksEditor();

        // Storage ---------------------------------------------------------------
        Map<SettingsLocation, RadioButton> locations = new EnumMap<>(SettingsLocation.class);
        VBox storage = storageTab(locations);

        TabPane tabs = new TabPane(
                tab(TAB_GENERAL, general.node()),
                tab(TAB_GIT, gitPane),
                tab(TAB_DIFF, diff.node()),
                tab(TAB_TERMINAL, terminal.node()),
                tab(TAB_COMMANDS, commands),
                tab(TAB_ISSUES, issues),
                tab(TAB_STORAGE, storage));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefSize(720, 620);
        tabs.getTabs().stream().filter(t -> t.getText().equals(initialTab)).findFirst()
                .ifPresent(t -> tabs.getSelectionModel().select(t));
        d.getDialogPane().setContent(tabs);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        while (true) {
            if (d.showAndWait().filter(b -> b == save).isEmpty()) {
                return false;
            }
            try {
                gitPane.save();
            } catch (GitException e) {
                Dialogs.error(owner, "Cannot save git configuration", e.getMessage());
                continue;
            }
            String exe = gitExe.getText().strip();
            Settings.put("gitExecutable", exe);
            Git.setExecutable(exe.isEmpty() ? "git" : exe);
            String tool = preset.getValue();
            if (tool == null || tool.equals("None")) {
                ExternalDiffTool.save("", "", diffArgs.getText());
            } else {
                ExternalDiffTool.save(tool, diffCommand.getText(), diffArgs.getText());
            }
            Terminal.save(term.getValue(), termCustom.getText());
            commands.save();
            issues.save();
            SettingsLocation target = locations.entrySet().stream().filter(e -> e.getValue().isSelected())
                    .map(Map.Entry::getKey).findFirst().orElse(Settings.store().location());
            if (target != Settings.store().location()) {
                try {
                    Settings.moveTo(target);
                } catch (RuntimeException e) {
                    Dialogs.error(owner, "Cannot move the settings to " + target.displayName(), e.getMessage());
                }
            }
            return true;
        }
    }

    /** Repository > Repository Settings: local identity and options overriding the global ones. */
    public static void showRepositorySettings(Window owner, Repo repo) {
        GitConfigPane pane;
        try {
            pane = new GitConfigPane(GitConfig.local(repo), GitConfig.global().list());
        } catch (GitException e) {
            Dialogs.error(owner, "Cannot read git configuration", e.getMessage());
            return;
        }
        Dialog<ButtonType> d = new Dialog<>();
        if (owner != null) {
            d.initOwner(owner);
        }
        d.setTitle("Repository Settings — " + repo.name());
        d.setHeaderText("Settings for " + repo.name());
        d.setResizable(true);
        d.getDialogPane().getStylesheets().setAll(Theme.stylesheets());
        d.getDialogPane().getStyleClass().add("jit-dialog");
        pane.setPrefSize(640, 560);
        d.getDialogPane().setContent(pane);
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        while (d.showAndWait().filter(b -> b == save).isPresent()) {
            try {
                pane.save();
                return;
            } catch (GitException e) {
                Dialogs.error(owner, "Cannot save git configuration", e.getMessage());
            }
        }
    }

    private static VBox storageTab(Map<SettingsLocation, RadioButton> locations) {
        ToggleGroup group = new ToggleGroup();
        VBox options = new VBox(10);
        SettingsLocation current = Settings.store().location();
        for (SettingsLocation location : SettingsLocation.values()) {
            if (location == SettingsLocation.CUSTOM_FILE && current != SettingsLocation.CUSTOM_FILE) {
                continue;
            }
            RadioButton radio = new RadioButton(location.displayName());
            radio.setToggleGroup(group);
            radio.setSelected(location == current);
            boolean available = location.isAvailable();
            radio.setDisable(!available || current == SettingsLocation.CUSTOM_FILE);
            Path file = location.file();
            String where = location == SettingsLocation.SYSTEM
                    ? (location == current ? Settings.store().description()
                    : "Windows: registry key HKEY_CURRENT_USER\\Software\\JavaSoft\\Prefs\\jit-git-client; "
                    + "other systems: Java preferences")
                    : file == null ? "Not available when running from the development build"
                    : file + (available ? "" : " (folder not writable)");
            locations.put(location, radio);
            Label hint = Ui.label(where, "form-note");
            hint.setWrapText(true);
            HBox indent = new HBox(hint);
            indent.setPadding(new Insets(0, 0, 0, 24));
            options.getChildren().add(new VBox(2, radio, indent));
        }
        Label currentLabel = Ui.label("Settings are currently stored in: " + Settings.store().description(),
                "form-value");
        currentLabel.setWrapText(true);
        Label help = Ui.label("Settings include the open and recent repositories, window size, theme, diff tool, "
                + "terminal, custom commands and issue links (git's own configuration stays in git). When you choose "
                + "another location, all settings are moved there and removed from the old location. At start-up "
                + "Jit looks for a portable settings file next to the application first, then for the file in the "
                + "user folder, and falls back to the system preferences. The start option -Djit.settings=<file> "
                + "forces a specific file.", "form-note");
        help.setWrapText(true);
        VBox box = new VBox(14, currentLabel, options, help);
        box.setPadding(new Insets(16, 20, 12, 20));
        return box;
    }

    private static Tab tab(String title, javafx.scene.Node content) {
        Tab t = new Tab(title, content);
        t.setClosable(false);
        return t;
    }

    private static HBox withBrowse(TextField field, Window owner, String title) {
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(title);
            File f = chooser.showOpenDialog(owner);
            if (f != null) {
                field.setText(f.getAbsolutePath());
            }
        });
        HBox box = new HBox(6, field, browse);
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    private static String gitVersion(String exe) {
        String saved = exe == null || exe.isBlank() ? "git" : exe.strip();
        try {
            ProcessBuilder pb = new ProcessBuilder(saved, "--version").redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).strip();
            return p.waitFor() == 0 ? out : "not working (" + out + ")";
        } catch (Exception e) {
            return "not found";
        }
    }
}
