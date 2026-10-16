package jit.git;

import java.util.List;

import jit.graph.GraphRow;

/** One commit of the history, as listed by {@code git log}. */
public final class Commit {
    /** Hash of the pseudo commit that represents uncommitted changes in the history graph. */
    public static final String WORKING_TREE = "*working-tree*";

    public final String hash;
    public final String[] parents;
    public final String author;
    public final String authorEmail;
    public final long authorTime;
    public final String committer;
    public final String committerEmail;
    public final long commitTime;
    public final String subject;

    /** Refs pointing at this commit; assigned on the UI thread. */
    public List<Ref> refs = List.of();
    /** Graph lanes for this row; assigned on the UI thread. */
    public GraphRow graph;

    public Commit(String hash, String[] parents, String author, String authorEmail, long authorTime,
                  String committer, String committerEmail, long commitTime, String subject) {
        this.hash = hash;
        this.parents = parents;
        this.author = author;
        this.authorEmail = authorEmail;
        this.authorTime = authorTime;
        this.committer = committer;
        this.committerEmail = committerEmail;
        this.commitTime = commitTime;
        this.subject = subject;
    }

    public static Commit workingTree(String headHash, int changes) {
        String[] parents = headHash == null ? new String[0] : new String[]{headHash};
        long now = System.currentTimeMillis() / 1000;
        return new Commit(WORKING_TREE, parents, "", "", now, "", "", now,
                "Uncommitted changes (" + changes + ")");
    }

    public boolean isWorkingTree() {
        return WORKING_TREE.equals(hash);
    }

    public boolean isMerge() {
        return parents.length > 1;
    }

    public String shortHash() {
        return isWorkingTree() ? "" : hash.substring(0, Math.min(7, hash.length()));
    }

    @Override
    public String toString() {
        return shortHash() + " " + subject;
    }
}
