package jit.graph;

import java.util.ArrayList;
import java.util.List;

import jit.git.Commit;

/** Computes the commit graph lanes for a list of commits in (topological) date order. */
public final class GraphBuilder {
    private GraphBuilder() {
    }

    public static GraphRow[] build(List<Commit> commits) {
        GraphRow[] rows = new GraphRow[commits.size()];
        List<String> lanes = new ArrayList<>();
        for (int r = 0; r < commits.size(); r++) {
            Commit c = commits.get(r);
            int lane = lanes.indexOf(c.hash);
            if (lane < 0) {
                lane = freeLane(lanes, 0);
            }
            int before = lanes.size();

            int[] passFrom = new int[lanes.size()];
            int[] incoming = new int[lanes.size()];
            int np = 0;
            int ni = 0;
            for (int j = 0; j < lanes.size(); j++) {
                String h = lanes.get(j);
                if (h == null) {
                    continue;
                }
                if (h.equals(c.hash)) {
                    incoming[ni++] = j;
                    lanes.set(j, null);
                } else {
                    passFrom[np++] = j;
                }
            }
            int[] passTo = new int[np];
            for (int i = 0; i < np; i++) {
                passTo[i] = passFrom[i];
            }

            int[] outgoing = new int[c.parents.length];
            for (int p = 0; p < c.parents.length; p++) {
                String parent = c.parents[p];
                int k = lanes.indexOf(parent);
                if (k > lane && p == 0 && lanes.get(lane) == null) {
                    // the first parent is already expected further right: pull it into our lane so
                    // the left-most line stays straight and the other lane curves in
                    lanes.set(k, null);
                    lanes.set(lane, parent);
                    for (int i = 0; i < np; i++) {
                        if (passFrom[i] == k) {
                            passTo[i] = lane;
                        }
                    }
                    k = lane;
                } else if (k < 0) {
                    k = p == 0 && lanes.get(lane) == null ? lane : freeLane(lanes, lane + 1);
                    lanes.set(k, parent);
                }
                outgoing[p] = k;
            }
            while (!lanes.isEmpty() && lanes.get(lanes.size() - 1) == null) {
                lanes.remove(lanes.size() - 1);
            }
            int width = Math.max(Math.max(before, lanes.size()), lane + 1);
            rows[r] = new GraphRow(lane, trim(passFrom, np), passTo, trim(incoming, ni), outgoing, width);
        }
        return rows;
    }

    /** Returns the first free lane at or after {@code from}, growing the lane list if necessary. */
    private static int freeLane(List<String> lanes, int from) {
        for (int i = from; i < lanes.size(); i++) {
            if (lanes.get(i) == null) {
                return i;
            }
        }
        while (lanes.size() < from) {
            lanes.add(null);
        }
        lanes.add(null);
        return lanes.size() - 1;
    }

    private static int[] trim(int[] a, int n) {
        int[] r = new int[n];
        System.arraycopy(a, 0, r, 0, n);
        return r;
    }
}
