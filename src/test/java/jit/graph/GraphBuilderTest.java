package jit.graph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import jit.git.Commit;

class GraphBuilderTest {

    private static Commit c(String hash, String... parents) {
        return new Commit(hash, parents, "a", "a@x", 0, "a", "a@x", 0, hash);
    }

    @Test
    void linearHistoryStaysInOneLane() {
        GraphRow[] rows = GraphBuilder.build(List.of(c("c", "b"), c("b", "a"), c("a")));
        for (GraphRow r : rows) {
            assertEquals(0, r.lane);
            assertEquals(1, r.width);
        }
        assertArrayEquals(new int[0], rows[0].incoming);
        assertArrayEquals(new int[]{0}, rows[1].incoming);
        assertArrayEquals(new int[0], rows[2].outgoing);
    }

    @Test
    void mergeOpensAndClosesASecondLane() {
        // m merges b2 (side branch) into a2; both fork from a1
        GraphRow[] rows = GraphBuilder.build(List.of(
                c("m", "a2", "b2"), c("b2", "a1"), c("a2", "a1"), c("a1")));
        assertEquals(0, rows[0].lane);
        assertArrayEquals(new int[]{0, 1}, rows[0].outgoing);
        assertEquals(1, rows[1].lane);
        assertArrayEquals(new int[]{0}, rows[1].passFrom);
        assertEquals(0, rows[2].lane);
        // the side lane curves back into the main lane below a2; a1 stays in lane 0
        assertArrayEquals(new int[]{1}, rows[2].passFrom);
        assertArrayEquals(new int[]{0}, rows[2].passTo);
        assertArrayEquals(new int[]{0}, rows[3].incoming);
        assertEquals(0, rows[3].lane);
    }

    @Test
    void branchTipStartsInFreeLane() {
        GraphRow[] rows = GraphBuilder.build(List.of(c("x", "a"), c("y", "a"), c("a")));
        assertEquals(0, rows[0].lane);
        assertEquals(1, rows[1].lane);
        assertArrayEquals(new int[]{0}, rows[1].outgoing);
    }
}
