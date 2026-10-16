package jit.git;

import java.util.List;

/** Raised when a git invocation fails; the message is git's own error output. */
public class GitException extends Exception {
    private final List<String> command;

    public GitException(String message, List<String> command) {
        super(message);
        this.command = List.copyOf(command);
    }

    public List<String> command() {
        return command;
    }
}
