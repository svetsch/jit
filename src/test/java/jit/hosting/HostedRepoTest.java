package jit.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class HostedRepoTest {

    @Test
    void parsesGithubUrls() {
        for (String url : new String[]{"https://github.com/owner/repo.git", "git@github.com:owner/repo.git",
                "ssh://git@github.com/owner/repo", "https://user@github.com/owner/repo/"}) {
            HostedRepo h = HostedRepo.parse(url);
            assertEquals(HostedRepo.Provider.GITHUB, h.provider, url);
            assertEquals("https://github.com/owner/repo", h.repositoryUrl(), url);
        }
        HostedRepo h = HostedRepo.parse("git@github.com:owner/repo.git");
        assertEquals("https://github.com/owner/repo/commit/abc", h.commitUrl("abc"));
        assertEquals("https://github.com/owner/repo/tree/feature/x", h.branchUrl("feature/x"));
        assertEquals("https://github.com/owner/repo/compare/main...feature/x?expand=1",
                h.newPullRequestUrl("feature/x", "main"));
        assertEquals("https://github.com/owner/repo/blob/main/src/a%20b.txt", h.fileUrl("main", "src/a b.txt"));
    }

    @Test
    void parsesGitlabBitbucketAndAzure() {
        HostedRepo gl = HostedRepo.parse("git@gitlab.com:group/sub/repo.git");
        assertEquals(HostedRepo.Provider.GITLAB, gl.provider);
        assertEquals("https://gitlab.com/group/sub/repo/-/merge_requests/new?merge_request%5Bsource_branch%5D=f"
                + "&merge_request%5Btarget_branch%5D=main", gl.newPullRequestUrl("f", "main"));

        HostedRepo bb = HostedRepo.parse("https://jane@bitbucket.org/team/repo.git");
        assertEquals(HostedRepo.Provider.BITBUCKET, bb.provider);
        assertEquals("https://bitbucket.org/team/repo/commits/abc", bb.commitUrl("abc"));
        assertEquals("https://bitbucket.org/team/repo/pull-requests/new?source=f&dest=main",
                bb.newPullRequestUrl("f", "main"));

        HostedRepo az = HostedRepo.parse("https://org@dev.azure.com/org/Project/_git/Repo");
        assertEquals(HostedRepo.Provider.AZURE, az.provider);
        assertEquals("https://dev.azure.com/org/Project/_git/Repo/commit/abc", az.commitUrl("abc"));
        HostedRepo azSsh = HostedRepo.parse("git@ssh.dev.azure.com:v3/org/Project/Repo");
        assertEquals("https://dev.azure.com/org/Project/_git/Repo", azSsh.repositoryUrl());
    }

    @Test
    void ignoresLocalAndUnknownRemotes() {
        assertNull(HostedRepo.parse("C:\\repos\\demo.git"));
        assertNull(HostedRepo.parse("../demo-remote.git"));
        assertNull(HostedRepo.parse("https://git.example.com/a/b.git"));
        assertNull(HostedRepo.parse(""));
    }
}
