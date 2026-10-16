package jit.git;

/** A branch, remote-tracking branch or tag. */
public final class Ref {
    public enum Kind { LOCAL, REMOTE, TAG }

    public final Kind kind;
    public final String fullName;
    /** Short name, e.g. {@code main}, {@code origin/main} or {@code v1.0}. */
    public final String name;
    /** Commit the ref points at (tags are peeled). */
    public final String target;
    public final String upstream;
    public final int ahead;
    public final int behind;
    public final boolean upstreamGone;
    public final boolean head;

    public Ref(Kind kind, String fullName, String name, String target, String upstream,
               int ahead, int behind, boolean upstreamGone, boolean head) {
        this.kind = kind;
        this.fullName = fullName;
        this.name = name;
        this.target = target;
        this.upstream = upstream;
        this.ahead = ahead;
        this.behind = behind;
        this.upstreamGone = upstreamGone;
        this.head = head;
    }

    /** Remote name of a remote-tracking branch. */
    public String remote() {
        int i = name.indexOf('/');
        return i < 0 ? name : name.substring(0, i);
    }

    /** Branch name without the remote prefix. */
    public String remoteBranch() {
        int i = name.indexOf('/');
        return i < 0 ? name : name.substring(i + 1);
    }

    @Override
    public String toString() {
        return name;
    }
}
