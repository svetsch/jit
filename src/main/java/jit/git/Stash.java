package jit.git;

/** An entry of {@code git stash list}. */
public record Stash(int index, String ref, String hash, String message, long time) {
}
