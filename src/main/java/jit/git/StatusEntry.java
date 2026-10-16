package jit.git;

/**
 * One line of {@code git status --porcelain}.
 *
 * @param x index status
 * @param y work tree status
 */
public record StatusEntry(String path, String origPath, char x, char y) {

    public boolean isUntracked() {
        return x == '?';
    }

    public boolean isConflict() {
        return x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D');
    }

    public boolean hasStaged() {
        return !isConflict() && x != ' ' && x != '?' && x != '!';
    }

    public boolean hasUnstaged() {
        return isConflict() || isUntracked() || y != ' ';
    }

    /** Status letter for the unstaged list. */
    public char unstagedStatus() {
        if (isConflict()) {
            return 'U';
        }
        return isUntracked() ? 'A' : y;
    }

    public char stagedStatus() {
        return x;
    }
}
