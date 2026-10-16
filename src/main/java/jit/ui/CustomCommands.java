package jit.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.Menu;
import javafx.scene.control.SeparatorMenuItem;

import jit.git.Git;

/** User defined commands with placeholders, run from menus on a repository, commit, branch or file. */
public final class CustomCommands {

    /** What a command needs to be selected to appear in a menu. */
    public enum Target {
        REPOSITORY("Repository"), COMMIT("Commit"), BRANCH("Branch"), FILE("File");

        public final String label;

        Target(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * @param command      a git command line ("git …", run and logged like Jit's own git calls) or any other
     *                     program, run through the system shell
     * @param inTerminal   run in a new terminal window instead of in the background
     * @param confirm      ask before running
     * @param showOutput   show the output in a dialog when done
     */
    public record Command(String name, Target target, String command, boolean inTerminal, boolean confirm,
                          boolean showOutput) {
        @Override
        public String toString() {
            return name;
        }
    }

    public static final String HELP = "Placeholders: $REPO (repository folder), $BRANCH (current branch), "
            + "$SHA / $SHORT_SHA (selected commit), $REF (selected branch or tag), $FILE (selected file, relative), "
            + "$FILE_ABS, $REMOTE (default remote), $REMOTE_URL. Commands starting with \"git \" run with Jit's git "
            + "and appear in the command history; others run through the system shell.";

    private CustomCommands() {
    }

    public static List<Command> all() {
        List<Command> list = new ArrayList<>();
        for (String line : Settings.get("customCommands", "").split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 6 || f[0].isBlank()) {
                continue;
            }
            Target t;
            try {
                t = Target.valueOf(f[1]);
            } catch (IllegalArgumentException e) {
                t = Target.REPOSITORY;
            }
            list.add(new Command(f[0], t, f[2], Boolean.parseBoolean(f[3]), Boolean.parseBoolean(f[4]),
                    Boolean.parseBoolean(f[5])));
        }
        return list;
    }

    public static void save(List<Command> commands) {
        StringBuilder sb = new StringBuilder();
        for (Command c : commands) {
            sb.append(clean(c.name())).append('\t').append(c.target().name()).append('\t')
                    .append(clean(c.command())).append('\t').append(c.inTerminal()).append('\t')
                    .append(c.confirm()).append('\t').append(c.showOutput()).append('\n');
        }
        Settings.put("customCommands", sb.toString());
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("\t", " ").replace("\n", " ").strip();
    }

    public static List<Command> forTarget(Target target) {
        return all().stream().filter(c -> c.target() == target).toList();
    }

    /**
     * Builds a "Custom Commands" submenu for the given target, or {@code null} when there are none.
     * Repository-wide commands are included in every menu.
     */
    public static Menu menu(Target target, Consumer<Command> run) {
        List<Command> specific = target == Target.REPOSITORY ? List.of() : forTarget(target);
        List<Command> general = forTarget(Target.REPOSITORY);
        if (specific.isEmpty() && general.isEmpty()) {
            return null;
        }
        Menu menu = new Menu("Custom Commands");
        menu.setGraphic(Icons.icon(Icons.TERMINAL, 14));
        for (Command c : specific) {
            menu.getItems().add(Ui.item(c.name(), () -> run.accept(c)));
        }
        if (!specific.isEmpty() && !general.isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
        }
        for (Command c : general) {
            menu.getItems().add(Ui.item(c.name(), () -> run.accept(c)));
        }
        return menu;
    }

    /** Replaces placeholders; longer names first so $SHORT_SHA is not mistaken for $SHA. */
    static String expand(String template, Map<String, String> values) {
        String s = template;
        List<String> keys = new ArrayList<>(values.keySet());
        keys.sort((a, b) -> b.length() - a.length());
        for (String k : keys) {
            s = s.replace("$" + k, values.get(k) == null ? "" : values.get(k));
        }
        return s;
    }

    /** Runs a command in the background; returns its combined output. */
    static String execute(String commandLine, Path dir) throws Exception {
        String trimmed = commandLine.strip();
        if (trimmed.startsWith("git ")) {
            List<String> args = ExternalDiffTool.tokenize(trimmed.substring(4));
            Git.Result r = new Git(dir.toFile()).run(null, null, args);
            String out = (r.out() + (r.err().isBlank() ? "" : "\n" + r.err())).strip();
            if (!r.ok()) {
                throw new Exception(out.isEmpty() ? "git exited with code " + r.exitCode() : out);
            }
            return out;
        }
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        ProcessBuilder pb = win ? new ProcessBuilder("cmd.exe", "/c", trimmed) : new ProcessBuilder("sh", "-c", trimmed);
        pb.directory(dir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        int code = p.waitFor();
        if (code != 0) {
            throw new Exception((out.isEmpty() ? "" : out + "\n\n") + "Exited with code " + code);
        }
        return out;
    }

    /** Opens a terminal running the command and keeping the window open afterwards. */
    static void executeInTerminal(String commandLine, Path dir) throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (win) {
            new ProcessBuilder("cmd", "/c", "start", "", "cmd.exe", "/K", commandLine).directory(dir.toFile()).start();
        } else if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            String script = "tell application \"Terminal\" to do script \"cd " + dir.toString().replace("\"", "\\\"")
                    + " && " + commandLine.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            new ProcessBuilder("osascript", "-e", script).start();
        } else {
            new ProcessBuilder("x-terminal-emulator", "-e", "sh", "-c", commandLine + "; exec $SHELL")
                    .directory(dir.toFile()).start();
        }
    }
}
