package jit.diff;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the output of {@code git diff} for a single file. */
public final class DiffParser {
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private static final int MAX_ROWS = 100_000;

    private DiffParser() {
    }

    public static FileDiff parse(String text) {
        FileDiff diff = new FileDiff();
        if (text == null || text.isEmpty()) {
            return diff;
        }
        String[] lines = text.split("\n", -1);
        int count = lines.length;
        if (count > 0 && lines[count - 1].isEmpty()) {
            count--;
        }
        Hunk hunk = null;
        boolean combined = false;
        int oldNo = 0;
        int newNo = 0;
        for (int i = 0; i < count; i++) {
            String raw = lines[i];
            if (diff.rows.size() >= MAX_ROWS) {
                diff.rows.add(new DiffLine(DiffLine.Type.META, "", "… diff truncated (too large to display)", -1, -1, null));
                break;
            }
            if (raw.startsWith("@@")) {
                Matcher m = HUNK.matcher(stripCr(raw));
                if (m.matches()) {
                    oldNo = Integer.parseInt(m.group(1));
                    newNo = Integer.parseInt(m.group(3));
                    hunk = new Hunk(raw, oldNo, newNo, true);
                    combined = false;
                } else {
                    hunk = new Hunk(raw, 0, 0, false);
                    combined = raw.startsWith("@@@");
                }
                diff.hunks.add(hunk);
                diff.rows.add(new DiffLine(DiffLine.Type.HUNK, raw, display(raw), -1, -1, hunk));
                continue;
            }
            if (hunk == null) {
                diff.header.add(raw);
                String meta = metaText(raw);
                if (raw.startsWith("Binary files") || raw.startsWith("GIT binary patch")) {
                    diff.binary = true;
                }
                if (meta != null) {
                    diff.rows.add(new DiffLine(DiffLine.Type.META, raw, meta, -1, -1, null));
                }
                continue;
            }
            if (raw.startsWith("diff ")) {
                // a second file in the same output; treat the rest as header text
                hunk = null;
                diff.header.add(raw);
                continue;
            }
            DiffLine line;
            if (combined) {
                String body = raw.length() > 2 ? raw.substring(2) : "";
                DiffLine.Type t = raw.contains("+") && raw.indexOf('+') < 2 ? DiffLine.Type.ADD
                        : raw.contains("-") && raw.indexOf('-') < 2 ? DiffLine.Type.DELETE : DiffLine.Type.CONTEXT;
                line = new DiffLine(t, raw, display(body), -1, -1, hunk);
            } else if (raw.startsWith("+")) {
                line = new DiffLine(DiffLine.Type.ADD, raw, display(raw.substring(1)), -1, newNo++, hunk);
                diff.additions++;
            } else if (raw.startsWith("-")) {
                line = new DiffLine(DiffLine.Type.DELETE, raw, display(raw.substring(1)), oldNo++, -1, hunk);
                diff.deletions++;
            } else if (raw.startsWith("\\")) {
                line = new DiffLine(DiffLine.Type.NO_NEWLINE, raw, stripCr(raw.substring(1)).strip(), -1, -1, hunk);
            } else {
                String body = raw.isEmpty() ? "" : raw.substring(1);
                line = new DiffLine(DiffLine.Type.CONTEXT, raw, display(body), oldNo++, newNo++, hunk);
            }
            hunk.lines.add(line);
            diff.rows.add(line);
        }
        return diff;
    }

    private static String metaText(String raw) {
        String s = stripCr(raw);
        if (s.startsWith("new file mode")) {
            return "New file (mode " + s.substring("new file mode ".length()) + ")";
        }
        if (s.startsWith("deleted file mode")) {
            return "Deleted file";
        }
        if (s.startsWith("old mode") || s.startsWith("new mode")) {
            return s.substring(0, 1).toUpperCase() + s.substring(1);
        }
        if (s.startsWith("rename from") || s.startsWith("rename to") || s.startsWith("copy from")
                || s.startsWith("copy to")) {
            return s.substring(0, 1).toUpperCase() + s.substring(1);
        }
        if (s.startsWith("similarity index")) {
            return "Similarity " + s.substring("similarity index ".length());
        }
        if (s.startsWith("Binary files")) {
            return "Binary file — no textual diff available";
        }
        return null;
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    static String display(String s) {
        s = stripCr(s);
        if (s.indexOf('\t') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t') {
                int spaces = 4 - (sb.length() % 4);
                sb.append(" ".repeat(spaces));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
