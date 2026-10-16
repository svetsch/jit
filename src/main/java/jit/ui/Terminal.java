package jit.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Opens the user's preferred terminal in a repository directory. */
public final class Terminal {
    public static final String CUSTOM = "Custom";
    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS.contains("win");
    private static final boolean MAC = OS.contains("mac");

    private Terminal() {
    }

    public static List<String> choices() {
        if (WINDOWS) {
            return List.of("Git Bash", "Windows Terminal", "PowerShell", "Command Prompt", CUSTOM);
        }
        if (MAC) {
            return List.of("Terminal", "iTerm", CUSTOM);
        }
        return List.of("Default terminal", CUSTOM);
    }

    public static String choice() {
        String c = Settings.get("terminal", choices().get(0));
        return choices().contains(c) ? c : choices().get(0);
    }

    /** Custom command line; {@code $DIR} is replaced by the repository directory. */
    public static String customCommand() {
        return Settings.get("terminal.custom", "");
    }

    public static void save(String choice, String customCommand) {
        Settings.put("terminal", choice);
        Settings.put("terminal.custom", customCommand == null ? "" : customCommand.strip());
    }

    public static void open(Path dir) throws IOException {
        List<String> cmd = new ArrayList<>();
        String choice = choice();
        switch (choice) {
            case "Git Bash" -> {
                Path bash = gitBash();
                if (bash == null) {
                    throw new IOException("Git Bash (git-bash.exe) was not found. Choose another terminal in "
                            + "Preferences.");
                }
                cmd.addAll(List.of(bash.toString(), "--cd=" + dir));
            }
            case "Windows Terminal" -> cmd.addAll(List.of("wt.exe", "-d", dir.toString()));
            case "PowerShell" -> cmd.addAll(List.of("cmd", "/c", "start", "", "powershell.exe", "-NoExit"));
            case "Command Prompt" -> cmd.addAll(List.of("cmd", "/c", "start", "", "cmd.exe"));
            case "Terminal" -> cmd.addAll(List.of("open", "-a", "Terminal", dir.toString()));
            case "iTerm" -> cmd.addAll(List.of("open", "-a", "iTerm", dir.toString()));
            case "Default terminal" -> cmd.add("x-terminal-emulator");
            default -> {
                String custom = customCommand();
                if (custom.isBlank()) {
                    throw new IOException("No custom terminal command is configured. Set one in Preferences.");
                }
                for (String token : ExternalDiffTool.tokenize(custom)) {
                    cmd.add(ExternalDiffTool.expand(token.replace("$DIR", dir.toString())));
                }
            }
        }
        new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Locates git-bash.exe next to the git installation in use. */
    static Path gitBash() {
        List<Path> candidates = new ArrayList<>();
        String exe = Settings.get("gitExecutable", "");
        if (!exe.isBlank()) {
            Path p = Path.of(exe).toAbsolutePath();
            for (Path d = p.getParent(); d != null; d = d.getParent()) {
                candidates.add(d.resolve("git-bash.exe"));
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                try {
                    Path d = Path.of(dir);
                    if (Files.isRegularFile(d.resolve("git.exe")) && d.getParent() != null) {
                        candidates.add(d.getParent().resolve("git-bash.exe"));
                        if (d.getParent().getParent() != null) {
                            candidates.add(d.getParent().getParent().resolve("git-bash.exe"));
                        }
                    }
                } catch (Exception ignored) {
                    // malformed PATH entry
                }
            }
        }
        candidates.add(Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "Git", "git-bash.exe"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            candidates.add(Path.of(local, "Programs", "Git", "git-bash.exe"));
        }
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) {
                return c;
            }
        }
        return null;
    }
}
