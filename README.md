# Jit

A fast and friendly Git client for the desktop, written in Java 21 and OpenJFX 21.0.12, inspired by
[Fork](https://git-fork.com/). Jit drives your installed `git` executable, so it behaves exactly like git on the
command line — and every command it runs is visible in the **Git Command History**.

## Features

**Repositories**
- Several repositories open side by side as **tabs** (restored at start-up, reorderable, `Ctrl+T` / `+` for a new
  tab, `Ctrl+W` to close, `Ctrl+PgUp/PgDn` to switch, context menu: close others / to the right / all, copy path,
  explorer, terminal). Drop a folder on the window to open it.
- Repository manager: open, clone (with progress), init, recent repositories.

**History**
- Commit graph with coloured lanes, branch / remote / tag badges (current branch highlighted, local and remote
  branch merged into one badge when in sync), an *Uncommitted changes* row, search by message, author, SHA or ref.
- Commit details: author avatar, message with **clickable issue links**, SHA, parents, changed files and diff.
- File history (follows renames) and blame.

**Local changes**
- Unstaged / staged lists; stage, unstage and discard files, **hunks or individual lines**.
- Conflict resolution (mine / theirs / mark resolved) and a banner to **continue, skip or abort** a merge, rebase,
  cherry-pick or revert.
- Commit with subject + description, **recent messages** to reuse, and **options**: amend, amend keeping the
  message, reset author, sign-off, skip hooks, allow empty, push after commit. `Ctrl+Enter` commits.

**Diffs**
- **Unified or side-by-side** layout (toggle in the diff header, remembered).
- **External diff tool** (WinMerge, Beyond Compare, VS Code, Meld, KDiff3, P4Merge or any command) from the diff
  header, file context menus, or a double-click on a file of a commit. For local changes the right side is your
  real working file, so edits made in the tool are kept.

**Branches, tags, remotes, stashes**
- Sidebar with folders for `feature/…` branches, ahead/behind counts, filter.
- Checkout (local, remote → tracking branch, detached), create, rename, delete (optionally on the remote), merge
  (`--no-ff`, squash), rebase, cherry-pick, revert, reset (soft / mixed / hard), tags (annotated, push, delete).
- Fetch (all / one remote, prune, tags), pull (merge or rebase, autostash), push (choose branch and remote branch,
  set upstream, force with lease, tags).
- Stash (message, untracked, keep index), apply, pop, drop, browse stash contents.

**Interactive rebase**
- *Rewrite History* on any commit of the current branch: interactive rebase from here, or quick reword / edit /
  squash / fixup / drop of one commit. Reorder commits, edit messages, choose actions in a dialog.
- Stops (edit, conflicts) show a banner with *Continue*, *Skip* and **Abort** (restores the branch completely).
  After a completed rebase an **Undo** bar moves the branch back to where it was.

**Hosting services**
- GitHub, GitLab, Bitbucket and Azure DevOps are detected from the remote URL: open the repository, commit,
  branch, tag or file in the browser, list pull / merge requests, **create a pull request** (optionally pushing
  first), issues, CI pipelines.

**Customisation**
- **Custom commands** with placeholders (`$REPO`, `$BRANCH`, `$SHA`, `$SHORT_SHA`, `$REF`, `$FILE`, `$FILE_ABS`,
  `$REMOTE`, `$REMOTE_URL`) for the repository, a commit, a branch or a file; run in the background (optionally
  showing the output) or in a terminal. `git …` commands use Jit's git and appear in the command history.
- **Issue links**: regex → URL rules (e.g. JIRA `[A-Z][A-Z0-9]+-\d+` → `https://…/browse/$0`), plus automatic
  `#123` links for hosted remotes and plain URLs.
- **Git configuration**: global name, email and common options (default branch, line endings, pull strategy,
  prune, auto upstream, signing, file mode) plus every entry of `~/.gitconfig`; per repository overrides in
  *Repository → Repository Settings*.
- **Terminal**: Git Bash, Windows Terminal, PowerShell, Command Prompt or a custom command.
- Light and dark themes.

**Transparency**
- *View → Git Command History* (`Ctrl+Shift+H`, or *Git Log* in the status bar): every git invocation with time,
  repository, exact command line, exit code, duration, standard input (patches, messages) and error output.
  Filter, hide read-only queries, copy the command, open a terminal in the repository to run it yourself.
- *Help → About* shows the version and the git commit Jit was built from.

## Requirements

- JDK 21 and Maven 3.9 to build (OpenJFX comes from Maven Central).
- Git 2.25 or newer on the `PATH` (or set its location in *Preferences → General*).

## Build and run

```bash
mvn javafx:run
```

```bash
mvn package
```

`mvn package` runs the tests and builds `target/jit.jar`, a runnable jar for the current platform
(`java -jar target/jit.jar [repository folder]`).

### Native executable

```bash
mvn -Pnative package
```

Builds `target/native/output/Jit/` with `Jit.exe` (Windows; `Jit.app` on macOS, `bin/Jit` on Linux) and a
minimal bundled Java runtime, so no Java installation is needed on the target machine. Copy the whole folder.
Build on the operating system you target, because the JavaFX native libraries are platform specific.

```bash
mvn -Pnative,windows-installer package
```

Builds an `.msi` installer instead (per-user install, Start menu and desktop shortcuts); add
`-Djpackage.type=exe` for a setup `.exe`. Installers need the [WiX Toolset 3.x](https://github.com/wixtoolset/wix3/releases)
on the `PATH`. Set `-Dnative.version=1.2.3` for the version shown by the installer.

## Settings storage

*Preferences → Storage* chooses where Jit's own settings (open and recent repositories, window, theme, diff tool,
terminal, custom commands, issue links) are kept; switching moves all settings and removes them from the old place.
Git's configuration always stays in git.

| Location | Where |
|---|---|
| System preferences (default) | Windows registry `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\jit-git-client` (Java preferences on other systems) |
| Settings file in the user folder | `~/.jit/settings.properties` |
| Portable settings file | `jit.properties` next to `Jit.exe` (or the jar), so a copied folder keeps its settings |

At start-up the portable file wins, then the user file, then the registry. `-Djit.settings=<file>` forces a
specific file.

## Keyboard shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+T` / `Ctrl+W` | new tab / close tab |
| `Ctrl+PgDn` / `Ctrl+PgUp` | next / previous tab |
| `Ctrl+O` / `Ctrl+Shift+O` | open / clone repository |
| `Ctrl+1` / `Ctrl+2` | local changes / all commits |
| `Ctrl+F` | search commits |
| `Ctrl+Shift+C` | focus the commit message |
| `Ctrl+Enter` | commit (in the message fields) |
| `Ctrl+Shift+F` / `L` / `P` | fetch / pull / push |
| `Ctrl+Shift+S` | stash |
| `Ctrl+Shift+B` | new branch |
| `Ctrl+Shift+H` | git command history |
| `Ctrl+Alt+T` / `Ctrl+Alt+E` | terminal / explorer |
| `Ctrl+,` | preferences |
| `F5` | refresh |
| `Enter` / `Space` on a file | stage / unstage |
| `Delete` on an unstaged file | discard |
| `Alt+Up` / `Alt+Down` | move a commit in the interactive rebase dialog |

## Project layout

| Package | Contents |
|---|---|
| `jit.git` | git process wrapper, command log, repository operations, configuration |
| `jit.diff` | unified diff parser and partial patch builder (hunk / line staging) |
| `jit.graph` | commit graph lane layout |
| `jit.hosting` | GitHub / GitLab / Bitbucket / Azure DevOps URL builder |
| `jit.settings` | settings stores: Java preferences (registry) or properties file |
| `jit.ui` | JavaFX views, dialogs, themes |

The git layer is covered by integration tests against a real git (staging lines and hunks, CRLF files, stashes,
merge conflicts, push/pull, interactive rebase, commit options) — `mvn test`.

Developer aids:
- `-Djit.snapshots=<folder>` walks through the main screens, saves PNG snapshots and exits.
- The application icon is drawn in `jit.ui.AppIcon`; after changing it, run `jit.dev.IconGenerator` (from the project
  folder, with the JavaFX jars on the class path) to regenerate the window icons in `src/main/resources/jit/icons`,
  `packaging/windows/jit.ico` (the `Jit.exe` icon) and `packaging/linux/jit.png`. The `native` profile picks the
  launcher icon for the build OS automatically.
