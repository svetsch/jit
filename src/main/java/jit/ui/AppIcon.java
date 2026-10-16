package jit.ui;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;

/**
 * The application icon: a blue rounded square with a white branch. The PNGs in {@code /jit/icons} and the
 * packaging icons are generated from {@link #render(int)} by {@code jit.dev.IconGenerator}.
 */
public final class AppIcon {
    /** Sizes shipped as resources and embedded in the Windows .ico. */
    public static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private AppIcon() {
    }

    /** Window icons in every shipped size (the platform picks the best one). */
    public static List<Image> images() {
        List<Image> images = new ArrayList<>();
        for (int size : SIZES) {
            images.add(image(size));
        }
        return images;
    }

    /** The icon at {@code size} pixels, from the shipped PNG when there is one. */
    public static Image image(int size) {
        try (InputStream in = AppIcon.class.getResourceAsStream("/jit/icons/jit-" + size + ".png")) {
            if (in != null) {
                return new Image(in);
            }
        } catch (Exception ignored) {
            // fall back to drawing it
        }
        return render(size);
    }

    /** Draws the icon; must run on the JavaFX application thread. */
    public static Image render(int size) {
        Canvas c = new Canvas(size, size);
        GraphicsContext g = c.getGraphicsContext2D();
        double s = size / 64.0;
        // background: rounded square with a diagonal gradient and a soft top highlight
        g.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#5b9bff")), new Stop(1, Color.web("#2152c4"))));
        g.fillRoundRect(2 * s, 2 * s, 60 * s, 60 * s, 18 * s, 18 * s);
        if (size >= 32) {
            g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.rgb(255, 255, 255, 0.22)), new Stop(1, Color.rgb(255, 255, 255, 0))));
            g.fillRoundRect(2 * s, 2 * s, 60 * s, 30 * s, 18 * s, 18 * s);
        }
        // branch: trunk, curved side branch, three commits; strokes thicker at tiny sizes to stay visible
        double line = Math.max(5 * s, 1.6);
        double dot = Math.max(13 * s, 4.2);
        g.setStroke(Color.WHITE);
        g.setLineWidth(line);
        g.setLineCap(StrokeLineCap.ROUND);
        g.strokeLine(22 * s, 16 * s, 22 * s, 48 * s);
        g.beginPath();
        g.moveTo(42 * s, 22 * s);
        g.bezierCurveTo(42 * s, 34 * s, 22 * s, 30 * s, 22 * s, 42 * s);
        g.stroke();
        g.setFill(Color.WHITE);
        g.fillOval(22 * s - dot / 2, 16 * s - dot / 2, dot, dot);
        g.fillOval(22 * s - dot / 2, 48 * s - dot / 2, dot, dot);
        g.fillOval(42 * s - dot / 2, 22 * s - dot / 2, dot, dot);
        // a hint of the lane colours inside the commit dots
        if (size >= 24) {
            double inner = dot * 0.42;
            g.setFill(Color.web("#2152c4"));
            g.fillOval(22 * s - inner / 2, 16 * s - inner / 2, inner, inner);
            g.setFill(Color.web("#f59e0b"));
            g.fillOval(42 * s - inner / 2, 22 * s - inner / 2, inner, inner);
            g.setFill(Color.web("#10b981"));
            g.fillOval(22 * s - inner / 2, 48 * s - inner / 2, inner, inner);
        }
        SnapshotParameters p = new SnapshotParameters();
        p.setFill(Color.TRANSPARENT);
        return c.snapshot(p, null);
    }
}
