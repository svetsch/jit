package jit.hosting;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A repository on a hosting service, derived from a remote URL, that knows the web URLs of
 * commits, branches, files and pull requests.
 */
public final class HostedRepo {

    public enum Provider {
        GITHUB("GitHub", "Pull Requests", "Pull Request", "Actions"),
        GITLAB("GitLab", "Merge Requests", "Merge Request", "Pipelines"),
        BITBUCKET("Bitbucket", "Pull Requests", "Pull Request", "Pipelines"),
        AZURE("Azure DevOps", "Pull Requests", "Pull Request", "Pipelines");

        public final String title;
        public final String reviews;
        public final String review;
        public final String ci;

        Provider(String title, String reviews, String review, String ci) {
            this.title = title;
            this.reviews = reviews;
            this.review = review;
            this.ci = ci;
        }
    }

    private static final Pattern SCP = Pattern.compile("^(?:[^@/]+@)?([^:/]+):(?!//)(.+)$");
    private static final Pattern URL = Pattern.compile("^[a-z+]+://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/(.+)$",
            Pattern.CASE_INSENSITIVE);

    public final Provider provider;
    public final String host;
    /** Path of the repository on the host, e.g. {@code owner/repo} or {@code group/sub/repo}. */
    public final String path;
    private final String base;

    private HostedRepo(Provider provider, String host, String path, String base) {
        this.provider = provider;
        this.host = host;
        this.path = path;
        this.base = base;
    }

    /** Parses https, ssh and scp-style remote URLs; returns {@code null} for unknown hosts. */
    public static HostedRepo parse(String remoteUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return null;
        }
        String url = remoteUrl.strip();
        String host;
        String path;
        Matcher m = URL.matcher(url);
        if (m.matches()) {
            host = m.group(1).toLowerCase();
            path = m.group(2);
        } else {
            Matcher s = SCP.matcher(url);
            if (!s.matches() || url.contains("\\") || url.matches("^[A-Za-z]:.*")) {
                return null;
            }
            host = s.group(1).toLowerCase();
            path = s.group(2);
        }
        path = path.replaceAll("/+$", "");
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        if (host.contains("dev.azure.com") || host.endsWith("visualstudio.com")) {
            return azure(host, path);
        }
        Provider provider;
        if (host.contains("github")) {
            provider = Provider.GITHUB;
        } else if (host.contains("gitlab")) {
            provider = Provider.GITLAB;
        } else if (host.contains("bitbucket")) {
            provider = Provider.BITBUCKET;
        } else {
            return null;
        }
        if (host.startsWith("ssh.")) {
            host = host.substring(4);
        }
        return new HostedRepo(provider, host, path, "https://" + host + "/" + path);
    }

    private static HostedRepo azure(String host, String path) {
        // https: org/project/_git/repo   ssh: v3/org/project/repo
        String[] p = path.split("/");
        String org;
        String project;
        String repo;
        if (p.length >= 4 && p[0].equals("v3")) {
            org = p[1];
            project = p[2];
            repo = p[3];
        } else if (p.length >= 4 && p[2].equals("_git")) {
            org = p[0];
            project = p[1];
            repo = p[3];
        } else if (p.length >= 3 && p[1].equals("_git") && host.endsWith("visualstudio.com")) {
            org = host.substring(0, host.indexOf('.'));
            project = p[0];
            repo = p[2];
        } else {
            return null;
        }
        String base = "https://dev.azure.com/" + org + "/" + project + "/_git/" + repo;
        return new HostedRepo(Provider.AZURE, "dev.azure.com", org + "/" + project + "/" + repo, base);
    }

    public String repositoryUrl() {
        return base;
    }

    public String commitUrl(String sha) {
        return switch (provider) {
            case GITHUB, AZURE -> base + "/commit/" + sha;
            case GITLAB -> base + "/-/commit/" + sha;
            case BITBUCKET -> base + "/commits/" + sha;
        };
    }

    public String branchUrl(String branch) {
        return switch (provider) {
            case GITHUB -> base + "/tree/" + segments(branch);
            case GITLAB -> base + "/-/tree/" + segments(branch);
            case BITBUCKET -> base + "/branch/" + segments(branch);
            case AZURE -> base + "?version=GB" + query(branch);
        };
    }

    public String fileUrl(String rev, String file) {
        return switch (provider) {
            case GITHUB -> base + "/blob/" + segments(rev) + "/" + segments(file);
            case GITLAB -> base + "/-/blob/" + segments(rev) + "/" + segments(file);
            case BITBUCKET -> base + "/src/" + segments(rev) + "/" + segments(file);
            case AZURE -> base + "?path=/" + query(file) + "&version=GC" + query(rev);
        };
    }

    public String pullRequestsUrl() {
        return switch (provider) {
            case GITHUB -> base + "/pulls";
            case GITLAB -> base + "/-/merge_requests";
            case BITBUCKET -> base + "/pull-requests";
            case AZURE -> base + "/pullrequests";
        };
    }

    /** Page that starts a pull / merge request from {@code source} into {@code target} (may be null). */
    public String newPullRequestUrl(String source, String target) {
        return switch (provider) {
            case GITHUB -> base + "/compare/" + (target == null ? "" : segments(target) + "...")
                    + segments(source) + "?expand=1";
            case GITLAB -> base + "/-/merge_requests/new?merge_request%5Bsource_branch%5D=" + query(source)
                    + (target == null ? "" : "&merge_request%5Btarget_branch%5D=" + query(target));
            case BITBUCKET -> base + "/pull-requests/new?source=" + query(source)
                    + (target == null ? "" : "&dest=" + query(target));
            case AZURE -> base + "/pullrequestcreate?sourceRef=" + query(source)
                    + (target == null ? "" : "&targetRef=" + query(target));
        };
    }

    public String issuesUrl() {
        return switch (provider) {
            case GITHUB -> base + "/issues";
            case GITLAB -> base + "/-/issues";
            case BITBUCKET -> base + "/issues";
            case AZURE -> base.substring(0, base.indexOf("/_git/")) + "/_workitems";
        };
    }

    public String ciUrl() {
        return switch (provider) {
            case GITHUB -> base + "/actions";
            case GITLAB -> base + "/-/pipelines";
            case BITBUCKET -> base + "/pipelines";
            case AZURE -> base.substring(0, base.indexOf("/_git/")) + "/_build";
        };
    }

    private static String query(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Encodes each path segment but keeps the slashes. */
    private static String segments(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("/", -1)) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(query(part));
        }
        return sb.toString();
    }
}
