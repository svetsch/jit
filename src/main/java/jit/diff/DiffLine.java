package jit.diff;

/** One displayed row of a diff. */
public final class DiffLine {
    public enum Type { META, HUNK, CONTEXT, ADD, DELETE, NO_NEWLINE }

    public final Type type;
    /** The line exactly as it appears in the patch (including the +/-/space prefix). */
    public final String raw;
    /** Text for display: prefix removed, tabs expanded. */
    public final String text;
    public final int oldNo;
    public final int newNo;
    /** Hunk this line belongs to; for {@link Type#HUNK} rows the hunk it introduces. */
    public final Hunk hunk;

    DiffLine(Type type, String raw, String text, int oldNo, int newNo, Hunk hunk) {
        this.type = type;
        this.raw = raw;
        this.text = text;
        this.oldNo = oldNo;
        this.newNo = newNo;
        this.hunk = hunk;
    }

    public boolean isChange() {
        return type == Type.ADD || type == Type.DELETE;
    }
}
