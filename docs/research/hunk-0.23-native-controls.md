# Hunk 0.23 native controls and safe integration boundaries

Audited on 2026-10-01 against the unmodified `v0.23.0` release, commit
`7991d3034af1d2116e9c77db31265e409a73bd9f`. The native probe used the installed release binary,
an isolated temporary Git fixture, private credential/configuration directories, a loopback
daemon, and an owned PTY. It did not use a patched source checkout, preload, personal session,
or an externally owned terminal. Terminal content and note bodies were inspected only in memory;
the emitted evidence contained result flags, range coordinates, and failure classes.

## Feasibility summary

| Operation | Stock upstream behavior | Safe plugin boundary |
| --- | --- | --- |
| Reviewer range inside a published hunk | Native `v`, source-side movement, `c`, `Ctrl+S`; verified through the daemon | Feasible through the owned composer, with exact range read-back |
| Edit the currently active reviewer note | Native `E` and `Ctrl+S` preserve its ID; verified in the probe | The primitive works, but does not identify an arbitrary saved note |
| Reply to the currently active saved reviewer note | Native `R` and `Ctrl+S` preserve its parent ID; verified in the probe | The primitive works, but arbitrary parent selection still needs trustworthy identity |
| Reply to a saved agent note | Native selectors permit it when that stored card is active | Do not substitute agent API creation for a reviewer reply; exact card targeting was not proved |
| Select a saved reviewer note by session API ID | Not exposed | Do not infer it from line navigation or `editable: true` |
| Add a single-line note in expanded unchanged source | Native gap expansion plus an attested source line is supported in source | Requires a provable exact expanded line, not a blind `z` toggle |
| Range through unpublished/expanded gaps | Native range projection rejects expanded-gap rows | Keep ranged composition inside published hunks |
| Subscribe to protected review SSE | A process-private review capability is required | Caller credentials alone do not delegate that capability |

These are separate claims: a working native keyboard primitive is not proof that the plugin can
select its intended saved-note ID safely. The command definitions and semantic selectors are
the authoritative controls. [Command catalog](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/core/run/commandCatalog.ts),
[review selectors](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/core/review/selectors.ts).

## Reviewer ranges: actionable native sequence

For a current/right-side range wholly inside one published hunk:

1. Use authenticated session navigation to reveal the exact `new` start line; require a line
   reveal rather than a hunk-only fallback.
2. Wait for the owned terminal to render that navigation. Send `v` and acknowledge its render.
3. Send `j` or Down once per additional source line, acknowledging each render.
4. Send `c`. Verify the file and full draft range label before pasting the body.
5. Save with `Ctrl+S`. Require a previously unseen daemon reviewer note with the exact body,
   file, `source: "user"`, and full `newRange` before reporting success.

While visual selection is active, movement uses the anchored side's line-cursor candidates.
It skips note cards and old-only rows; it does not count terminal wrapping or card height as
source lines. The selection projection requires contiguous numbered source within one file and
rejects expanded-gap rows. These rules make source-side movement safer than counting visible
terminal rows. [Selection controller](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/components/panes/useDiffSelectionController.ts),
[selection projection](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/components/panes/copySelection.ts),
[App movement dispatch](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/App.tsx).

The isolated probe navigated to R12, sent `v`, `j`, `j`, `c`, and saved. An existing R13
reviewer card and old-side replacement rows were present. The daemon returned a reviewer note
with `newRange: [12, 14]`. The native header was `Draft note - main.go R12–R14`.
The range separator is U+2013, and both endpoints carry `R`; a single line is `R12`. The
preferred anchor is the focused endpoint, so do not mistake it for the entire range.
[Range formatting](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/lib/agentAnnotations.ts),
[draft header construction](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/components/panes/AgentInlineNote.tsx).

A separate probe sequence, `navigate(R12); v; navigate(R14); c`, also produced `[12, 14]`.
This observation is not a guarantee that every asynchronous navigation preserves selection:
the proven per-render source-side movement path has the clearer state guards.

The key sequences assume effective stock bindings and a supported line-cursor mode. Personal
configuration can remap commands. In 0.23, previous/next saved-note commands have no default
binding; `n`/`N` are search commands, not a reliable note-navigation contract.
[Command catalog](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/core/run/commandCatalog.ts).

## Native editing and replies: real primitives, missing exact targeting

`E` edits the active stored reviewer note only when it is editable and no draft is open.
Its native draft carries `targetNoteId`. Saving updates the same stored object, retaining its
ID, original creation time, anchor, author, and parent; it updates the body and edit time.
It is not delete-and-recreate. `R` creates a reviewer reply to the active stored reviewer or
agent note, retaining that note's ID as `parentId` and inheriting its anchor. Static decorations
which never entered the review store are not equivalent to saved, replyable notes.
[Draft and save planning](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/core/review/intents.ts),
[active-note eligibility](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/core/review/selectors.ts),
[native composer](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/hooks/useUserNoteComposer.ts).

The probe successfully edited the reviewer note known to be active immediately after creation.
For complete body replacement, Home (`ESC[H`) followed by Shift+End (`ESC[1;2F`) selected the
buffer, then bracketed paste replaced it; `Ctrl+S` preserved the original note ID and creation
time. Hunk's pinned OpenTUI 0.5.6 binds Home/End to buffer boundaries and Shift variants to
buffer selection. `Ctrl+A` is **line-home**, not select-all. Super+A is a separate select-all
binding. [Pinned textarea bindings](https://github.com/anomalyco/opentui/blob/v0.5.6/packages/core/src/renderables/Textarea.ts),
[Hunk dependency lock](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/bun.lock).

The subsequent `R` saved a reviewer child whose daemon `parentId` matched that known-active
reviewer ID. These successes do **not** validate editing or replying to an arbitrary preexisting
card. An additional attempt to reach a newly added agent card by line navigation and one `j`
did not obtain the expected reply-composer acknowledgement. That probe did not establish a
reliable agent-card targeting sequence, and no reviewer reply was claimed from it.

The missing identity boundary is concrete:

- `session navigate --comment <id>` searches `liveComments`, which contains live agent annotations,
  not reviewer notes. It lowers the selected comment to file/side/line coordinates. It does not
  activate the semantic note card; a reviewer ID is not a supported target there.
- Ordinary source-line navigation selects a code line. The TUI's separate vertical-stop controller
  activates note cards by exact internal ID when clicking a card or traversing rendered stops.
- Ordinary session snapshots expose saved notes and publication revision, but no active note ID,
  draft kind, target note ID, or draft parent ID. The card/draft title shows a source range, not ID.
- Public extensions expose immutable review notes and revision, source-line navigation, and
  enabled public commands with movement counts. They do not expose `selectNote(id)`, active note
  identity, an editable draft buffer, or a review-capability getter.

Consequently, line labels, card ranks, bodies, or `editable: true` alone cannot prove which exact
saved note `E`/`R` will mutate. Duplicate anchors and bodies are valid; external agent updates can
change rendered stops between checks. Read-back detects a wrong edit after it happened, which is
not an adequate pre-mutation guard. Keep arbitrary-ID editing/reply creation unavailable until
there is a supported identity-aware control path. Do not scrape body fragments or extend a stock
protocol with invented fields. [Broker navigation resolution](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/session/broker/brokerServer.ts),
[native stop and line navigation](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/hooks/useTerminalReview.ts),
[session snapshot types](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/session/types.ts),
[public extension API](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/extension-api/types.ts).

Deletion is independently identity-addressed by the session API. Native semantic removal rejects
a note with saved descendants: delete leaf replies first, and do not silently discard a thread.
[Removal eligibility](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/core/review/selectors.ts),
[session deletion contract](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/session/agent/surface.ts).

## Expanded unchanged source: narrower than arbitrary out-of-diff comments

The stock `z` command toggles an expandable context gap when the file has an available source
identity/fetcher. Its policy is the selected hunk's leading gap first, then later hunks' leading
gaps, then the trailing gap. It is **not** an arbitrary line-addressed gap command. Repeating it
can collapse an already expanded gap; there is no public session API expansion-state getter.
[Gap selection policy](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/core/review/selectors.ts),
[gap toggling and source loading](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/hooks/useTerminalReview.ts).

After the intended source gap has loaded, exact source navigation can reveal a real expanded
line. Native `c` can start a **single-line** draft there, using an attested `expandedLineSource`
identity/range that the semantic planner validates. A line hidden in a collapsed gap instead
falls back to hunk navigation, so the client must check the reveal result before composing.
Files without available attested source are not supported by this path. Range projection still
rejects expanded-gap rows, so expansion does not authorize arbitrary multi-line anchors outside
the published diff. [Expanded-line draft proof](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/core/review/intents.ts),
[exact line reveal](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/hooks/useTerminalReview.ts),
[range projection restrictions](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/ui/components/panes/copySelection.ts).

This expanded-line sequence is source-confirmed, not successfully validated by the completed
probe. It remains a separate implementation/test requirement. The safe plugin default is to
reject unpublished targets rather than blindly toggle context and treat hunk fallback as a line.

## SSE and semantic actions: caller identity is not a review capability

Signed caller authentication grants the supported session discovery/control contract. Protected
review publication/events/actions routes independently require the clear process capability.
Stock Hunk creates it inside the owning process and publishes only its digest; caller or daemon
credentials cannot reconstruct it. The capability survives that process's reloads, not its exit.
[Capability lifetime](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/app/review/capability.ts),
[protected review authorization](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/session/broker/browserReviewServer.ts),
[caller contract](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/session/broker/appContract.ts).

The URL helper can encode that capability in a fragment, but no released production call site
hands it to the plugin through a supported CLI command, terminal menu, environment option,
session response, or public extension accessor. A legitimately supplied owner URL would satisfy
the separate capability model; it is not an automatic stock-plugin acquisition workflow.
No supported delegated SSE or semantic-action authority was found in the released contract.
Do not import private source helpers, inspect process memory, scrape tokens, claim an external
TUI, or impersonate its producer credential to bridge this gap. Bounded authenticated session
polling remains the available automatic two-way refresh path.
[Capability helper](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/app/review/capability.ts),
[released CLI](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/app/cli.ts),
[session protocol](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/session/protocol.ts),
[extension contract](https://github.com/modem-dev/hunk/blob/7991d3034af1d2116e9c77db31265e409a73bd9f/packages/hunk/src/extension-api/types.ts).

## Verification boundary

This note records release-source findings and isolated native experiments. It does not substitute
for the project's enabled `realHunkE2e`, same-profile restart gate, or GoLand Driver suite.
Ranged creation is covered by the project's native read-back and restart gates; existing reply
metadata is retained without claiming native arbitrary-parent reply creation. Reviewer editing, arbitrary
reply creation, expanded-context composition, and capability-based push refresh must not be
advertised as delivered solely because upstream contains the corresponding internal primitive.
