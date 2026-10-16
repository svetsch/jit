package jit.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProgressTest {

    @Test
    void parsesGitProgressLines() {
        Progress p = Progress.parse("Receiving objects:  45% (450/1000), 1.20 MiB | 2.00 MiB/s");
        assertEquals("Receiving objects", p.phase());
        assertEquals(45, p.percent());
        assertEquals("Receiving objects 45%", p.label());

        Progress remote = Progress.parse("remote: Counting objects: 100% (12/12), done.");
        assertEquals("Counting objects", remote.phase());
        assertEquals(100, remote.percent());

        Progress plain = Progress.parse("From github.com:owner/repo");
        assertEquals(-1, plain.percent());
        assertEquals("From github.com:owner/repo", plain.label());
    }
}
