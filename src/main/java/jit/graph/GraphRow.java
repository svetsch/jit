package jit.graph;

/**
 * Lane layout of one history row. Lanes are columns; edges run from the top border of the row to the
 * commit dot ({@link #incoming}), from the dot to the bottom border ({@link #outgoing}), or through the row
 * from lane {@code passFrom[i]} at the top to lane {@code passTo[i]} at the bottom.
 */
public final class GraphRow {
    public final int lane;
    public final int[] passFrom;
    public final int[] passTo;
    public final int[] incoming;
    public final int[] outgoing;
    /** Number of lanes this row needs to draw. */
    public final int width;

    public GraphRow(int lane, int[] passFrom, int[] passTo, int[] incoming, int[] outgoing, int width) {
        this.lane = lane;
        this.passFrom = passFrom;
        this.passTo = passTo;
        this.incoming = incoming;
        this.outgoing = outgoing;
        this.width = width;
    }
}
