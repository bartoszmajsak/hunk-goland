# Hunk Session for GoLand

Review a live Hunk changeset without leaving GoLand. The plugin shows Hunk reviewer notes and agent
responses on code lines, and lets you add genuine Hunk reviewer comments from normal editors and
diff viewers.

For the integration decisions, failed approaches, upstream constraints, and current evidence, see
[JOURNEY.md](JOURNEY.md).

## What works

- **Normal editors:** open a line-attached thread from **Add Hunk Comment…**.
- **Diff viewers:** comment from the toolbar, editor menu, or gutter menu. Hunk markers and threads
  appear only on the current/right (`new`) side, including one-sided added-file diffs.
- **Diff-line validation:** changed and context lines within a Hunk hunk are accepted; arbitrary
  lines outside the diff are rejected. A line does not need an existing thread.
- **Reviewer and agent provenance:** reviewer notes (`source: "user"`) and agent responses
  (`source: "agent"`) have distinct cards and gutter glyphs.
- **Modern inline UI:** chronological, natural-height, collapsible Markdown cards, independently
  expandable conversation and composer areas, and inline deletion confirmation beneath the
  reviewed line—no dialog or popup.
- **Reviewer identity:** when upstream Hunk reports its default `author: "user"`, the UI presents
  the effective repository `git user.name`; explicit reviewer and agent author names stay intact.
- **Keyboard submission:** `Ctrl+Enter` adds a comment; Enter inserts a newline.
- **Review navigation:** commented files carry a blue Hunk badge in native VCS change trees, and the
  status bar shows the number of commented files and notes. Each thread has clickable
  previous/next controls labeled with the active keymap shortcuts; the defaults are `Ctrl+Alt+,`
  and `Ctrl+Alt+.` and navigation wraps across files.
- **Native deletion:** editable reviewer notes are removed through Hunk's `comment-rm` action. Hunk
  does not currently expose editing for a saved reviewer note.
- **Two-way refresh:** notes added by another Hunk client appear in an open thread and in gutter
  markers within the two-second refresh interval. Unchanged refreshes do not rebuild the thread,
  and changed data preserves the message-scroll position.
- **Restart recovery:** GoLand keeps a bounded, non-roaming recovery snapshot. When an IDE-managed
  Hunk process disappears during an IDE restart or plugin update, the next exact matching review
  replays missing reviewer comments through Hunk's native composer and keeps missing agent responses
  as read-only history labeled **Recovered locally**.
- **Agent handoff:** **Copy agent prompt** puts Hunk's agent-skill prompt on the clipboard.
- **Matching reviews:** adding a comment automatically starts `hunk diff --watch` for a working tree,
  `hunk diff <base> --watch` for GoLand's **Compare with Branch** current-version view, or
  `hunk diff <before>..<after> --watch` for a comparison between two concrete revisions.
- **Shared current-version context:** after **Compare with Branch** starts a base-to-working-tree
  review, opening the same file normally keeps that exact review. Comments and agent responses are
  visible and writable from either view without launching a competing working-tree session.

Reviewer comments are currently single-line anchors. Multiline Markdown bodies are supported, but a
multi-line code selection uses its first line because Hunk's reviewer composer does not expose a
multi-line target.

## How it works

Hunk 0.20.x does not expose a public HTTP operation that creates a reviewer note with draft text;
`comment-add` intentionally creates an agent annotation. To preserve provenance, the plugin starts
an unmodified Hunk TUI in an off-screen PTY that GoLand owns. It uses Hunk's native session API to
navigate to the selected line, then drives the real `c` → body → `Ctrl+S` reviewer-composer path.
Nothing is shown in GoLand's Terminal tool window.

If an independently opened Hunk TUI already owns the worktree, GoLand can read its notes and delete
editable notes, but cannot type into that process. Invoking **Add Hunk Comment…** automatically
starts a separate off-screen Hunk process, binds it by its exact session ID, and leaves the external
TUI open. The setup button remains available as a retry only when automatic startup fails.

No token or manual pairing is required. The plugin uses Hunk's loopback `/session-api`. Hunk's
session WebSocket is the TUI-to-daemon producer connection, not a public observer stream, and the
protected SSE capability is deliberately not recoverable from a stock TUI. The plugin therefore
uses bounded loopback polling every two seconds for external invalidation; requests are small and do
not block the IDE thread.

Hunk sessions and reviewer notes are process memory, not a durable session database. They disappear
when the owning TUI exits. To bridge that lifecycle, the plugin stores up to 20 non-empty review
snapshots for 30 days in JetBrains' project cache. The cache is local to this IDE installation,
does not roam or modify the repository, contains comment bodies in plain text, and may be removed by
**Invalidate Caches**.

Recovery is deliberately stricter than matching `(file, line)`: it requires the same canonical
repository, exact review input, resolved Git commits, Hunk file/hunk structure, and—for working-tree
comparisons—the current contents of every changed file. If identity cannot be verified or the diff
changed, GoLand does not replay anything and explains that the saved items were left stale. Replayed
reviewer comments receive new native Hunk IDs and timestamps because upstream cannot preserve the
originals; GoLand retains their cached display time so the conversation stays chronological. Agent
responses cannot be recreated without falsifying provenance, so they remain clearly marked local
history until the agent publishes them again.

## Requirements

- GoLand 2026.2 (`262.*`).
- Upstream Hunk 0.20.0 or newer, available as `hunk` on `PATH` or configured explicitly.
- JDK 25 for building; GoLand's bundled `jbr` is suitable.

Install upstream Hunk using one of its documented methods, for example:

```bash
curl -fsSL https://hunk.dev/install.sh | sh
hunk --version
```

This project always targets upstream `modem-dev/hunk`; no companion Hunk fork is used.

## How to install

1. Build the ZIP:

   ```bash
   ./gradlew clean buildPlugin

   # Optional: reuse an installed GoLand platform.
   JAVA_HOME=/path/to/GoLand/jbr \
     ./gradlew -PplatformPath=/path/to/GoLand clean buildPlugin
   ```

2. In GoLand, open **Settings | Plugins**, choose the gear menu, then
   **Install Plugin from Disk…**.
3. Select `build/distributions/hunk-goland-0.4.0.zip` and restart GoLand when prompted.
4. In **Settings | Tools | Hunk Session**, confirm the loopback daemon address and Hunk executable.
   Defaults are `127.0.0.1:47657` and `hunk`.
5. On a diff-relevant line, choose **Add Hunk Comment…**. GoLand starts the matching off-screen Hunk
   review automatically, and Hunk starts its daemon when one is not already running.

Overwriting a ZIP does not hot-reload an installed plugin. Reinstall the new ZIP and accept
GoLand's reload/restart prompt.

### Session selection

Each live Hunk TUI registers a random session ID, working directory, process ID, and detected VCS
root with the daemon. GoLand resolves the file's VCS root and normally lets Hunk select the matching
live registration. Use `hunk session list` to inspect registrations.

In upstream Hunk 0.20, the `comments:` value printed by `hunk session list` is the legacy live agent
annotation count, not the number of saved reviewer notes. A session can therefore correctly show
`comments: 0` while containing GoLand reviewer comments. Inspect both kinds with:

```bash
hunk session review <session-id> --include-notes
```

The authoritative content is `reviewNotes`; GoLand verifies the newly created `source: "user"` note
there before it reports submission success. `reviewNoteCount` is the size of that exported note set,
so it includes agent entries when present; distinguish provenance with each note's `source` field.

The optional **Preferred session ID** setting attaches read/delete operations to a specific external
session. Clear it before starting an IDE-managed review. Closing the owning TUI removes the live
registration.

### Runtime diagnostics

The plugin always writes body-free lifecycle events such as `event=review-target-resolved`,
`event=managed-review-session-registered`, and `event=review-comment-submit-succeeded` to GoLand's
`idea.log`. Open **Help | Show Log in Files** and filter for `dev.hunk.goland` or `event=`.

For a difficult runtime failure, enable **Settings | Tools | Hunk Session | Enable diagnostic
logging** before reproducing it. The additional `event=hunk-diagnostic-…` records correlate:

- daemon request IDs, action names, selector kind, HTTP status, response size, and latency;
- managed-session scans by process ID and exact review input;
- snapshot file/note counts split by reviewer/agent provenance;
- reviewer-composer PTY stages; and
- each exact-note persistence attempt and whether a location candidate or exact match appeared.

Diagnostic mode never records comment bodies, terminal transcripts, HTTP payloads/responses, or
capability tokens. Disable it after reproducing to avoid routine two-second refresh entries. Driver
tests enable it with `-Dhunk.diagnostics=true`, and the real-project diagnostic asserts that the
expected events reached its copied `idea.log`.

## Build and verify

With no override, Gradle resolves the pinned GoLand platform from JetBrains repositories. For a
faster local build, set `GOLAND_HOME` or `-PplatformPath`:

```bash
./gradlew test buildPlugin verifyPlugin
JAVA_HOME=/path/to/GoLand/jbr GOLAND_HOME=/path/to/GoLand ./gradlew buildPlugin
```

The same JetBrains Driver tests support three display adapters:

```bash
./scripts/run-ui-tests native integrationTest       # visible on the current desktop
./scripts/run-ui-tests weston integrationTest       # headless native Wayland
./scripts/run-ui-tests xvfb integrationTest         # headless X11; used in CI
```

`integrationTest` launches real GoLand against a generated, self-contained Go module and Git
repository. It covers editor and diff actions, right-side-only comments, a line without an existing
thread, true unified-view line mapping, a submitted comment on a net-new file, Markdown,
card/composer collapse and expansion, chronological ordering, stable message scrolling, the
commented-file indicator, cross-file next/previous navigation, deletion, external refresh, gutter
glyphs, missing-session recovery, exact working-tree/revision commands, and one shared
Compare-with-Branch session across the diff and normal source editor. It uses Awaitility condition
waits rather than fixed sleeps.

Screenshots and the reviewable HTML report are written to
`build/reports/ux/index.html`; the focused cross-view report is
`build/reports/ux/cross-view/index.html`.

`realHunkE2e` is enabled and runs separately against an installed, unmodified upstream Hunk binary:

```bash
./scripts/run-ui-tests weston realHunkE2e \
  -PhunkExecutable=/absolute/path/to/hunk
```

It first registers an independently owned upstream TUI review, then proves that GoLand can start a
separate managed review for the same repository without displacing it. The test verifies two
sequential reviewer comments on one line (including a long multiline Markdown body), another
comment after navigating to a different hunk line, exact daemon persistence as `source: "user"`, an
externally added `source: "agent"` note appearing in the open GoLand thread, and native deletion
without losing the remaining notes. It also runs the real
`hunk session review <id> --include-notes --json` command against the live managed session before the
agent update, before deletion, and after deletion, asserting counts, provenance, bodies, and line
targets. Machine-readable evidence, including both distinct session IDs, the CLI inspections, and
every daemon state transition, is written to `build/reports/ux/real-daemon-e2e.json`.

A second real-upstream scenario restarts the same GoLand profile after its owned TUI exits. It
requires a new Hunk session ID, re-verifies the recovered reviewer comment as `source: "user"` in the
new live daemon session, proves the old agent response was not republished as a reviewer note, and
checks its read-only local attribution in the UI. Its evidence is
`build/reports/ux/real-daemon-recovery-e2e.json`; its screenshot report is
`build/reports/ux/recovery/index.html`.

For the reported KServe comparison regression, `realProjectDiagnostic` opens the requested KServe
worktree in an isolated GoLand profile and drives its real **Compare with Branch** UI. Despite
retaining the full diagnostic bundle, this is a gating test: it fails unless the exact reported
`config_loader.go:100` line and a net-new-file gutter line both reach a writable Hunk composer, the
managed working-tree process is replaced by the exact `hunk diff <base> --watch` review, and that
review registers with the daemon.

```bash
./scripts/run-ui-tests weston realProjectDiagnostic \
  -PhunkRealProject=/absolute/path/to/worktree \
  -PhunkRealBase=master \
  -PhunkRealHead=upstream/llmisvc/feat/lora-regex-routing \
  -PhunkDiagnosticWarmupWorkingTree=true

# Optional: reproduce with a copy of an existing GoLand configuration.
# Add -PhunkDiagnosticConfig=/absolute/path/to/GoLand/config
```

The report under `build/reports/real-project-diagnostic/` contains the screenshot HTML, Swing
hierarchies, diff `DataContext`, daemon sessions, Hunk PIDs/arguments/working directories, and the
isolated `idea.log`. Lifecycle and opt-in diagnostic log events use stable `event=…` names and never
include comment bodies or tokens.

GitHub Actions uses Xvfb to run both the visual fixture and real-upstream tasks, then runs Plugin
Verifier and uploads the HTML report, screenshots, daemon evidence, diagnostics, and installable ZIP.

## Current constraints

- Only loopback daemon hosts are accepted, matching Hunk's local-session security model.
- Diff comments are intentionally right/new-side only.
- Comments may target only lines contained in Hunk's published hunks.
- Saved reviewer comments can be deleted but not edited because upstream Hunk has no saved-note edit
  intent.
- Recovery is local cache assistance, not upstream session persistence: recovered reviewer comments
  have new native IDs/timestamps, while recovered agent responses are read-only local history.
- Push-driven SSE synchronization needs an upstream-supported way to delegate the protected review
  capability to the IDE. Until then, the two-second refresh loop is the honest stock-Hunk path.
