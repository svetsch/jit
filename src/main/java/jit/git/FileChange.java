package jit.git;

/** A file touched by a commit. */
public record FileChange(char status, String path, String oldPath) {
}
