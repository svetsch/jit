package jit.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import jit.hosting.HostedRepo;

/** Turns issue keys (JIRA, #123 …) and URLs in commit messages into clickable links. */
public final class IssueLinks {

    /** A user rule: every match of {@code pattern} links to {@code url} ($0 = match, $1… = groups). */
    public record Rule(String pattern, String url) {
    }

    /** A link found in a text. */
    public record Link(int start, int end, String text, String url) {
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"')\\]]+");
    private static final Pattern HASH_ISSUE = Pattern.compile("(?<![\\w&/])#(\\d+)\\b");

    private IssueLinks() {
    }

    public static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        for (String line : Settings.get("issueLinks", "").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                rules.add(new Rule(line.substring(0, tab), line.substring(tab + 1)));
            }
        }
        return rules;
    }

    public static void saveRules(List<Rule> rules) {
        StringBuilder sb = new StringBuilder();
        for (Rule r : rules) {
            if (!r.pattern().isBlank() && !r.url().isBlank()) {
                sb.append(r.pattern().replace("\n", "").replace("\t", " ")).append('\t')
                        .append(r.url().strip()).append('\n');
            }
        }
        Settings.put("issueLinks", sb.toString());
    }

    /** Validates a rule pattern; returns an error message or {@code null}. */
    public static String validate(String pattern) {
        try {
            Pattern.compile(pattern);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    /** Finds links in {@code text}; user rules win over #123 and plain URLs when they overlap. */
    public static List<Link> find(String text, HostedRepo hosted, List<Rule> rules) {
        List<Link> found = new ArrayList<>();
        for (Rule rule : rules) {
            Pattern p;
            try {
                p = Pattern.compile(rule.pattern());
            } catch (PatternSyntaxException e) {
                continue;
            }
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (m.end() > m.start()) {
                    add(found, new Link(m.start(), m.end(), m.group(), expand(rule.url(), m)));
                }
            }
        }
        if (hosted != null && hosted.provider != HostedRepo.Provider.AZURE) {
            Matcher m = HASH_ISSUE.matcher(text);
            while (m.find()) {
                add(found, new Link(m.start(), m.end(), m.group(), hosted.issuesUrl() + "/" + m.group(1)));
            }
        }
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String url = m.group().replaceAll("[.,;:!?]+$", "");
            add(found, new Link(m.start(), m.start() + url.length(), url, url));
        }
        found.sort(Comparator.comparingInt(Link::start));
        return found;
    }

    private static void add(List<Link> links, Link link) {
        for (Link l : links) {
            if (link.start() < l.end() && l.start() < link.end()) {
                return;
            }
        }
        links.add(link);
    }

    private static String expand(String template, Matcher m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            if (c == '$' && i + 1 < template.length() && Character.isDigit(template.charAt(i + 1))) {
                int g = template.charAt(i + 1) - '0';
                if (g <= m.groupCount()) {
                    String v = m.group(g);
                    sb.append(v == null ? "" : java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Builds a wrapping text node where every link is a clickable hyperlink. */
    public static TextFlow flow(String text, HostedRepo hosted, String styleClass, Consumer<String> open) {
        return flow(text, hosted, rules(), styleClass, open);
    }

    public static TextFlow flow(String text, HostedRepo hosted, List<Rule> rules, String styleClass,
                                Consumer<String> open) {
        TextFlow flow = new TextFlow();
        int pos = 0;
        for (Link l : find(text, hosted, rules)) {
            if (l.start() > pos) {
                flow.getChildren().add(text(text.substring(pos, l.start()), styleClass));
            }
            Hyperlink h = new Hyperlink(l.text());
            h.getStyleClass().addAll("issue-link", styleClass);
            h.setTooltip(new Tooltip(l.url()));
            h.setOnAction(e -> open.accept(l.url()));
            flow.getChildren().add(h);
            pos = l.end();
        }
        if (pos < text.length()) {
            flow.getChildren().add(text(text.substring(pos), styleClass));
        }
        return flow;
    }

    private static Node text(String s, String styleClass) {
        Text t = new Text(s);
        t.getStyleClass().addAll("flow-text", styleClass);
        return t;
    }
}
