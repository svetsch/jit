package jit.diff;

import java.util.ArrayList;
import java.util.List;

/** A parsed single-file unified diff. */
public final class FileDiff {
    /** Raw header lines ({@code diff --git}, {@code index}, {@code ---}, {@code +++} ...). */
    public final List<String> header = new ArrayList<>();
    public final List<Hunk> hunks = new ArrayList<>();
    /** Every row to display, hunk headers included. */
    public final List<DiffLine> rows = new ArrayList<>();
    public boolean binary;
    public int additions;
    public int deletions;

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public boolean isPatchable() {
        return !binary && !hunks.isEmpty() && hunks.stream().allMatch(h -> h.patchable);
    }
}
