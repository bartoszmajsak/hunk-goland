# Hunk Session user guide

Hunk Session lets you review changes in GoLand, leave reviewer comments on code lines, and see
responses from an agent. **Install upstream Hunk separately before using the plugin.** The plugin
ZIP does not contain the Hunk executable.

## Before you start

- GoLand 2026.2 or later within the verified compatibility range. The development build has been
  verified against GoLand builds `262.9437.286` and `263.5701.47`.
- Upstream [Hunk](https://github.com/modem-dev/hunk). This plugin's native review integration has
  been tested against **Hunk 0.20.0**. Compatibility with later versions is not guaranteed.
- Git and a local Git-backed project containing changes or a branch/revision comparison to review.

The automated desktop and native Hunk tests currently run on Linux. Hunk's own platform support
does not establish that this plugin has been tested on every supported operating system.

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
tested **0.20.0** version. Keep the absolute executable path reported by `command -v hunk` for the
GoLand setting below. An IDE launched from a desktop menu may have a different `PATH` from your
terminal.

## Install and configure the plugin

1. Obtain the plugin ZIP. The current local development bundle is
   `build/distributions/hunk-goland-0.1.0.zip`; see the [README](../README.md#how-to-install) to build it.
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

Reviewer notes can be deleted using their trash action and inline confirmation. Editing a saved
reviewer note is not supported by the tested Hunk version. Comments target one source line; a
multi-line selection does not create a code-range comment.

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
history in GoLand; they are not published as new reviewer comments. If an agent changes the diff,
an old anchor may no longer belong to the current review.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Hunk cannot start | Run `hunk --version` in a terminal and configure its absolute executable path in GoLand. Confirm the installed version against the tested version above. |
| Daemon cannot be reached | Confirm the local host/port setting. If you run a daemon as a service, it must use that port; `hunk daemon serve` can also start it manually. Retry the thread after correcting the setting. |
| No review is open | A running daemon alone is not a review. Invoke **Add Hunk Comment…** to start the matching managed review. |
| Review does not match this comparison | Reopen the intended GoLand comparison, leave **Preferred session ID** empty, and retry the matching IDE-managed review. |
| A line cannot be commented on | Check that it is on the current/right side and inside a published Hunk hunk. Fixed revision comparisons must be reviewed in their diff. |
| Submission timed out | Inspect the live session with `hunk session review <id> --include-notes` before retrying, to check whether it already contains the comment. Enable diagnostics to capture the failed stage. |
| Session list says `comments: 0` | Inspect the session with `--include-notes`; that list counter does not count reviewer notes in Hunk 0.20.0. |
| A plugin update has no effect | Reinstall the new ZIP and accept GoLand's reload/restart prompt. |

For runtime failures, enable **Settings | Tools | Hunk Session | Enable diagnostic logging**,
reproduce the problem, and open **Help | Show Log in Files**. Look for `dev.hunk.goland` and
`event=hunk-diagnostic-`. Plugin diagnostics omit comment bodies and tokens, but logs can contain
repository paths. Review them before sharing. Disable diagnostic logging when finished.
