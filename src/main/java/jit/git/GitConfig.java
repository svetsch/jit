package jit.git;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes git configuration in the global (~/.gitconfig) or repository-local scope. */
public final class GitConfig {
    public enum Scope { GLOBAL, LOCAL }

    private final Git git;
    private final Scope scope;

    private GitConfig(Git git, Scope scope) {
        this.git = git;
        this.scope = scope;
    }

    public static GitConfig global() {
        return new GitConfig(new Git(new File(System.getProperty("user.home"))), Scope.GLOBAL);
    }

    public static GitConfig local(Repo repo) {
        return new GitConfig(repo.git(), Scope.LOCAL);
    }

    public Scope scope() {
        return scope;
    }

    private String flag() {
        return scope == Scope.GLOBAL ? "--global" : "--local";
    }

    /** All entries of this scope; for multi-valued keys the last value wins. */
    public Map<String, String> list() throws GitException {
        Git.Result r = git.run(null, null, List.of("config", flag(), "--list", "-z"));
        Map<String, String> map = new LinkedHashMap<>();
        if (!r.ok()) {
            // a missing ~/.gitconfig is not an error
            return map;
        }
        for (String entry : r.out().split("\0")) {
            if (entry.isEmpty()) {
                continue;
            }
            int nl = entry.indexOf('\n');
            if (nl < 0) {
                map.put(entry, "true");
            } else {
                map.put(entry.substring(0, nl), entry.substring(nl + 1));
            }
        }
        return map;
    }

    public void set(String key, String value) throws GitException {
        git.exec("config", flag(), "--replace-all", key, value);
    }

    public void unset(String key) throws GitException {
        Git.Result r = git.run(null, null, List.of("config", flag(), "--unset-all", key));
        // exit code 5: the key was not set, which is what we wanted anyway
        if (!r.ok() && r.exitCode() != 5) {
            throw new GitException(r.err().strip(), List.of("config", flag(), "--unset-all", key));
        }
    }
}
