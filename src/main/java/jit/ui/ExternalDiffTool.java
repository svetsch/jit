package jit.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The user-configured external diff tool (WinMerge, Beyond Compare, …) and how to launch it. */
public final class ExternalDiffTool {

    /** A known tool: where it is usually installed and the arguments it expects. */
    public record Preset(String name, List<String> candidates, String arguments) {
        @Override
        public String toString() {
            return name;
        }

        /** First candidate path that exists, or {@code null}. */
        public String detect() {
            for (String c : candidates) {
                String expanded = expand(c);
                if (expanded.contains("\\") || expanded.contains("/")) {
                    if (Files.isRegularFile(Path.of(expanded))) {
                        return expanded;
                    }
                } else if (onPath(expanded) != null) {
                    return onPath(expanded);
                }
            }
            return null;
        }
    }

    public static final String CUSTOM = "Custom";

    public static final List<Preset> PRESETS = List.of(
            new Preset("WinMerge", List.of(
                    "%ProgramFiles%\\WinMerge\\WinMergeU.exe",
                    "%ProgramFiles(x86)%\\WinMerge\\WinMergeU.exe",
                    "%LOCALAPPDATA%\\Programs\\WinMerge\\WinMergeU.exe"),
                    "/e /u /wl /dl \"$LOCAL_TITLE\" /dr \"$REMOTE_TITLE\" \"$LOCAL\" \"$REMOTE\""),
            new Preset("Beyond Compare", List.of(
                    "%ProgramFiles%\\Beyond Compare 5\\BCompare.exe",
                    "%ProgramFiles%\\Beyond Compare 4\\BCompare.exe",
                    "/usr/bin/bcompare", "/usr/local/bin/bcompare", "bcomp"),
                    "\"$LOCAL\" \"$REMOTE\" /lefttitle=\"$LOCAL_TITLE\" /righttitle=\"$REMOTE_TITLE\""),
            new Preset("Visual Studio Code", List.of(
                    "%LOCALAPPDATA%\\Programs\\Microsoft VS Code\\Code.exe",
                    "%ProgramFiles%\\Microsoft VS Code\\Code.exe",
                    "/usr/bin/code", "/usr/local/bin/code", "code"),
                    "--new-window --diff \"$LOCAL\" \"$REMOTE\""),
            new Preset("Meld", List.of(
                    "%ProgramFiles%\\Meld\\Meld.exe", "%ProgramFiles(x86)%\\Meld\\Meld.exe",
                    "/usr/bin/meld", "/usr/local/bin/meld", "meld"),
                    "--label \"$LOCAL_TITLE\" --label \"$REMOTE_TITLE\" \"$LOCAL\" \"$REMOTE\""),
            new Preset("KDiff3", List.of(
                    "%ProgramFiles%\\KDiff3\\kdiff3.exe", "%ProgramFiles%\\KDiff3\\bin\\kdiff3.exe",
                    "/usr/bin/kdiff3", "kdiff3"),
                    "--L1 \"$LOCAL_TITLE\" --L2 \"$REMOTE_TITLE\" \"$LOCAL\" \"$REMOTE\""),
            new Preset("P4Merge", List.of(
                    "%ProgramFiles%\\Perforce\\p4merge.exe", "/usr/bin/p4merge", "p4merge"),
                    "-nl \"$LOCAL_TITLE\" -nr \"$REMOTE_TITLE\" \"$LOCAL\" \"$REMOTE\""));

    private ExternalDiffTool() {
    }

    public static String toolName() {
        return Settings.get("diffTool.name", "");
    }

    public static String command() {
        return Settings.get("diffTool.command", "");
    }

    public static String arguments() {
        return Settings.get("diffTool.arguments", "\"$LOCAL\" \"$REMOTE\"");
    }

    public static void save(String name, String command, String arguments) {
        Settings.put("diffTool.name", name == null ? "" : name);
        Settings.put("diffTool.command", command == null ? "" : command.strip());
        Settings.put("diffTool.arguments", arguments == null ? "" : arguments.strip());
    }

    public static boolean isConfigured() {
        return !command().isBlank();
    }

    /**
     * Starts the tool comparing two files. Placeholders in the arguments: {@code $LOCAL}, {@code $REMOTE}
     * (file paths) and {@code $LOCAL_TITLE}, {@code $REMOTE_TITLE} (labels).
     */
    public static void launch(Path local, Path remote, String localTitle, String remoteTitle) throws IOException {
        Map<String, String> values = Map.of(
                "$LOCAL_TITLE", localTitle,
                "$REMOTE_TITLE", remoteTitle,
                "$LOCAL", local.toString(),
                "$REMOTE", remote.toString());
        List<String> cmd = new ArrayList<>();
        cmd.add(expand(command()));
        for (String token : tokenize(arguments())) {
            String t = token;
            // titles first: "$LOCAL" is a prefix of "$LOCAL_TITLE"
            t = t.replace("$LOCAL_TITLE", values.get("$LOCAL_TITLE"))
                    .replace("$REMOTE_TITLE", values.get("$REMOTE_TITLE"))
                    .replace("$LOCAL", values.get("$LOCAL"))
                    .replace("$REMOTE", values.get("$REMOTE"));
            cmd.add(t);
        }
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    /** Splits a command line into arguments, honouring double quotes. */
    static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    any = false;
                }
            } else {
                cur.append(c);
                any = true;
            }
        }
        if (any) {
            tokens.add(cur.toString());
        }
        return tokens;
    }

    /** Expands %VAR% (Windows style) environment references. */
    static String expand(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf('%', i);
            int end = start < 0 ? -1 : s.indexOf('%', start + 1);
            if (start < 0 || end < 0) {
                sb.append(s, i, s.length());
                break;
            }
            String value = System.getenv(s.substring(start + 1, end));
            sb.append(s, i, start).append(value != null ? value : s.substring(start, end + 1));
            i = end + 1;
        }
        return sb.toString();
    }

    private static String onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            for (String ext : win ? List.of(".exe", ".cmd", ".bat") : List.of("")) {
                try {
                    Path p = Path.of(dir, exe + ext);
                    if (Files.isRegularFile(p)) {
                        return p.toString();
                    }
                } catch (Exception ignored) {
                    // malformed PATH entry
                }
            }
        }
        return null;
    }
}
