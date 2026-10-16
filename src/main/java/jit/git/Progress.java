package jit.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One line of git's progress output, e.g. {@code Receiving objects:  45% (450/1000), 1.20 MiB | 2.00 MiB/s}.
 *
 * @param phase   what git is doing ("Receiving objects"), or the whole line when it has no phase
 * @param percent 0–100, or -1 when the line carries no percentage
 */
public record Progress(String phase, int percent) {
    private static final Pattern LINE = Pattern.compile("^(?:remote:\\s*)?([^:]+):\\s+(\\d{1,3})%.*");

    public static Progress parse(String line) {
        String s = line == null ? "" : line.strip();
        Matcher m = LINE.matcher(s);
        if (m.matches()) {
            return new Progress(m.group(1).strip(), Math.min(100, Integer.parseInt(m.group(2))));
        }
        return new Progress(s, -1);
    }

    /** Text for a status bar: "Receiving objects 45%". */
    public String label() {
        return percent < 0 ? phase : phase + " " + percent + "%";
    }
}
