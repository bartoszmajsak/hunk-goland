# Hunk Session user guide

Hunk Session lets you review changes in GoLand, leave reviewer comments on code lines, and see
responses from an agent. **Install upstream Hunk separately before using the plugin.** The plugin
ZIP does not contain the Hunk executable.

## Before you start

- GoLand 2026.2 or later within the verified compatibility range. The development build has been
  verified against GoLand builds `262.9437.286` and `263.5701.47`.
- Upstream [Hunk](https://github.com/modem-dev/hunk). This plugin's native review integration has
  been tested against **Hunk 0.23.0**. Compatibility with other versions is not guaranteed.
- Git and a local Git-backed project containing changes or a branch/revision comparison to review.

The automated desktop and native Hunk tests currently run on Linux. Hunk's own platform support
does not establish that this plugin has been tested on every supported operating system.

Local authentication is automatic. Run Hunk and GoLand as the same OS user, with the same
`XDG_RUNTIME_DIR` (or the same home-directory fallback). Hunk creates its owner-private credentials;
the plugin reads them and authenticates requests without a manual token or pairing step. The
installed Hunk executable and the running daemon must both be 0.23.0. Inspect `hunk daemon status`
after an upgrade: replacing the executable does not update an already-running daemon. Save or copy
important notes before deliberately running `hunk daemon restart` or restarting your daemon service.
This can disconnect incompatible review windows and lose their in-memory notes. The local recovery
cache is not a backup.

You do not need Bun, Node.js, or a separate JDK when using Hunk's standalone binary and installing
the plugin ZIP. A JDK is needed only to build the plugin from source.

## Install Hunk

Follow [upstream's installation instructions](https://github.com/modem-dev/hunk#install). Its
standalone installer for Linux and macOS is:

```bash
curl -fsSL https://hunk.dev/install.sh | sh
hunk --version
command -v hunk
```

The installer normally places Hunk under `~/.hunk`. It may install a newer release than this
plugin's tested version; use upstream's release installation/update instructions if you need the
tested **0.23.0** version. Keep the absolute executable path reported by `command -v hunk` for the
GoLand setting below. An IDE launched from a desktop menu may have a different `PATH` from your
terminal.

## Install and configure the plugin

1. Obtain `hunk-goland-0.1.0.zip` from the project's release assets, or
   [build it from source](#build-from-source). The local build produces
   `build/distributions/hunk-goland-0.1.0.zip`.
2. In GoLand, open **Settings | Plugins**, then the gear menu and **Install Plugin from Disk…**.
3. Select the ZIP and accept the IDE's reload/restart prompt.
4. Open **Settings | Tools | Hunk Session**:
   - Set **Hunk executable** to `hunk`, or the absolute path found above.
   - Keep **Daemon host** at `127.0.0.1` and **Daemon port** at `47657`, unless your local daemon
     uses a different port. Only local loopback connections are supported.
   - Leave **Preferred session ID** empty for ordinary reviewing and comment creation.
5. Apply the settings and open your Git project.

Replacing the ZIP on disk does not update an installed plugin. Reinstall each new bundle and
accept GoLand's reload/restart prompt.

## Start a review and add a comment

For uncommitted changes, open a file normally or open its diff from GoLand's Changes/Commit view.
For a branch review, use GoLand's **Compare with Branch** and select the base branch. The plugin
starts a matching Hunk review when you invoke **Add Hunk Comment…**.

In a normal editor, use the editor context menu. In a diff, use the editor or line-number context
menu, or the Hunk toolbar action. Select a line on the current/right side, including a line in a
newly added file. The line must be inside a hunk published by Hunk: changed lines and surrounding
context qualify; arbitrary lines elsewhere do not. A line does not need an existing comment.

To comment on a block, select its code and invoke the editor menu or Hunk toolbar action. The
selection must stay inside one Hunk hunk on the current/right side. The thread shows the inclusive
range and each range card labels its endpoints. A selection ending at the next line's start does
not include that line. A line-number menu always targets the clicked line, not an existing selection.

The review thread appears below the line. Write your comment in the Markdown composer and press
**Ctrl+Enter** or click **Add comment**. Enter inserts a newline. Expand the composer or conversation
area for longer text, and collapse individual comment cards when useful. The newest comment appears
last. GoLand reports submission success only after Hunk confirms the reviewer note in its live
session; a timeout is not a successful submission.

GoLand starts the required Hunk process off-screen and Hunk starts its daemon if necessary. You do
not need a visible terminal review, a manual token, or a pairing step. Once a branch comparison is
active, normal editors in that repository share that current-version review. Comparisons between
two fixed revisions remain in the diff viewer.

## Find comments and read agent responses

Open **Tools | Show Hunk Review**, or click the Hunk status-bar count. The **Hunk Review** pane groups
threads by file and line and shows item counts and reviewer/agent glyphs. Double-click a line, press
Enter, or choose **Open Thread** to open its conversation. Commented files also have Hunk badges in
GoLand's VCS change trees, and commented lines have gutter markers.

Use **Previous Thread** and **Next Thread** in the pane or the inline thread to move across files.
The default shortcuts are **Ctrl+Alt+,** and **Ctrl+Alt+.**; controls display the shortcuts from your
active keymap. Change them under **Settings | Keymap** by searching for the Hunk comment-thread
actions.

Click **Copy agent prompt** in a thread and paste it into your agent conversation. Responses
published into that Hunk session appear as agent cards. Live external changes normally appear on
the next two-second refresh; the pane also has a **Refresh** action.

Semantic replies show **Reply to** and the parent author's name. If that exact parent is absent,
the card says **Reply · parent unavailable**; matching text or line positions are not used to guess
the relationship. Replies keep the same oldest-to-newest order as other cards.

Reviewer notes can be deleted using their trash action and inline confirmation. A parent with live
replies shows **Has replies** instead: Hunk requires those replies to be removed first, by a client
authorized to remove them. Editing a saved
reviewer note is not yet supported by this plugin, although stock Hunk supports saved-note editing.
Comments can target one source line or a selected code range within one published hunk. Semantic
reply creation is not yet available in GoLand.

## Sessions and restarts

Hunk keeps a review in memory while its owning process runs. To inspect the plugin's live review:

```bash
hunk session list
hunk session review <session-id> --include-notes
```

In the tested Hunk version, `comments: 0` in the list counts agent annotations, not reviewer notes.
Use the review command to see reviewer comments; they have `source: "user"`. Agent responses have
`source: "agent"`. Opening another terminal review creates a separate session; it does not join
GoLand's managed review. The tested upstream version has no interactive TUI attach/join command.

GoLand keeps a local recovery cache of up to 20 non-empty reviews for 30 days. It contains comment
bodies in plain text outside your repository, does not roam, and can be removed by invalidating
IDE caches. It is not a backup. Recovery requires the same repository, comparison, revisions, hunk
structure, and working-tree contents. If those have changed, automatic replay is refused.

After a matching restart, missing reviewer comments are submitted into a fresh Hunk session and
receive new IDs and timestamps. Missing agent responses remain read-only **Recovered locally**
history in GoLand; they are not published as new reviewer comments. Cached reviewer replies also
remain read-only history because GoLand cannot safely recreate their parent relationship through
the native composer. Only a verified replay of the original parent remaps a cached reply to its new
ID; an unavailable parent is labeled explicitly. If an agent changes the diff,
an old anchor may no longer belong to the current review.

## Build from source

With JDK 25 and Git available, run from a checkout of this project:

```bash
./gradlew clean buildPlugin
```

The Gradle wrapper downloads the pinned build tools and GoLand platform. To reuse an installed
GoLand instead, point Gradle at its bundled runtime and installation:

```bash
JAVA_HOME=/path/to/GoLand/jbr \
  ./gradlew -PplatformPath=/path/to/GoLand clean buildPlugin
```

Install the resulting ZIP as described above. Building the plugin does not install Hunk.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Hunk cannot start | Run `hunk --version` in a terminal and configure its absolute executable path in GoLand. Confirm the installed version against the tested version above. |
| Daemon cannot be reached | Confirm the local host/port setting. If you run a daemon as a service, it must use that port; `hunk daemon serve` can also start it manually. Retry the thread after correcting the setting. |
| No review is open | A running daemon alone is not a review. Invoke **Add Hunk Comment…** to start the matching managed review. |
| Review does not match this comparison | Reopen the intended GoLand comparison, leave **Preferred session ID** empty, and retry the matching IDE-managed review. |
| A line cannot be commented on | Check that it is on the current/right side and inside a published Hunk hunk. Fixed revision comparisons must be reviewed in their diff. |
| Submission timed out | Inspect the live session with `hunk session review <id> --include-notes` before retrying, to check whether it already contains the comment. Enable diagnostics to capture the failed stage. |
| Session list says `comments: 0` | Inspect the session with `--include-notes`; that list counter does not count reviewer notes in Hunk 0.23.0. |
| Local authentication fails | Confirm the executable and running daemon are both 0.23.0, use the same OS user/runtime directory, and check ownership and private permissions of Hunk's `security-v1` directory. Do not make credential files world-readable. |
| A plugin update has no effect | Reinstall the new ZIP and accept GoLand's reload/restart prompt. |

For runtime failures, enable **Settings | Tools | Hunk Session | Enable diagnostic logging**,
reproduce the problem, and open **Help | Show Log in Files**. Look for `dev.hunk.goland` and
`event=hunk-diagnostic-`. Plugin diagnostics omit comment bodies and tokens, but logs can contain
repository paths. Review them before sharing. Disable diagnostic logging when finished.
