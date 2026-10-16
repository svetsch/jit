package jit.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jit.diff.DiffLine;
import jit.diff.DiffParser;
import jit.diff.FileDiff;
import jit.diff.PatchBuilder;

/** Integration tests against a real git executable. */
class RepoTest {
    @TempDir
    Path dir;
    Repo repo;

    @BeforeEach
    void setUp() throws Exception {
        Repo.init(dir);
        repo = new Repo(Repo.findRoot(dir));
        repo.git().exec("config", "user.name", "Test User");
        repo.git().exec("config", "user.email", "test@example.com");
        repo.git().exec("config", "core.autocrlf", "false");
        repo.git().exec("config", "commit.gpgsign", "false");
    }

    private void write(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private String read(String name) throws IOException {
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    }

    private static String lines(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> "line " + i + "\n").collect(Collectors.joining());
    }

    private String indexContent(String path) throws GitException {
        return repo.git().exec("show", ":" + path);
    }

    private StatusEntry entry(String path) throws GitException {
        return repo.status().stream().filter(e -> e.path().equals(path)).findFirst().orElseThrow();
    }

    private void commitAll(String msg) throws GitException {
        repo.stage(repo.status());
        repo.commit(msg, false);
    }

    @Test
    void testsCannotReachTheNetwork() throws Exception {
        // the Surefire configuration sets GIT_ALLOW_PROTOCOL=file: git refuses before connecting anywhere
        Git.Result r = repo.git().run(null, null, List.of("ls-remote", "https://github.com/git/git.git"));
        assertFalse(r.ok());
        assertTrue(r.err().contains("not allowed"), r.err());
    }

    @Test
    void emptyRepositoryHasNoHistory() throws Exception {
        assertTrue(repo.log(100).isEmpty());
        assertNull(repo.headHash());
        assertEquals(Repo.State.NONE, repo.state());
    }

    @Test
    void commitLogRefsAndStatus() throws Exception {
        write("a.txt", "hello\n");
        commitAll("First commit\n\nWith a body");
        repo.createTag("v1", "HEAD", "Release one");
        repo.createBranch("feature/x", "HEAD", false);
        List<Commit> log = repo.log(100);
        assertEquals(1, log.size());
        assertEquals("First commit", log.get(0).subject);
        assertEquals("Test User", log.get(0).author);
        assertEquals("First commit\n\nWith a body", repo.commitMessage(log.get(0).hash));

        List<Ref> refs = repo.refs();
        assertTrue(refs.stream().anyMatch(r -> r.kind == Ref.Kind.LOCAL && r.name.equals("feature/x") && !r.head));
        Ref tag = refs.stream().filter(r -> r.kind == Ref.Kind.TAG).findFirst().orElseThrow();
        assertEquals(log.get(0).hash, tag.target, "annotated tag must be peeled to its commit");

        write("b.txt", "new\n");
        Files.move(dir.resolve("a.txt"), dir.resolve("renamed.txt"));
        repo.stage(repo.status());
        List<StatusEntry> st = repo.status();
        StatusEntry renamed = st.stream().filter(e -> e.x() == 'R').findFirst().orElseThrow();
        assertEquals("renamed.txt", renamed.path());
        assertEquals("a.txt", renamed.origPath());
        repo.unstage(st);
        assertTrue(repo.status().stream().noneMatch(StatusEntry::hasStaged));
    }

    @Test
    void stageAndUnstageSingleHunk() throws Exception {
        write("f.txt", lines(1, 30));
        commitAll("base");
        write("f.txt", lines(1, 30).replace("line 2\n", "line two\n").replace("line 25\n", "line twenty-five\n"));

        FileDiff diff = DiffParser.parse(repo.workingDiff(entry("f.txt"), false));
        assertEquals(2, diff.hunks.size());
        String patch = PatchBuilder.build(diff, l -> l.hunk == diff.hunks.get(0), false);
        repo.applyPatch(patch, true, false);

        String index = indexContent("f.txt");
        assertTrue(index.contains("line two\n"));
        assertTrue(index.contains("line 25\n"));

        FileDiff staged = DiffParser.parse(repo.workingDiff(entry("f.txt"), true));
        assertEquals(1, staged.hunks.size());
        repo.applyPatch(PatchBuilder.build(staged, l -> true, true), true, true);
        assertEquals(lines(1, 30), indexContent("f.txt"));
    }

    @Test
    void stageAndUnstageSelectedLines() throws Exception {
        write("f.txt", lines(1, 10));
        commitAll("base");
        write("f.txt", lines(1, 10).replace("line 5\n", "line five\nextra A\nextra B\n"));

        FileDiff diff = DiffParser.parse(repo.workingDiff(entry("f.txt"), false));
        // stage only "extra B"
        String patch = PatchBuilder.build(diff, l -> l.type == DiffLine.Type.ADD && l.text.equals("extra B"), false);
        repo.applyPatch(patch, true, false);
        assertEquals(lines(1, 10).replace("line 5\n", "line 5\nextra B\n"), indexContent("f.txt"));

        // stage the deletion of "line 5" and "line five" too
        diff = DiffParser.parse(repo.workingDiff(entry("f.txt"), false));
        patch = PatchBuilder.build(diff, l -> l.text.startsWith("line 5") || l.text.equals("line five"), false);
        repo.applyPatch(patch, true, false);
        assertEquals(lines(1, 10).replace("line 5\n", "line five\nextra B\n"), indexContent("f.txt"));

        // unstage just "extra B"
        FileDiff staged = DiffParser.parse(repo.workingDiff(entry("f.txt"), true));
        patch = PatchBuilder.build(staged, l -> l.type == DiffLine.Type.ADD && l.text.equals("extra B"), true);
        repo.applyPatch(patch, true, true);
        assertEquals(lines(1, 10).replace("line 5\n", "line five\n"), indexContent("f.txt"));
        // work tree untouched
        assertEquals(lines(1, 10).replace("line 5\n", "line five\nextra A\nextra B\n"), read("f.txt"));
    }

    @Test
    void discardSelectedLinesFromWorkTree() throws Exception {
        write("f.txt", lines(1, 10));
        commitAll("base");
        write("f.txt", lines(1, 10).replace("line 3\n", "").replace("line 8\n", "line 8\nadded\n"));
        FileDiff diff = DiffParser.parse(repo.workingDiff(entry("f.txt"), false));
        // discard the removal of line 3 only
        String patch = PatchBuilder.build(diff, l -> l.type == DiffLine.Type.DELETE, true);
        repo.applyPatch(patch, false, true);
        assertEquals(lines(1, 10).replace("line 8\n", "line 8\nadded\n"), read("f.txt"));
    }

    @Test
    void partiallyStageUntrackedFile() throws Exception {
        write("base.txt", "x\n");
        commitAll("base");
        write("new.txt", "keep\nskip\n");
        StatusEntry e = entry("new.txt");
        assertTrue(e.isUntracked());
        FileDiff diff = DiffParser.parse(repo.workingDiff(e, false));
        String patch = PatchBuilder.build(diff, l -> l.text.equals("keep"), false);
        repo.applyPatch(patch, true, false);
        assertEquals("keep\n", indexContent("new.txt"));
    }

    @Test
    void crlfFilesCanBeStagedByLine() throws Exception {
        write("w.txt", "a\r\nb\r\nc\r\n");
        commitAll("base");
        write("w.txt", "a\r\nB1\r\nB2\r\nc\r\n");
        FileDiff diff = DiffParser.parse(repo.workingDiff(entry("w.txt"), false));
        String patch = PatchBuilder.build(diff, l -> l.text.equals("B1"), false);
        repo.applyPatch(patch, true, false);
        assertEquals("a\r\nb\r\nB1\r\nc\r\n", indexContent("w.txt"));
    }

    @Test
    void stashBranchMergeConflictAndAbort() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        String mainBranch = repo.headBranch();
        assertNotNull(mainBranch);

        write("f.txt", "stashed\n");
        repo.stash("my stash", true, false);
        assertEquals(1, repo.stashes().size());
        assertEquals("one\n", read("f.txt"));
        Commit stashCommit = repo.commit(repo.stashes().get(0).hash());
        assertFalse(repo.commitChanges(stashCommit).isEmpty());
        repo.stashApply(repo.stashes().get(0), true);
        assertEquals("stashed\n", read("f.txt"));
        assertTrue(repo.stashes().isEmpty());
        repo.discard(repo.status());
        assertTrue(repo.status().isEmpty());

        repo.createBranch("other", "HEAD", true);
        write("f.txt", "theirs\n");
        commitAll("other change");
        repo.checkout(mainBranch);
        write("f.txt", "ours\n");
        commitAll("main change");

        assertThrows(GitException.class, () -> repo.merge("other", false, false));
        assertEquals(Repo.State.MERGING, repo.state());
        assertTrue(repo.status().stream().anyMatch(StatusEntry::isConflict));
        assertTrue(repo.preparedMessage().contains("other"));
        repo.continueOrAbort(Repo.State.MERGING, true);
        assertEquals(Repo.State.NONE, repo.state());
        assertEquals("ours\n", read("f.txt"));

        assertThrows(GitException.class, () -> repo.merge("other", false, false));
        repo.resolve(entry("f.txt"), false);
        repo.continueOrAbort(Repo.State.MERGING, false);
        assertEquals(Repo.State.NONE, repo.state());
        assertEquals("theirs\n", read("f.txt"));
        assertEquals(2, repo.log(10).get(0).parents.length);
    }

    @Test
    void pushPullAndFetchWithLocalRemote(@TempDir Path remoteDir) throws Exception {
        new Git(remoteDir.toFile()).exec("init", "--bare", "-q");
        write("f.txt", "one\n");
        commitAll("base");
        String branch = repo.headBranch();
        repo.addRemote("origin", remoteDir.toString());
        repo.push("origin", branch, branch, true, false, false, null);
        Ref head = repo.refs().stream().filter(r -> r.head).findFirst().orElseThrow();
        assertEquals("origin/" + branch, head.upstream);
        assertEquals(0, head.ahead);

        write("f.txt", "two\n");
        commitAll("second");
        head = repo.refs().stream().filter(r -> r.head).findFirst().orElseThrow();
        assertEquals(1, head.ahead);
        repo.fetch(null, true, false, null);
        repo.pull("origin", branch, false, true, null);
        assertEquals(1, repo.remotes().size());
        assertEquals(remoteDir.toString(), repo.remotes().get("origin"));
    }

    @Test
    void interactiveRebaseReordersRewordsSquashesAndDrops() throws Exception {
        write("base.txt", "base\n");
        commitAll("base");
        String base = repo.headHash();
        for (String n : List.of("a", "b", "c", "d")) {
            write(n + ".txt", n + "\n");
            commitAll("add " + n);
        }
        List<Commit> commits = repo.rebaseCommits(base);
        assertEquals(List.of("add a", "add b", "add c", "add d"), commits.stream().map(c -> c.subject).toList());
        // new order: d (reworded), a, b squashed into a, c dropped
        List<Repo.RebaseStep> plan = List.of(
                new Repo.RebaseStep(Repo.RebaseAction.REWORD, commits.get(3), "add d, reworded\n\nwith body"),
                new Repo.RebaseStep(Repo.RebaseAction.PICK, commits.get(0), null),
                new Repo.RebaseStep(Repo.RebaseAction.FIXUP, commits.get(1), null),
                new Repo.RebaseStep(Repo.RebaseAction.DROP, commits.get(2), null));
        repo.interactiveRebase(base, plan, false);
        assertEquals(Repo.State.NONE, repo.state());
        List<Commit> after = repo.log(10);
        assertEquals(List.of("add a", "add d, reworded", "base"), after.stream().map(c -> c.subject).toList());
        assertEquals("add d, reworded\n\nwith body", repo.commitMessage(after.get(1).hash));
        assertTrue(Files.exists(dir.resolve("b.txt")));
        assertFalse(Files.exists(dir.resolve("c.txt")));

        // an "edit" step stops the rebase; aborting restores the branch completely
        String before = repo.headHash();
        List<Commit> again = repo.rebaseCommits(base);
        repo.interactiveRebase(base, List.of(
                new Repo.RebaseStep(Repo.RebaseAction.EDIT, again.get(0), null),
                new Repo.RebaseStep(Repo.RebaseAction.PICK, again.get(1), null)), false);
        assertEquals(Repo.State.REBASING, repo.state());
        repo.continueOrAbort(Repo.State.REBASING, true);
        assertEquals(Repo.State.NONE, repo.state());
        assertEquals(before, repo.headHash());
    }

    @Test
    void commitOptionsAndRecentMessages() throws Exception {
        write("f.txt", "1\n");
        commitAll("first message");
        write("f.txt", "2\n");
        repo.stage(repo.status());
        repo.commit("second message", Repo.CommitOptions.DEFAULT);
        write("f.txt", "3\n");
        repo.stage(repo.status());
        // amend keeping the message, with sign-off
        repo.commit(null, new Repo.CommitOptions(true, true, false, true, true, false));
        List<Commit> log = repo.log(10);
        assertEquals(2, log.size());
        assertTrue(repo.commitMessage(log.get(0).hash).startsWith("second message"));
        assertTrue(repo.commitMessage(log.get(0).hash).contains("Signed-off-by: Test User"));
        repo.commit("empty one", new Repo.CommitOptions(false, false, false, false, false, true));
        assertEquals(List.of("empty one", "second message\n\nSigned-off-by: Test User <test@example.com>",
                "first message"), repo.recentMessages(10));
    }

    @Test
    void globalAndLocalConfig() throws Exception {
        GitConfig local = GitConfig.local(repo);
        local.set("jit.test", "hello world");
        assertEquals("hello world", local.list().get("jit.test"));
        local.unset("jit.test");
        local.unset("jit.test");
        assertNull(local.list().get("jit.test"));
    }

    @Test
    void commandsAreRecordedInTheLog() throws Exception {
        CommandLog.clear();
        repo.status();
        List<CommandLog.Entry> entries = CommandLog.entries();
        assertFalse(entries.isEmpty());
        CommandLog.Entry e = entries.get(entries.size() - 1);
        assertEquals("status", e.subcommand());
        assertTrue(e.isQuery());
        assertTrue(e.commandLine().startsWith("git status --porcelain=v1"));
        assertEquals(0, e.exitCode());
    }

    @Test
    void exportRevisionWritesBinarySafeContent() throws Exception {
        Files.write(dir.resolve("bin.dat"), new byte[]{0, 1, 2, (byte) 0xff, 10, 13});
        commitAll("binary");
        Path out = dir.resolve("export/bin.dat");
        repo.exportRevision("HEAD", "bin.dat", out);
        assertArrayEqualsBytes(new byte[]{0, 1, 2, (byte) 0xff, 10, 13}, Files.readAllBytes(out));
        Path missing = dir.resolve("export/missing.txt");
        repo.exportRevision("HEAD", "missing.txt", missing);
        assertEquals(0, Files.size(missing));
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    @Test
    void historyCanBeFilteredToBranchesOrHideBranches() throws Exception {
        write("f.txt", "base\n");
        commitAll("base");
        String main = repo.headBranch();
        repo.createBranch("side", "HEAD", true);
        write("s.txt", "side\n");
        commitAll("side work");
        repo.checkout(main);
        write("m.txt", "main\n");
        commitAll("main work");

        assertEquals(java.util.Set.of("main work", "side work", "base"), java.util.Set.copyOf(subjects(repo.log(10))));
        assertEquals(List.of("side work", "base"), subjects(repo.log(10, List.of("refs/heads/side"), List.of())));
        assertEquals(List.of("main work", "base"), subjects(repo.log(10, List.of(), List.of("refs/heads/side"))));
        // hiding the checked-out branch keeps it visible through HEAD
        assertTrue(subjects(repo.log(10, List.of(), List.of("refs/heads/" + main))).contains("main work"));
    }

    private static List<String> subjects(List<Commit> commits) {
        return commits.stream().map(c -> c.subject).toList();
    }

    @Test
    void commitPatchesRoundTripThroughGitAm() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        String base = repo.headHash();
        write("f.txt", "one\ntwo\n");
        commitAll("second");
        write("g.txt", "new\n");
        commitAll("third");
        Path patch = dir.resolve("../series.patch").normalize();
        Files.writeString(patch, repo.formatPatches(base), StandardCharsets.UTF_8);
        assertTrue(Repo.isMailbox(patch));
        assertTrue(repo.patchStat(patch).contains("g.txt"));

        repo.reset(base, Repo.ResetMode.HARD);
        assertNull(repo.checkPatch(patch, Repo.PatchMode.COMMIT));
        repo.applyPatchFile(patch, Repo.PatchMode.COMMIT, true);
        assertEquals(List.of("third", "second", "base"), subjects(repo.log(10)));
        assertEquals("Test User", repo.log(1).get(0).author);

        // conflicting mailbox: git am stops, the state is detected and abort restores everything
        repo.reset(base, Repo.ResetMode.HARD);
        write("f.txt", "conflicting\n");
        commitAll("other");
        String before = repo.headHash();
        assertThrows(GitException.class, () -> repo.applyPatchFile(patch, Repo.PatchMode.COMMIT, false));
        assertEquals(Repo.State.APPLYING_PATCHES, repo.state());
        repo.continueOrAbort(Repo.State.APPLYING_PATCHES, true);
        assertEquals(Repo.State.NONE, repo.state());
        assertEquals(before, repo.headHash());
    }

    @Test
    void workingTreePatchIncludesNewFilesAndAppliesToIndex() throws Exception {
        write("f.txt", "one\n");
        commitAll("base");
        write("f.txt", "one\nchanged\n");
        write("new.txt", "brand new\n");
        Path patch = dir.resolve("../changes.patch").normalize();
        Files.writeString(patch, repo.workingPatch(repo.status(), false), StandardCharsets.UTF_8);
        assertFalse(Repo.isMailbox(patch));

        repo.discard(repo.status());
        assertTrue(repo.status().isEmpty());
        repo.applyPatchFile(patch, Repo.PatchMode.STAGE, false);
        assertEquals("one\nchanged\n", indexContent("f.txt"));
        assertEquals("brand new\n", read("new.txt"));
        assertTrue(repo.status().stream().allMatch(StatusEntry::hasStaged));
    }

    @Test
    void backgroundFetchUpdatesBehindCount(@TempDir Path remoteDir, @TempDir Path otherDir) throws Exception {
        new Git(remoteDir.toFile()).exec("init", "--bare", "-q");
        write("f.txt", "one\n");
        commitAll("base");
        String branch = repo.headBranch();
        repo.addRemote("origin", remoteDir.toString());
        repo.push("origin", branch, branch, true, false, false, null);

        // someone else pushes a commit
        Path clone = otherDir.resolve("clone");
        Repo.clone(remoteDir.toString(), clone, null);
        Repo other = new Repo(clone);
        other.git().exec("config", "user.name", "Other");
        other.git().exec("config", "user.email", "other@example.com");
        Files.writeString(clone.resolve("g.txt"), "two\n");
        other.stage(other.status());
        other.commit("from elsewhere", false);
        other.git().exec("push", "-q", "origin", "HEAD");

        assertEquals(0, repo.refs().stream().filter(r -> r.head).findFirst().orElseThrow().behind);
        repo.fetchInBackground(true);
        assertEquals(1, repo.refs().stream().filter(r -> r.head).findFirst().orElseThrow().behind);
    }

    @Test
    void processHookSeesEveryGitProcess() throws Exception {
        List<Process> started = new java.util.ArrayList<>();
        Git.onProcessStart(started::add);
        try {
            repo.status();
            repo.headBranch();
        } finally {
            Git.onProcessStart(null);
        }
        assertEquals(2, started.size());
        repo.status();
        assertEquals(2, started.size());
    }

    @Test
    void fileHistoryFollowsRenames() throws Exception {
        write("a.txt", "content that is long enough to be detected as rename\n");
        commitAll("add a");
        Files.move(dir.resolve("a.txt"), dir.resolve("b.txt"));
        commitAll("rename to b");
        List<Repo.FileRevision> history = repo.fileHistory("b.txt", 100);
        assertEquals(2, history.size());
        assertEquals('R', history.get(0).change().status());
        assertEquals("a.txt", history.get(0).change().oldPath());
        assertEquals("a.txt", history.get(1).change().path());
        String diff = repo.commitDiff(history.get(1).commit(), history.get(1).change());
        assertTrue(diff.contains("+content"));
    }
}
