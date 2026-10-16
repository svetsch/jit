package jit.dev;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javafx.application.Platform;

import jit.ui.AppIcon;

/**
 * Regenerates the icon files from {@link AppIcon#render(int)}:
 * {@code src/main/resources/jit/icons/jit-<size>.png} (window icons), {@code packaging/windows/jit.ico}
 * (executable icon) and {@code packaging/linux/jit.png}.
 *
 * <p>Run from the project folder: {@code java -cp target/classes;<javafx jars> jit.dev.IconGenerator}.
 */
public final class IconGenerator {
    private IconGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        CountDownLatch done = new CountDownLatch(1);
        Exception[] failure = new Exception[1];
        Platform.startup(() -> {
            try {
                generate(project);
            } catch (Exception e) {
                failure[0] = e;
            } finally {
                done.countDown();
            }
        });
        done.await();
        Platform.exit();
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private static void generate(Path project) throws IOException {
        Path icons = project.resolve("src/main/resources/jit/icons");
        Files.createDirectories(icons);
        List<byte[]> pngs = new ArrayList<>();
        for (int size : AppIcon.SIZES) {
            Path png = icons.resolve("jit-" + size + ".png");
            PngWriter.write(AppIcon.render(size), png);
            pngs.add(Files.readAllBytes(png));
            System.out.println("wrote " + project.relativize(png));
        }
        Path ico = project.resolve("packaging/windows/jit.ico");
        Files.createDirectories(ico.getParent());
        Files.write(ico, ico(AppIcon.SIZES, pngs));
        System.out.println("wrote " + project.relativize(ico));
        Path linux = project.resolve("packaging/linux/jit.png");
        Files.createDirectories(linux.getParent());
        Files.copy(icons.resolve("jit-256.png"), linux, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("wrote " + project.relativize(linux));
    }

    /** Windows .ico with PNG-compressed images (supported since Windows Vista). */
    static byte[] ico(int[] sizes, List<byte[]> pngs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        writeShort(out, 0);
        writeShort(out, 1);
        writeShort(out, sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            out.writeByte(s >= 256 ? 0 : s);
            out.writeByte(s >= 256 ? 0 : s);
            out.writeByte(0);
            out.writeByte(0);
            writeShort(out, 1);
            writeShort(out, 32);
            writeInt(out, pngs.get(i).length);
            writeInt(out, offset);
            offset += pngs.get(i).length;
        }
        for (byte[] png : pngs) {
            out.write(png);
        }
        return bytes.toByteArray();
    }

    private static void writeShort(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xff);
        out.writeByte((v >> 8) & 0xff);
    }

    private static void writeInt(DataOutputStream out, int v) throws IOException {
        writeShort(out, v & 0xffff);
        writeShort(out, (v >>> 16) & 0xffff);
    }
}
