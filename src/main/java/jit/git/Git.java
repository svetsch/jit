package jit.git;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Thin wrapper around the git command line executable. */
public final class Git {
    private static volatile String executable = "git";

    private final File dir;

    public Git(File dir) {
        this.dir = dir;
    }

    public static void setExecutable(String exe) {
        executable = exe;
    }

    public record Result(int exitCode, String out, String err) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * Runs git and returns its result without checking the exit code.
     *
     * @param stdin    text written to git's standard input, or {@code null}
     * @param progress receives stderr lines while git runs (progress output), or {@code null}
     */
    public Result run(String stdin, Consumer<String> progress, List<String> args) throws GitException {
        return run(stdin, progress, args, Map.of());
    }

    /** Like {@link #run(String, Consumer, List)} with extra environment variables. */
    public Result run(String stdin, Consumer<String> progress, List<String> args, Map<String, String> extraEnv)
            throws GitException {
        List<String> cmd = new ArrayList<>(args.size() + 8);
        cmd.add(executable);
        cmd.addAll(List.of("-c", "core.quotepath=false", "-c", "color.ui=false",
                "-c", "i18n.logOutputEncoding=UTF-8"));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) {
            pb.directory(dir);
        }
        Map<String, String> env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_OPTIONAL_LOCKS", "0");
        env.put("GIT_EDITOR", "true");
        env.put("GIT_MERGE_AUTOEDIT", "no");
        env.putAll(extraEnv);

        long started = System.nanoTime();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            CommandLog.record(dir, args, -1, 0, stdin, "Unable to start git: " + e.getMessage(), 0);
            throw new GitException("Unable to start git: " + e.getMessage(), cmd);
        }
        StringBuilder err = new StringBuilder();
        Thread errReader = new Thread(() -> readStderr(process.getErrorStream(), err, progress), "git-stderr");
        errReader.setDaemon(true);
        errReader.start();
        try {
            try (OutputStream os = process.getOutputStream()) {
                if (stdin != null) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // git exited before reading its input; the exit code tells the story
            }
            byte[] out = process.getInputStream().readAllBytes();
            int code = process.waitFor();
            errReader.join();
            String errText;
            synchronized (err) {
                errText = err.toString();
            }
            CommandLog.record(dir, args, code, (System.nanoTime() - started) / 1_000_000, stdin, errText, out.length);
            return new Result(code, new String(out, StandardCharsets.UTF_8), errText);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new GitException("git I/O error: " + e.getMessage(), cmd);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new GitException("git was interrupted", cmd);
        }
    }

    /** Collects stderr; segments terminated by '\r' are progress updates and are only forwarded. */
    private static void readStderr(InputStream in, StringBuilder err, Consumer<String> progress) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                if (c == '\r' || c == '\n') {
                    String s = line.toString();
                    line.setLength(0);
                    if (progress != null && !s.isBlank()) {
                        progress.accept(s.trim());
                    }
                    if (c == '\n') {
                        synchronized (err) {
                            err.append(s).append('\n');
                        }
                    }
                } else {
                    line.append((char) c);
                }
            }
            if (!line.isEmpty()) {
                synchronized (err) {
                    err.append(line);
                }
            }
        } catch (IOException ignored) {
            // process went away
        }
    }

    /** Runs git with standard output written to {@code out} byte for byte (binary safe). */
    public void execToFile(java.nio.file.Path out, List<String> args) throws GitException {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (dir != null) {
            pb.directory(dir);
        }
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0");
        pb.redirectOutput(out.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(System.getProperty("os.name", "")
                .toLowerCase().contains("win") ? "NUL" : "/dev/null")));
        long started = System.nanoTime();
        try {
            Process p = pb.start();
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            List<String> logged = new ArrayList<>(args);
            logged.add(">");
            logged.add(out.toString());
            CommandLog.record(dir, logged, code, (System.nanoTime() - started) / 1_000_000, null, err,
                    out.toFile().length());
            if (code != 0) {
                throw new GitException(err.isBlank() ? "git " + String.join(" ", args) + " failed" : err.strip(), cmd);
            }
        } catch (IOException e) {
            throw new GitException("git I/O error: " + e.getMessage(), cmd);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("git was interrupted", cmd);
        }
    }

    public String exec(String... args) throws GitException {
        return check(run(null, null, List.of(args)), args).out();
    }

    public String exec(List<String> args) throws GitException {
        return check(run(null, null, args), args.toArray(String[]::new)).out();
    }

    public String execInput(String stdin, String... args) throws GitException {
        return check(run(stdin, null, List.of(args)), args).out();
    }

    public String execProgress(Consumer<String> progress, List<String> args) throws GitException {
        Result r = check(run(null, progress, args), args.toArray(String[]::new));
        return r.out() + r.err();
    }

    private static Result check(Result r, String[] args) throws GitException {
        if (!r.ok()) {
            String msg = r.err().strip();
            if (msg.isEmpty()) {
                msg = r.out().strip();
            }
            if (msg.isEmpty()) {
                msg = "git " + String.join(" ", args) + " exited with code " + r.exitCode();
            }
            throw new GitException(msg, List.of(args));
        }
        return r;
    }
}
