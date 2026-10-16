package jit;

/** Plain main class so the shaded jar can start JavaFX from the class path. */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        JitApp.main(args);
    }
}
