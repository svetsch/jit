package jit.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Settings kept in a UTF-8 properties file, written on every change. */
final class FileStore implements SettingsStore {

    private final SettingsLocation location;
    private final Path file;
    private final Map<String, String> values = new TreeMap<>();

    FileStore(SettingsLocation location, Path file) {
        this.location = location;
        this.file = file.toAbsolutePath();
        load();
    }

    Path file() {
        return file;
    }

    @Override
    public SettingsLocation location() {
        return location;
    }

    @Override
    public String description() {
        return file.toString();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read settings file " + file, e);
        }
        props.forEach((k, v) -> values.put(k.toString(), v.toString()));
    }

    @Override
    public synchronized String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    @Override
    public synchronized void put(String key, String value) {
        String old = value == null ? values.remove(key) : values.put(key, value);
        if (!Objects.equals(old, value)) {
            save();
        }
    }

    @Override
    public synchronized void remove(String key) {
        if (values.remove(key) != null) {
            save();
        }
    }

    @Override
    public synchronized Set<String> keys() {
        return Set.copyOf(values.keySet());
    }

    @Override
    public synchronized void clear() {
        values.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete settings file " + file, e);
        }
    }

    @Override
    public void flush() {
        // written through on every change
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                out.write("# Jit settings\n");
                for (Map.Entry<String, String> e : values.entrySet()) {
                    out.write(escape(e.getKey(), true) + "=" + escape(e.getValue(), false) + "\n");
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write settings file " + file, e);
        }
    }

    /** Properties escaping that keeps non-ASCII characters readable (the file is read back as UTF-8). */
    static String escape(String s, boolean key) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\f' -> b.append("\\f");
                case '=', ':', '#', '!' -> b.append('\\').append(c);
                case ' ' -> b.append(key || i == 0 ? "\\ " : " ");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
