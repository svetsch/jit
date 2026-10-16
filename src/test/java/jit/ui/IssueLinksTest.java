package jit.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import jit.hosting.HostedRepo;

class IssueLinksTest {

    @Test
    void linksJiraKeysIssueNumbersAndUrls() {
        List<IssueLinks.Rule> rules = List.of(
                new IssueLinks.Rule("\\b[A-Z][A-Z0-9]+-\\d+\\b", "https://jira.example.com/browse/$0"));
        HostedRepo gh = HostedRepo.parse("git@github.com:o/r.git");
        List<IssueLinks.Link> links = IssueLinks.find(
                "PROJ-12: fix #7, see https://example.com/x.", gh, rules);
        assertEquals(3, links.size());
        assertEquals("https://jira.example.com/browse/PROJ-12", links.get(0).url());
        assertEquals("https://github.com/o/r/issues/7", links.get(1).url());
        assertEquals("https://example.com/x", links.get(2).url());
    }

    @Test
    void groupsAreSubstitutedAndOverlapsSkipped() {
        List<IssueLinks.Rule> rules = List.of(new IssueLinks.Rule("bug (\\d+)", "https://bugs/?id=$1"));
        List<IssueLinks.Link> links = IssueLinks.find("bug 42 and bug 43", null, rules);
        assertEquals(List.of("https://bugs/?id=42", "https://bugs/?id=43"),
                links.stream().map(IssueLinks.Link::url).toList());
        assertEquals(0, IssueLinks.find("color #fff and a&#12;", null, List.of()).size());
    }

    @Test
    void tokenizeHonoursQuotes() {
        assertEquals(List.of("/e", "/dl", "a b", "C:\\x y\\f.txt"),
                ExternalDiffTool.tokenize("/e /dl \"a b\" \"C:\\x y\\f.txt\""));
        assertEquals(List.of("/lefttitle=a b"), ExternalDiffTool.tokenize("/lefttitle=\"a b\""));
    }
}
