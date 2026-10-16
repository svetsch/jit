package jit.git;

import java.io.File;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Application wide history of every git command the client has run. */
public final class CommandLog {

    /** Sub-commands that only read repository state. */
    private static final Set<String> QUERIES = Set.of("log", "status", "for-each-ref", "rev-parse", "show", "diff",
            "diff-tree", "cat-file", "symbolic-ref", "blame", "remote", "config", "ls-files", "--version");

    /** One finished git invocation. */
    public record Entry(long id, Instant time, File dir, List<String> args, int exitCode, long durationMs,
                        String stdin, String stderr, long stdoutBytes) {

        /** The command as it could be typed in a shell. */
        public String commandLine() {
            StringBuilder sb = new StringBuilder("git");
            for (String a : args) {
                sb.append(' ').append(quote(a));
            }
            return sb.toString();
        }

        public String subcommand() {
            return args.isEmpty() ? "" : args.get(0);
        }

        /** True for commands that only read state (as opposed to fetch, commit, checkout …). */
        public boolean isQuery() {
            String sub = subcommand();
            if (sub.equals("stash")) {
                return args.size() > 1 && args.get(1).equals("list");
            }
            if (sub.equals("remote")) {
                return args.size() == 1;
            }
            if (sub.equals("config")) {
                return args.contains("--get") || args.contains("--get-regexp");
            }
            return QUERIES.contains(sub);
        }

        /** Exit code 0, or 1 for {@code diff --no-index}, which reports "files differ" that way. */
        public boolean ok() {
            return exitCode == 0 || exitCode == 1 && subcommand().equals("diff") && args.contains("--no-index");
        }

        private static String quote(String a) {
            if (a.equals(">") || !a.isEmpty() && a.chars().noneMatch(c -> Character.isWhitespace(c) || "\"'$`\\|&;<>()*?!{}"
                    .indexOf(c) >= 0)) {
                return a;
            }
            return "'" + a.replace("'", "'\\''") + "'";
        }
    }

    private static final int MAX_ENTRIES = 5000;
    private static final int MAX_TEXT = 20_000;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();
    private static final List<Consumer<Entry>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicLong IDS = new AtomicLong();

    private CommandLog() {
    }

    static void record(File dir, List<String> args, int exitCode, long durationMs, String stdin, String stderr,
                       long stdoutBytes) {
        Entry e = new Entry(IDS.incrementAndGet(), Instant.now(), dir, List.copyOf(args), exitCode, durationMs,
                truncate(stdin), truncate(stderr), stdoutBytes);
        synchronized (ENTRIES) {
            ENTRIES.addLast(e);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.removeFirst();
            }
        }
        for (Consumer<Entry> l : LISTENERS) {
            l.accept(e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT) + "\n… (" + (s.length() - MAX_TEXT)
                + " more characters)";
    }

    public static List<Entry> entries() {
        synchronized (ENTRIES) {
            return new ArrayList<>(ENTRIES);
        }
    }

    public static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
    }

    /** Listeners are called on the thread that ran git. */
    public static void addListener(Consumer<Entry> listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Consumer<Entry> listener) {
        LISTENERS.remove(listener);
    }
}
