package jit.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** High level git operations on one working tree. All methods block; call them off the UI thread. */
public final class Repo {

    public enum State {
        NONE(""),
        MERGING("Merge in progress"),
        REBASING("Rebase in progress"),
        CHERRY_PICKING("Cherry-pick in progress"),
        REVERTING("Revert in progress");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    public enum ResetMode { SOFT, MIXED, HARD }

    /** A history entry of a single file: the commit and the file's path in that commit. */
    public record FileRevision(Commit commit, FileChange change) {
    }

    private static final String LOG_FORMAT = "%H%x1f%P%x1f%an%x1f%ae%x1f%at%x1f%cn%x1f%ce%x1f%ct%x1f%s";
    private static final String[] DIFF_OPTS = {"--no-ext-diff", "--src-prefix=a/", "--dst-prefix=b/"};

    private final Path root;
    private final Git git;
    private volatile Path gitDir;

    public Repo(Path root) {
        this.root = root;
        this.git = new Git(root.toFile());
    }

    public Path root() {
        return root;
    }

    public String name() {
        Path n = root.getFileName();
        return n == null ? root.toString() : n.toString();
    }

    public Git git() {
        return git;
    }

    // ------------------------------------------------------------------ repository level

    public static Path findRoot(Path dir) throws GitException {
        String top = new Git(dir.toFile()).exec("rev-parse", "--show-toplevel").strip();
        if (top.isEmpty()) {
            throw new GitException(dir + " is not inside a git working tree", List.of());
        }
        return Path.of(top).toAbsolutePath().normalize();
    }

    public static void init(Path dir) throws GitException {
        new Git(dir.toFile()).exec("init");
    }

    public static void clone(String url, Path target, Consumer<String> progress) throws GitException {
        Path parent = target.toAbsolutePath().getParent();
        new Git(parent.toFile()).execProgress(progress, List.of("clone", "--progress", url, target.toString()));
    }

    public Path gitDir() throws GitException {
        if (gitDir == null) {
            gitDir = Path.of(git.exec("rev-parse", "--absolute-git-dir").strip());
        }
        return gitDir;
    }

    public State state() throws GitException {
        Path d = gitDir();
        if (Files.isDirectory(d.resolve("rebase-merge")) || Files.isDirectory(d.resolve("rebase-apply"))) {
            return State.REBASING;
        }
        if (Files.exists(d.resolve("MERGE_HEAD"))) {
            return State.MERGING;
        }
        if (Files.exists(d.resolve("CHERRY_PICK_HEAD"))) {
            return State.CHERRY_PICKING;
        }
        if (Files.exists(d.resolve("REVERT_HEAD"))) {
            return State.REVERTING;
        }
        return State.NONE;
    }

    /** Message prepared by git for a merge / cherry-pick / revert in progress, without comment lines. */
    public String preparedMessage() throws GitException {
        Path f = gitDir().resolve("MERGE_MSG");
        if (!Files.exists(f)) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (!line.startsWith("#")) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString().strip();
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reading

    public String headBranch() throws GitException {
        Git.Result r = git.run(null, null, List.of("symbolic-ref", "-q", "--short", "HEAD"));
        return r.ok() ? r.out().strip() : null;
    }

    public String headHash() throws GitException {
        Git.Result r = git.run(null, null, List.of("rev-parse", "-q", "--verify", "HEAD"));
        return r.ok() ? r.out().strip() : null;
    }

    public List<Commit> log(int max) throws GitException {
        Git.Result r = git.run(null, null, List.of("log", "--exclude=refs/stash", "--all", "--date-order",
                "-n", Integer.toString(max), "--format=" + LOG_FORMAT + "%x1e"));
        if (!r.ok()) {
            if (headHash() == null) {
                return new ArrayList<>();
            }
            throw new GitException(r.err().strip(), List.of("log"));
        }
        return parseLog(r.out());
    }

    public Commit commit(String rev) throws GitException {
        List<Commit> list = parseLog(git.exec("log", "-1", "--format=" + LOG_FORMAT + "%x1e", rev, "--"));
        if (list.isEmpty()) {
            throw new GitException("Unknown revision " + rev, List.of());
        }
        return list.get(0);
    }

    private static List<Commit> parseLog(String out) {
        List<Commit> commits = new ArrayList<>();
        for (String rec : out.split("\u001e")) {
            String s = rec.strip();
            if (s.isEmpty()) {
                continue;
            }
            Commit c = parseCommit(s);
            if (c != null) {
                commits.add(c);
            }
        }
        return commits;
    }

    private static Commit parseCommit(String record) {
        String[] f = record.split("\u001f", -1);
        if (f.length < 9) {
            return null;
        }
        String[] parents = f[1].isBlank() ? new String[0] : f[1].strip().split(" ");
        return new Commit(f[0], parents, f[2], f[3], parseLong(f[4]), f[5], f[6], parseLong(f[7]), f[8]);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String commitMessage(String hash) throws GitException {
        return git.exec("show", "-s", "--format=%B", hash).strip();
    }

    public List<Ref> refs() throws GitException {
        String out = git.exec("for-each-ref",
                "--format=%(refname)%1f%(objectname)%1f%(*objectname)%1f%(upstream:short)%1f%(upstream:track,nobracket)%1f%(HEAD)",
                "refs/heads", "refs/remotes", "refs/tags");
        List<Ref> refs = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\u001f", -1);
            if (f.length < 6) {
                continue;
            }
            String full = f[0];
            String target = f[2].isEmpty() ? f[1] : f[2];
            Ref.Kind kind;
            String name;
            if (full.startsWith("refs/heads/")) {
                kind = Ref.Kind.LOCAL;
                name = full.substring("refs/heads/".length());
            } else if (full.startsWith("refs/remotes/")) {
                kind = Ref.Kind.REMOTE;
                name = full.substring("refs/remotes/".length());
                if (name.endsWith("/HEAD")) {
                    continue;
                }
            } else {
                kind = Ref.Kind.TAG;
                name = full.substring("refs/tags/".length());
            }
            int ahead = 0;
            int behind = 0;
            boolean gone = false;
            for (String part : f[4].split(",")) {
                String p = part.strip();
                if (p.startsWith("ahead ")) {
                    ahead = (int) parseLong(p.substring(6));
                } else if (p.startsWith("behind ")) {
                    behind = (int) parseLong(p.substring(7));
                } else if (p.equals("gone")) {
                    gone = true;
                }
            }
            refs.add(new Ref(kind, full, name, target, f[3].isEmpty() ? null : f[3], ahead, behind, gone,
                    "*".equals(f[5].strip())));
        }
        return refs;
    }

    /** Remote names mapped to their fetch URL. */
    public Map<String, String> remotes() throws GitException {
        Map<String, String> remotes = new LinkedHashMap<>();
        for (String name : git.exec("remote").split("\n")) {
            if (!name.isBlank()) {
                remotes.put(name.strip(), "");
            }
        }
        Git.Result r = git.run(null, null, List.of("config", "--get-regexp", "^remote\\..*\\.url$"));
        if (r.ok()) {
            for (String line : r.out().split("\n")) {
                int sp = line.indexOf(' ');
                if (sp < 0) {
                    continue;
                }
                String key = line.substring(0, sp);
                String name = key.substring("remote.".length(), key.length() - ".url".length());
                if (remotes.containsKey(name)) {
                    remotes.put(name, line.substring(sp + 1).strip());
                }
            }
        }
        return remotes;
    }

    public List<Stash> stashes() throws GitException {
        Git.Result r = git.run(null, null, List.of("stash", "list", "--format=%H%x1f%gd%x1f%s%x1f%ct"));
        List<Stash> list = new ArrayList<>();
        if (!r.ok()) {
            return list;
        }
        int i = 0;
        for (String line : r.out().split("\n")) {
            String[] f = line.split("\u001f", -1);
            if (f.length < 4) {
                continue;
            }
            list.add(new Stash(i++, f[1], f[0], f[2], parseLong(f[3])));
        }
        return list;
    }

    public List<StatusEntry> status() throws GitException {
        String out = git.exec("status", "--porcelain=v1", "-z", "--untracked-files=all");
        List<StatusEntry> entries = new ArrayList<>();
        String[] parts = out.split("\0");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() < 4) {
                continue;
            }
            char x = p.charAt(0);
            char y = p.charAt(1);
            String path = p.substring(3);
            String orig = null;
            if ((x == 'R' || x == 'C') && i + 1 < parts.length) {
                orig = parts[++i];
            }
            entries.add(new StatusEntry(path, orig, x, y));
        }
        entries.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
        return entries;
    }

    public List<FileChange> commitChanges(Commit c) throws GitException {
        List<String> args = new ArrayList<>(List.of("diff-tree", "--no-commit-id", "-r", "-M", "--name-status", "-z"));
        if (c.parents.length == 0) {
            args.add("--root");
        } else {
            args.add(c.parents[0]);
        }
        args.add(c.hash);
        String[] parts = git.exec(args).split("\0");
        List<FileChange> changes = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String st = parts[i];
            if (st.isEmpty()) {
                continue;
            }
            char s = st.charAt(0);
            if ((s == 'R' || s == 'C') && i + 2 < parts.length) {
                changes.add(new FileChange(s, parts[i + 2], parts[i + 1]));
                i += 2;
            } else if (i + 1 < parts.length) {
                changes.add(new FileChange(s, parts[i + 1], null));
                i++;
            }
        }
        return changes;
    }

    public String commitDiff(Commit c, FileChange f) throws GitException {
        List<String> args = new ArrayList<>();
        if (c.parents.length == 0) {
            args.addAll(List.of("show", "--format=", "-M"));
            args.addAll(List.of(DIFF_OPTS));
            args.add(c.hash);
        } else {
            args.addAll(List.of("diff", "-M"));
            args.addAll(List.of(DIFF_OPTS));
            args.add(c.parents[0]);
            args.add(c.hash);
        }
        args.add("--");
        if (f.oldPath() != null) {
            args.add(f.oldPath());
        }
        args.add(f.path());
        return git.exec(args);
    }

    public String workingDiff(StatusEntry e, boolean staged) throws GitException {
        List<String> args = new ArrayList<>();
        if (staged) {
            args.addAll(List.of("diff", "--cached", "-M"));
            args.addAll(List.of(DIFF_OPTS));
            args.add("--");
            if (e.origPath() != null) {
                args.add(e.origPath());
            }
            args.add(e.path());
            return git.exec(args);
        }
        if (e.isUntracked()) {
            args.addAll(List.of("diff", "--no-index"));
            args.addAll(List.of(DIFF_OPTS));
            args.addAll(List.of("--", "/dev/null", e.path()));
            Git.Result r = git.run(null, null, args);
            if (r.exitCode() > 1) {
                throw new GitException(r.err().strip(), args);
            }
            return r.out();
        }
        args.add("diff");
        args.addAll(List.of(DIFF_OPTS));
        args.add("--");
        args.add(e.path());
        return git.exec(args);
    }

    public List<FileRevision> fileHistory(String path, int max) throws GitException {
        String out = git.exec("log", "--follow", "-M", "-n", Integer.toString(max), "--name-status",
                "--format=%x1e" + LOG_FORMAT, "--", path);
        List<FileRevision> list = new ArrayList<>();
        for (String rec : out.split("\u001e")) {
            if (rec.isBlank()) {
                continue;
            }
            String[] lines = rec.split("\n");
            Commit c = parseCommit(lines[0]);
            if (c == null) {
                continue;
            }
            FileChange change = new FileChange('M', path, null);
            for (int i = 1; i < lines.length; i++) {
                String[] f = lines[i].split("\t");
                if (f.length >= 2 && !f[0].isEmpty()) {
                    char s = f[0].charAt(0);
                    change = f.length >= 3 ? new FileChange(s, f[2], f[1]) : new FileChange(s, f[1], null);
                    break;
                }
            }
            list.add(new FileRevision(c, change));
        }
        return list;
    }

    /**
     * Writes a file as it exists in a revision to {@code target}. An empty {@code rev} means the index;
     * a missing file (added / deleted side of a change) produces an empty file.
     */
    public void exportRevision(String rev, String path, Path target) throws GitException {
        try {
            Files.createDirectories(target.getParent());
            String spec = (rev == null ? "" : rev) + ":" + path;
            Git.Result exists = git.run(null, null, List.of("cat-file", "-e", spec));
            if (!exists.ok()) {
                Files.write(target, new byte[0]);
                return;
            }
            git.execToFile(target, List.of("cat-file", "blob", spec));
        } catch (IOException e) {
            throw new GitException("Cannot write " + target + ": " + e.getMessage(), List.of());
        }
    }

    public String blame(String path) throws GitException {
        return git.exec("blame", "--date=short", "--", path);
    }

    // ------------------------------------------------------------------ index & working tree

    private static String pathspec(Collection<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            sb.append(p).append('\0');
        }
        return sb.toString();
    }

    private static Set<String> allPaths(Collection<StatusEntry> entries) {
        Set<String> paths = new LinkedHashSet<>();
        for (StatusEntry e : entries) {
            paths.add(e.path());
            if (e.origPath() != null) {
                paths.add(e.origPath());
            }
        }
        return paths;
    }

    public void stage(Collection<StatusEntry> entries) throws GitException {
        if (entries.isEmpty()) {
            return;
        }
        Set<String> paths = new LinkedHashSet<>();
        entries.forEach(e -> paths.add(e.path()));
        git.execInput(pathspec(paths), "add", "-A", "--pathspec-from-file=-", "--pathspec-file-nul");
    }

    public void unstage(Collection<StatusEntry> entries) throws GitException {
        if (entries.isEmpty()) {
            return;
        }
        String spec = pathspec(allPaths(entries));
        if (headHash() != null) {
            git.execInput(spec, "reset", "-q", "HEAD", "--pathspec-from-file=-", "--pathspec-file-nul");
        } else {
            git.execInput(spec, "rm", "--cached", "-r", "-q", "--pathspec-from-file=-", "--pathspec-file-nul");
        }
    }

    /** Throws away unstaged changes; untracked files are deleted. */
    public void discard(Collection<StatusEntry> entries) throws GitException {
        List<String> tracked = new ArrayList<>();
        for (StatusEntry e : entries) {
            if (e.isUntracked()) {
                try {
                    Files.deleteIfExists(root.resolve(e.path()));
                } catch (IOException ex) {
                    throw new GitException("Cannot delete " + e.path() + ": " + ex.getMessage(), List.of());
                }
            } else {
                tracked.add(e.path());
            }
        }
        if (!tracked.isEmpty()) {
            git.execInput(pathspec(tracked), "checkout", "--pathspec-from-file=-", "--pathspec-file-nul");
        }
    }

    public void applyPatch(String patch, boolean cached, boolean reverse) throws GitException {
        List<String> args = new ArrayList<>(List.of("apply", "--recount", "--whitespace=nowarn"));
        if (cached) {
            args.add("--cached");
        }
        if (reverse) {
            args.add("-R");
        }
        args.add("-");
        git.execInput(patch, args.toArray(String[]::new));
    }

    public void resolve(StatusEntry e, boolean ours) throws GitException {
        git.exec("checkout", ours ? "--ours" : "--theirs", "--", e.path());
        git.exec("add", "--", e.path());
    }

    /**
     * Options of {@link #commit(String, CommitOptions)}.
     *
     * @param keepMessage when amending, keep the previous message ({@code message} is ignored)
     * @param resetAuthor when amending, make the current user the author and update the date
     */
    public record CommitOptions(boolean amend, boolean keepMessage, boolean resetAuthor, boolean signoff,
                                boolean noVerify, boolean allowEmpty) {
        public static final CommitOptions DEFAULT = new CommitOptions(false, false, false, false, false, false);
    }

    public void commit(String message, boolean amend) throws GitException {
        commit(message, new CommitOptions(amend, false, false, false, false, false));
    }

    public void commit(String message, CommitOptions o) throws GitException {
        List<String> args = new ArrayList<>(List.of("commit"));
        boolean keep = o.amend() && o.keepMessage();
        if (keep) {
            args.add("--no-edit");
        } else {
            args.addAll(List.of("-F", "-"));
        }
        if (o.amend()) {
            args.add("--amend");
        }
        if (o.amend() && o.resetAuthor()) {
            args.add("--reset-author");
        }
        if (o.signoff()) {
            args.add("--signoff");
        }
        if (o.noVerify()) {
            args.add("--no-verify");
        }
        if (o.allowEmpty()) {
            args.add("--allow-empty");
        }
        git.execInput(keep ? null : message, args.toArray(String[]::new));
    }

    public String lastCommitMessage() throws GitException {
        return headHash() == null ? "" : git.exec("log", "-1", "--format=%B").strip();
    }

    /** Distinct messages of the latest commits on HEAD, newest first. */
    public List<String> recentMessages(int max) throws GitException {
        if (headHash() == null) {
            return new ArrayList<>();
        }
        String out = git.exec("log", "-n", Integer.toString(max * 2), "--format=%B%x1e", "HEAD");
        Set<String> seen = new LinkedHashSet<>();
        for (String m : out.split("\u001e")) {
            String msg = m.strip();
            if (!msg.isEmpty() && !msg.startsWith("Merge ") && seen.size() < max) {
                seen.add(msg);
            }
        }
        return new ArrayList<>(seen);
    }

    // ------------------------------------------------------------------ branches & tags

    public void checkout(String branch) throws GitException {
        git.exec("checkout", "-q", branch, "--");
    }

    public void checkoutTracking(Ref remoteBranch, String localName) throws GitException {
        git.exec("checkout", "-q", "-b", localName, "--track", remoteBranch.name);
    }

    public void checkoutDetached(String rev) throws GitException {
        git.exec("checkout", "-q", "--detach", rev);
    }

    public void createBranch(String name, String start, boolean checkout) throws GitException {
        if (checkout) {
            git.exec("checkout", "-q", "-b", name, start);
        } else {
            git.exec("branch", name, start);
        }
    }

    public void renameBranch(String oldName, String newName) throws GitException {
        git.exec("branch", "-m", oldName, newName);
    }

    public void deleteBranch(String name, boolean force) throws GitException {
        git.exec("branch", force ? "-D" : "-d", name);
    }

    public void deleteRemoteBranch(Ref remoteBranch, Consumer<String> progress) throws GitException {
        git.execProgress(progress, List.of("push", "--progress", remoteBranch.remote(), "--delete",
                remoteBranch.remoteBranch()));
    }

    public void setUpstream(String local, String upstream) throws GitException {
        git.exec("branch", "--set-upstream-to=" + upstream, local);
    }

    public void createTag(String name, String rev, String message) throws GitException {
        if (message == null || message.isBlank()) {
            git.exec("tag", name, rev);
        } else {
            git.execInput(message, "tag", "-a", name, "-F", "-", rev);
        }
    }

    public void deleteTag(String name) throws GitException {
        git.exec("tag", "-d", name);
    }

    public void pushTag(String remote, String tag, Consumer<String> progress) throws GitException {
        git.execProgress(progress, List.of("push", "--progress", remote, "refs/tags/" + tag));
    }

    public void deleteRemoteTag(String remote, String tag, Consumer<String> progress) throws GitException {
        git.execProgress(progress, List.of("push", "--progress", remote, "--delete", "refs/tags/" + tag));
    }

    public void merge(String rev, boolean noFastForward, boolean squash) throws GitException {
        List<String> args = new ArrayList<>(List.of("merge", "--no-edit"));
        if (squash) {
            args.add("--squash");
        } else if (noFastForward) {
            args.add("--no-ff");
        }
        args.add(rev);
        git.exec(args);
    }

    public void rebase(String rev) throws GitException {
        git.exec("rebase", rev);
    }

    /** One line of an interactive rebase plan. */
    public enum RebaseAction { PICK, REWORD, EDIT, SQUASH, FIXUP, DROP }

    /**
     * @param message new message for {@link RebaseAction#REWORD}; ignored otherwise
     */
    public record RebaseStep(RebaseAction action, Commit commit, String message) {
    }

    /** Commits that an interactive rebase onto {@code base} (null = root) would replay, oldest first. */
    public List<Commit> rebaseCommits(String base) throws GitException {
        List<String> args = new ArrayList<>(List.of("log", "--reverse", "--no-merges", "--topo-order",
                "--format=" + LOG_FORMAT + "%x1e"));
        args.add(base == null ? "HEAD" : base + "..HEAD");
        return parseLog(git.exec(args));
    }

    public int mergeCount(String base) throws GitException {
        String out = git.exec("rev-list", "--merges", "--count", base == null ? "HEAD" : base + "..HEAD").strip();
        return (int) parseLong(out);
    }

    public boolean isAncestorOfHead(String rev) throws GitException {
        return git.run(null, null, List.of("merge-base", "--is-ancestor", rev, "HEAD")).ok();
    }

    /**
     * Runs {@code git rebase -i} with a prepared plan instead of an editor. Rewording is done with an
     * {@code exec git commit --amend} step so no editor is ever needed. When the rebase stops (edit or
     * conflicts) the repository is left in {@link State#REBASING} and can be continued or aborted.
     */
    public void interactiveRebase(String base, List<RebaseStep> steps, boolean autostash) throws GitException {
        try {
            Path dir = Files.createTempDirectory("jit-rebase");
            dir.toFile().deleteOnExit();
            StringBuilder todo = new StringBuilder();
            int n = 0;
            for (RebaseStep s : steps) {
                String verb = switch (s.action()) {
                    case PICK, REWORD -> "pick";
                    case EDIT -> "edit";
                    case SQUASH -> "squash";
                    case FIXUP -> "fixup";
                    case DROP -> "drop";
                };
                todo.append(verb).append(' ').append(s.commit().hash).append(' ')
                        .append(s.commit().subject.replace("\n", " ")).append('\n');
                if (s.action() == RebaseAction.REWORD && s.message() != null && !s.message().isBlank()) {
                    Path msg = dir.resolve("message-" + (n++) + ".txt");
                    Files.writeString(msg, s.message().strip() + "\n", StandardCharsets.UTF_8);
                    msg.toFile().deleteOnExit();
                    todo.append("exec git commit --amend --only --allow-empty --no-verify -F ")
                            .append(shellQuote(msg)).append('\n');
                }
            }
            Path todoFile = dir.resolve("todo.txt");
            Files.writeString(todoFile, todo.toString(), StandardCharsets.UTF_8);
            todoFile.toFile().deleteOnExit();

            List<String> args = new ArrayList<>(List.of("rebase", "-i"));
            if (autostash) {
                args.add("--autostash");
            }
            args.add(base == null ? "--root" : base);
            Git.Result r = git.run(null, null, args,
                    Map.of("GIT_SEQUENCE_EDITOR", "cp " + shellQuote(todoFile), "GIT_EDITOR", "true"));
            if (!r.ok()) {
                String msg = r.err().strip();
                throw new GitException(msg.isEmpty() ? r.out().strip() : msg, args);
            }
        } catch (IOException e) {
            throw new GitException("Cannot prepare the rebase: " + e.getMessage(), List.of());
        }
    }

    /** Quotes a path for git's POSIX shell (forward slashes work on Windows too). */
    private static String shellQuote(Path p) {
        return "'" + p.toAbsolutePath().toString().replace('\\', '/').replace("'", "'\\''") + "'";
    }

    /** Moves the current branch back to {@code rev} keeping local changes (used to undo a rebase). */
    public void resetKeep(String rev) throws GitException {
        git.exec("reset", "--keep", rev);
    }

    public void cherryPick(Commit c) throws GitException {
        if (c.isMerge()) {
            git.exec("cherry-pick", "-m", "1", c.hash);
        } else {
            git.exec("cherry-pick", c.hash);
        }
    }

    public void revert(Commit c) throws GitException {
        if (c.isMerge()) {
            git.exec("revert", "--no-edit", "-m", "1", c.hash);
        } else {
            git.exec("revert", "--no-edit", c.hash);
        }
    }

    public void reset(String rev, ResetMode mode) throws GitException {
        git.exec("reset", "--" + mode.name().toLowerCase(), rev);
    }

    /** Continues or aborts the operation in progress. */
    public void continueOrAbort(State state, boolean abort) throws GitException {
        String action = abort ? "--abort" : "--continue";
        switch (state) {
            case MERGING -> {
                if (abort) {
                    git.exec("merge", "--abort");
                } else {
                    git.exec("commit", "--no-edit");
                }
            }
            case REBASING -> git.exec("rebase", action);
            case CHERRY_PICKING -> git.exec("cherry-pick", action);
            case REVERTING -> git.exec("revert", action);
            default -> {
            }
        }
    }

    public void skipRebaseCommit() throws GitException {
        git.exec("rebase", "--skip");
    }

    // ------------------------------------------------------------------ remotes

    public void fetch(String remote, boolean prune, boolean tags, Consumer<String> progress) throws GitException {
        List<String> args = new ArrayList<>(List.of("fetch", "--progress"));
        if (prune) {
            args.add("--prune");
        }
        if (tags) {
            args.add("--tags");
        }
        args.add(remote == null ? "--all" : remote);
        git.execProgress(progress, args);
    }

    public void pull(String remote, String branch, boolean rebase, boolean autostash, Consumer<String> progress)
            throws GitException {
        List<String> args = new ArrayList<>(List.of("pull", "--progress", rebase ? "--rebase" : "--no-rebase"));
        if (autostash) {
            args.add("--autostash");
        }
        if (remote != null) {
            args.add(remote);
            if (branch != null) {
                args.add(branch);
            }
        }
        git.execProgress(progress, args);
    }

    public void push(String remote, String local, String remoteBranch, boolean setUpstream, boolean force,
                     boolean tags, Consumer<String> progress) throws GitException {
        List<String> args = new ArrayList<>(List.of("push", "--progress"));
        if (setUpstream) {
            args.add("--set-upstream");
        }
        if (force) {
            args.add("--force-with-lease");
        }
        if (tags) {
            args.add("--follow-tags");
        }
        args.add(remote);
        args.add("refs/heads/" + local + ":refs/heads/" + remoteBranch);
        git.execProgress(progress, args);
    }

    public void addRemote(String name, String url) throws GitException {
        git.exec("remote", "add", name, url);
    }

    public void removeRemote(String name) throws GitException {
        git.exec("remote", "remove", name);
    }

    public void setRemoteUrl(String name, String url) throws GitException {
        git.exec("remote", "set-url", name, url);
    }

    // ------------------------------------------------------------------ stashes

    public void stash(String message, boolean includeUntracked, boolean keepIndex) throws GitException {
        List<String> args = new ArrayList<>(List.of("stash", "push"));
        if (includeUntracked) {
            args.add("--include-untracked");
        }
        if (keepIndex) {
            args.add("--keep-index");
        }
        if (message != null && !message.isBlank()) {
            args.add("-m");
            args.add(message);
        }
        git.exec(args);
    }

    public void stashApply(Stash s, boolean pop) throws GitException {
        git.exec("stash", pop ? "pop" : "apply", s.ref());
    }

    public void stashDrop(Stash s) throws GitException {
        git.exec("stash", "drop", s.ref());
    }
}
