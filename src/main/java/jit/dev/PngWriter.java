package jit.dev;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

/** Minimal PNG encoder so snapshots can be saved without the javafx-swing module. */
public final class PngWriter {
    private PngWriter() {
    }

    public static void write(Image image, Path file) throws IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(raw)) {
            byte[] row = new byte[w * 4 + 1];
            for (int y = 0; y < h; y++) {
                row[0] = 0;
                for (int x = 0; x < w; x++) {
                    int argb = reader.getArgb(x, y);
                    int i = 1 + x * 4;
                    row[i] = (byte) (argb >> 16);
                    row[i + 1] = (byte) (argb >> 8);
                    row[i + 2] = (byte) argb;
                    row[i + 3] = (byte) (argb >>> 24);
                }
                z.write(row);
            }
        }
        try (OutputStream os = Files.newOutputStream(file); DataOutputStream out = new DataOutputStream(os)) {
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(ihdr);
            d.writeInt(w);
            d.writeInt(h);
            d.writeByte(8);
            d.writeByte(6);
            d.writeByte(0);
            d.writeByte(0);
            d.writeByte(0);
            chunk(out, "IHDR", ihdr.toByteArray());
            chunk(out, "IDAT", raw.toByteArray());
            chunk(out, "IEND", new byte[0]);
        }
    }

    private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
        out.writeInt(data.length);
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }
}
