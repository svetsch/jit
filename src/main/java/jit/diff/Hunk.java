package jit.diff;

import java.util.ArrayList;
import java.util.List;

/** A {@code @@ ... @@} section of a unified diff. */
public final class Hunk {
    public final String header;
    public final int oldStart;
    public final int newStart;
    /** False for combined (merge conflict) diffs, which cannot be staged line by line. */
    public final boolean patchable;
    public final List<DiffLine> lines = new ArrayList<>();

    Hunk(String header, int oldStart, int newStart, boolean patchable) {
        this.header = header;
        this.oldStart = oldStart;
        this.newStart = newStart;
        this.patchable = patchable;
    }
}
