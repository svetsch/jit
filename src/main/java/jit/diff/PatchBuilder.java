package jit.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Builds partial patches used to stage, unstage or discard selected hunks and lines. */
public final class PatchBuilder {
    private PatchBuilder() {
    }

    /**
     * @param selected chooses the +/- lines that should be part of the patch
     * @param reverse  true when the patch will be applied with {@code -R} (unstage, discard);
     *                 unselected lines must then keep the "new" side intact instead of the "old" one
     * @return the patch text, or {@code null} when no change line was selected
     */
    public static String build(FileDiff diff, Predicate<DiffLine> selected, boolean reverse) {
        if (!diff.isPatchable()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String h : diff.header) {
            sb.append(h).append('\n');
        }
        boolean any = false;
        for (Hunk hunk : diff.hunks) {
            List<String> out = new ArrayList<>();
            int oldCount = 0;
            int newCount = 0;
            boolean hasChange = false;
            boolean lastKept = true;
            for (DiffLine l : hunk.lines) {
                switch (l.type) {
                    case CONTEXT -> {
                        out.add(l.raw.isEmpty() ? " " : l.raw);
                        oldCount++;
                        newCount++;
                        lastKept = true;
                    }
                    case ADD -> {
                        if (selected.test(l)) {
                            out.add(l.raw);
                            newCount++;
                            hasChange = true;
                            lastKept = true;
                        } else if (reverse) {
                            out.add(" " + l.raw.substring(1));
                            oldCount++;
                            newCount++;
                            lastKept = true;
                        } else {
                            lastKept = false;
                        }
                    }
                    case DELETE -> {
                        if (selected.test(l)) {
                            out.add(l.raw);
                            oldCount++;
                            hasChange = true;
                            lastKept = true;
                        } else if (!reverse) {
                            out.add(" " + l.raw.substring(1));
                            oldCount++;
                            newCount++;
                            lastKept = true;
                        } else {
                            lastKept = false;
                        }
                    }
                    case NO_NEWLINE -> {
                        if (lastKept) {
                            out.add(l.raw);
                        }
                    }
                    default -> {
                    }
                }
            }
            if (!hasChange) {
                continue;
            }
            any = true;
            sb.append("@@ -").append(hunk.oldStart).append(',').append(oldCount)
                    .append(" +").append(hunk.newStart).append(',').append(newCount).append(" @@\n");
            for (String s : out) {
                sb.append(s).append('\n');
            }
        }
        return any ? sb.toString() : null;
    }
}
