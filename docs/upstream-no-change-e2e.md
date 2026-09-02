# Real upstream Hunk E2E without upstream changes

Source of truth: `/home/bartek/code/oss/hunk` at commit
`c75a9a3b084059d8ff4ccfb257251e594a40b4a5`. The checkout was clean during this
audit. This note describes only APIs and behavior present at that commit. It does
not use or assume the `hunk-goland-hunk` fork.

## Verdict

The current upstream implementation supports the shipped real-daemon E2E with
one clearly labelled plugin bridge:

1. Run the real upstream Hunk TUI in a PTY owned by the test or plugin launcher.
2. Create reviewer notes through the real TUI input path (`navigate`, `c`, body,
   `Ctrl-S`), because the upstream HTTP action vocabulary cannot carry a draft
   body.
3. Read the resulting note from the native `/session-api` review snapshot.
4. Delete the reviewer note through upstream's supported deletion action, add an
   agent response from a separate `hunk session comment add` process, and verify
   both directions from the real daemon snapshot and GoLand UI.

This proves real Hunk storage, native deletion, and an external agent update.
It **cannot honestly prove that upstream currently
accepts the text of a GoLand reviewer comment through HTTP**. The PTY relay is the
temporary bridge for that one missing operation.

The implemented `realHunkE2e` task uses the installed unmodified upstream 0.20.0
binary, an isolated daemon port, a generated Git repository, an IDE-owned PTY,
and Awaitility waits. It requires neither a source checkout nor a capability
preload. Machine-readable evidence is `build/reports/ux/real-daemon-e2e.json`.

If controlling a Hunk TUI PTY is unacceptable, then all requested points cannot
be proved against this upstream commit without an upstream change. A fake
`review-action`, an actor kind of `ide`, or treating `comment-add` as a reviewer
note would test a made-up contract.

## Capability and pairing

### What upstream implements

The TUI process lazily generates a 32-byte random base64url token and caches it
for the lifetime of that process
([`src/app/review/capability.ts:29-45`](../../../oss/hunk/src/app/review/capability.ts)).
It intentionally survives review reloads but not a TUI process restart
([`src/app/review/capability.ts:10-13`](../../../oss/hunk/src/app/review/capability.ts)).
This is process-lifetime capability authorization, not a short-TTL token.

Registration sends only the token's SHA-256 digest
([`src/app/session/registration.ts:95-105`](../../../oss/hunk/src/app/session/registration.ts));
the daemon retains only that digest
([`src/session/broker/state.ts:198-220`](../../../oss/hunk/src/session/broker/state.ts),
[`src/session/broker/state.ts:272-279`](../../../oss/hunk/src/session/broker/state.ts)).
The design explicitly prevents the daemon, its registration payload, and logs
from recovering a usable token
([`src/app/review/capability.ts:4-8`](../../../oss/hunk/src/app/review/capability.ts),
[`src/session/reviewHttpProtocol.ts:13-21`](../../../oss/hunk/src/session/reviewHttpProtocol.ts)).

Every `/review-api` request must send the clear token in the
`hunk-review-capability` header
([`src/session/reviewHttpProtocol.ts:47-55`](../../../oss/hunk/src/session/reviewHttpProtocol.ts),
[`src/session/broker/browserReviewServer.ts:291-300`](../../../oss/hunk/src/session/broker/browserReviewServer.ts)).
It is never accepted in a query or route. The eventual browser URL carries it in
the fragment, which is not sent in the document request
([`src/session/reviewHttpProtocol.ts:149-172`](../../../oss/hunk/src/session/reviewHttpProtocol.ts)).

The daemon defaults to `127.0.0.1:47657`; `HUNK_MCP_HOST` and `HUNK_MCP_PORT`
override it
([`src/session/broker/brokerConfig.ts:3-8`](../../../oss/hunk/src/session/broker/brokerConfig.ts),
[`src/session/broker/brokerConfig.ts:50-74`](../../../oss/hunk/src/session/broker/brokerConfig.ts)).
Both the session and protected review surfaces enforce loopback Host/Origin
checks before routing requests
([`src/session/broker/brokerServer.ts:510-555`](../../../oss/hunk/src/session/broker/brokerServer.ts)).

### Why a normal plugin cannot obtain it

Upstream defines `reviewProcessUrl(origin, sessionId)`, but no runtime code calls
it
([`src/app/review/capability.ts:48-50`](../../../oss/hunk/src/app/review/capability.ts)).
The planned `--web`, `--no-open`, `hunk session open`, and recoverable terminal
URL are Phase 6 and have not landed
([`docs/browser-review-rebuild.md:118-129`](../../../oss/hunk/docs/browser-review-rebuild.md)).
The review page path is likewise declared for Phase 6, not served by the current
daemon
([`src/session/reviewHttpProtocol.ts:36-44`](../../../oss/hunk/src/session/reviewHttpProtocol.ts),
[`src/session/broker/brokerServer.ts:526-566`](../../../oss/hunk/src/session/broker/brokerServer.ts)).

Consequently:

- a stock TUI neither prints nor stores a recoverable clear token;
- a user has no normal upstream review URL to paste into GoLand;
- the token cannot be derived from the digest returned to the daemon;
- the session WebSocket is the TUI-to-daemon registration/snapshot/command
  connection, not a public observer stream
  ([`packages/session-broker/README.md:114-123`](../../../oss/hunk/packages/session-broker/README.md));
- a normally launched prebuilt Hunk binary cannot be paired for review SSE by
  an external plugin at this commit.

### Automatic capture for a controlled source E2E

A source-checkout E2E can capture the token without editing upstream. Launch the
TUI with a test-side Bun preload that imports the upstream
`reviewProcessCapability()` function and writes its token to a newly created,
mode-`0600` file (or an inherited private pipe), then runs upstream
`src/main.tsx`. Registration later calls the same cached module singleton, so the
captured clear token matches the digest registered by that TUI process.

Conceptually:

```ts
// Test-side preload; not part of upstream Hunk.
import { writeFileSync } from "node:fs";
import { reviewProcessCapability } from
  "/home/bartek/code/oss/hunk/src/app/review/capability.ts";

writeFileSync(process.env.HUNK_GOLAND_CAPABILITY_FILE!,
  reviewProcessCapability().token,
  { encoding: "utf8", flag: "wx", mode: 0o600 });
```

Launch the upstream source entry point with Bun's `--preload=<file>` and an
isolated `HUNK_MCP_PORT`. Start the daemon separately first so its process cannot
write the TUI's capture file. Discover `sessionId` by repository root through
`POST /session-api` after the TUI registers.

This is a valid controlled E2E technique and can be automatic for a
plugin-managed source session. It is not a supported pairing mechanism for an
arbitrary already-running or prebuilt Hunk process.

## Actual endpoints

The protected review surface has these routes
([`src/session/reviewHttpProtocol.ts:77-101`](../../../oss/hunk/src/session/reviewHttpProtocol.ts),
[`src/session/broker/browserReviewServer.ts:235-244`](../../../oss/hunk/src/session/broker/browserReviewServer.ts)):

| Method | Route | Result |
|---|---|---|
| `GET` | `/review-api/<sessionId>/publication` | Generation/revision address and resource catalog |
| `GET` | `/review-api/<sessionId>/events` | SSE publication/disconnect events |
| `POST` | `/review-api/<sessionId>/actions` | Forward a semantic review action to the TUI producer |
| `GET` | `/review-api/<sessionId>/resources/<generation>/<resourceId>` | Bounded file/patch/source resource bytes |

The existing loopback session API is `POST /session-api`. It has strict JSON
schemas for discovery, review reads, agent comments, and removal
([`src/session/protocolSchemas.ts:49-109`](../../../oss/hunk/src/session/protocolSchemas.ts)).
It is protected by loopback Host/Origin validation but not by the per-review
capability; the routing contains no capability check
([`src/session/broker/brokerServer.ts:515-547`](../../../oss/hunk/src/session/broker/brokerServer.ts)).

Useful request bodies are:

```json
{"action":"list"}
```

```json
{
  "action": "review",
  "selector": {"repoRoot": "/absolute/repo"},
  "includeNotes": true,
  "includePatch": true
}
```

```json
{
  "action": "comment-add",
  "selector": {"repoRoot": "/absolute/repo"},
  "filePath": "path/from/review.go",
  "side": "new",
  "line": 42,
  "summary": "External agent response",
  "author": "test-agent",
  "reveal": false
}
```

```json
{
  "action": "comment-rm",
  "selector": {"sessionId": "..."},
  "commentId": "user:..."
}
```

The `review` response includes notes only when `includeNotes:true`
([`src/session/broker/brokerServer.ts:246-254`](../../../oss/hunk/src/session/broker/brokerServer.ts),
[`src/session/broker/projections.ts:111-137`](../../../oss/hunk/src/session/broker/projections.ts)).
Each summary carries provenance, file and ranges, body, author, timestamps, and
editability
([`src/session/types.ts:181-194`](../../../oss/hunk/src/session/types.ts)).

## Reviewer-note creation: the hard gap

The protected action envelope is:

```json
{
  "protocolVersion": 1,
  "generation": "generation:...:0",
  "expectedStateRevision": 7,
  "actor": {
    "clientId": "goland:<uuid>",
    "kind": "browser",
    "displayName": "GoLand reviewer"
  },
  "action": {}
}
```

The envelope schema is defined at
[`src/session/reviewProtocol.ts:180-196`](../../../oss/hunk/src/session/reviewProtocol.ts).
Only `terminal`, `browser`, and `agent` are valid actor kinds; `ide` is rejected
([`src/session/reviewProtocol.ts:83-96`](../../../oss/hunk/src/session/reviewProtocol.ts),
[`src/session/reviewProtocol.ts:331-344`](../../../oss/hunk/src/session/reviewProtocol.ts)).
`browser` is the nearest current value for an IDE reviewer. Actor kind identifies
the surface; it is not note provenance or a trust claim
([`src/session/reviewProtocol.ts:83-112`](../../../oss/hunk/src/session/reviewProtocol.ts)).

The remote reviewer-note workflow was intended to be:

```json
{
  "type": "notes/start-draft",
  "fileKey": "file:...",
  "hunkIndex": 0,
  "target": {"side": "new", "line": 42},
  "reveal": {"anchor": "hunk", "scrollToNote": true}
}
```

followed by:

```json
{
  "type": "notes/create-user",
  "consumeDraft": true,
  "target": {"side": "new", "line": 42}
}
```

Those actions are real and validated
([`src/session/reviewProtocol.ts:380-409`](../../../oss/hunk/src/session/reviewProtocol.ts)).
However, there is no wire intent that updates the body. `draft/update` is an
internal reducer action used only by the TUI composer
([`src/core/review/actions.ts:34-38`](../../../oss/hunk/src/core/review/actions.ts),
[`src/ui/hooks/useTerminalReview.ts:1313-1319`](../../../oss/hunk/src/ui/hooks/useTerminalReview.ts)).
Saving a blank draft cancels it; only a nonblank draft becomes a `source:"user"`
note
([`src/core/review/intents.ts:415-445`](../../../oss/hunk/src/core/review/intents.ts)).
Upstream documents this as an explicit residual gap awaiting Phase 5
([`docs/browser-review-seam-audit.md:271-294`](../../../oss/hunk/docs/browser-review-seam-audit.md)).

Therefore neither a capability nor a manually constructed review URL makes
remote human-note composition work at this commit. Using `/session-api`
`comment-add` would also be semantically wrong: it dispatches `notes/add-live`
([`src/ui/hooks/useTerminalReview.ts:1120-1137`](../../../oss/hunk/src/ui/hooks/useTerminalReview.ts))
and is exported as `source:"agent"`, `editable:false`
([`src/ui/hooks/useTerminalReview.ts:1382-1394`](../../../oss/hunk/src/ui/hooks/useTerminalReview.ts)).

### No-change PTY bridge

For a real E2E now, the plugin/test launcher can own the TUI PTY and use only
real user-facing upstream behavior:

1. `POST /session-api` with `action:"navigate"`, `filePath`, `side`, and `line`.
2. Wait until the real TUI reports the selected target.
3. Write `c` to the PTY to open the reviewer-note composer.
4. Wait for `Draft note`/`Write a note`.
5. Write the exact GoLand comment body, then `Ctrl-S` (`\u0013`).
6. Wait for a higher publication revision and read it back as a user note.

Upstream's own real-PTY test uses exactly `c`, text input, and `Ctrl-S` and checks
the rendered saved reviewer note
([`test/pty/notes.test.ts:209-263`](../../../oss/hunk/test/pty/notes.test.ts)).
The upstream PTY harness launches the unmodified source entry point through Bun
([`test/pty/harness.ts:947-971`](../../../oss/hunk/test/pty/harness.ts)).
The session navigation handler resolves an exact line through the TUI's own
`revealLine` path before returning
([`src/ui/hooks/useTerminalReview.ts:905-963`](../../../oss/hunk/src/ui/hooks/useTerminalReview.ts)).

This lets one GoLand action result in a genuine upstream reviewer note, but the
evidence must say **PTY relay**, not **HTTP reviewer-note creation**. It also
requires a plugin-managed Hunk session; it cannot inject input into an arbitrary
TUI that the plugin does not own.

## Delete and edit

Reviewer-note deletion is supported natively:

```json
{"type":"notes/remove-user","noteId":"user:..."}
```

The intent, wire validation, and semantic removal are at
[`src/core/review/intents.ts:92-98`](../../../oss/hunk/src/core/review/intents.ts),
[`src/session/reviewProtocol.ts:410-413`](../../../oss/hunk/src/session/reviewProtocol.ts), and
[`src/core/review/intents.ts:447-467`](../../../oss/hunk/src/core/review/intents.ts).
The protected `/actions` route forwards it to the producer
([`src/session/broker/browserReviewServer.ts:391-449`](../../../oss/hunk/src/session/broker/browserReviewServer.ts)).

The unprotected loopback session API also supports deletion. `comment-rm`
forwards `remove_comment`, and the TUI deliberately interprets a `user:*` ID as
`notes/remove-user`
([`src/session/broker/brokerServer.ts:380-391`](../../../oss/hunk/src/session/broker/brokerServer.ts),
[`src/ui/hooks/useTerminalReview.ts:1206-1228`](../../../oss/hunk/src/ui/hooks/useTerminalReview.ts)).
Thus deletion can be tested even without capability capture.

Editing a saved reviewer note is not supported. There is no stored-note edit
intent in the current vocabulary
([`src/core/review/intents.ts:71-100`](../../../oss/hunk/src/core/review/intents.ts)).
The `editable:true` field distinguishes ownership/presentation; it does not imply
that an edit operation exists on this wire version.

## SSE is push invalidation, not the note feed

`GET /review-api/<sessionId>/events` returns SSE, starts with the complete current
publication, and uses a fresh publication as reconnect resynchronization
([`src/session/broker/browserReviewServer.ts:451-511`](../../../oss/hunk/src/session/broker/browserReviewServer.ts)).
It sends `publication` and `disconnect` only
([`src/session/reviewEventProtocol.ts:40-50`](../../../oss/hunk/src/session/reviewEventProtocol.ts)).
The stream can use one-frame or digest-verified begin/chunk/end framing
([`src/session/reviewEventProtocol.ts:143-181`](../../../oss/hunk/src/session/reviewEventProtocol.ts)).

The publication payload is only the generation/revision address and resource
catalog, not notes or the complete `ReviewState`
([`src/session/reviewHttpProtocol.ts:223-236`](../../../oss/hunk/src/session/reviewHttpProtocol.ts)).
Published resources are canonical files, patches, and source, not notes
([`src/app/review/publication.ts:48-85`](../../../oss/hunk/src/app/review/publication.ts)).
Consequently a client must not attempt to render notes from the SSE payload.

Every real review-store mutation increments `stateRevision` and notifies its
subscribers
([`src/core/review/store.ts:39-49`](../../../oss/hunk/src/core/review/store.ts)).
The TUI publishes that revision and its complete note summaries together in its
next daemon snapshot
([`src/ui/hooks/useHunkSessionBridge.ts:108-156`](../../../oss/hunk/src/ui/hooks/useHunkSessionBridge.ts)).
The daemon stores the snapshot before advancing the mirror and notifying SSE
watchers
([`src/session/broker/state.ts:226-237`](../../../oss/hunk/src/session/broker/state.ts),
[`src/session/broker/state.ts:405-419`](../../../oss/hunk/src/session/broker/state.ts)).

The smooth no-steady-state-polling algorithm is therefore:

1. Open SSE using `fetch`, not browser `EventSource`, because the capability must
   be sent in a header.
2. Parse and assemble each complete upstream event frame.
3. On the initial and every later `publication`, call `POST /session-api` with
   `action:"review"`, `includeNotes:true`.
4. Reconcile the returned `reviewNotes` by stable `noteId` and compare the
   snapshot's publication revision with the event.
5. On disconnect/reconnect, open a new stream; its initial publication is the
   complete current position.

This uses one read per real invalidation, not a timer-driven poll. The SSE stream
does not need to carry note bodies to eliminate polling.

Without capability capture, the fallback against a normally launched current
Hunk is polling `/session-api`; its session WebSocket is not an observer API.

## External agent responses

An independent upstream command can add a genuine live agent response:

```text
hunk session comment add --repo /absolute/repo \
  --file path/from/review.go --new-line 42 \
  --summary "External agent response" --author test-agent
```

The exact HTTP schema is shown above and defined at
[`src/session/protocol.ts:97-107`](../../../oss/hunk/src/session/protocol.ts).
The daemon forwards it to the TUI
([`src/session/broker/brokerServer.ts:323-341`](../../../oss/hunk/src/session/broker/brokerServer.ts));
the TUI persists it as a live note through `notes/add-live`
([`src/ui/hooks/useTerminalReview.ts:1120-1137`](../../../oss/hunk/src/ui/hooks/useTerminalReview.ts)).
The next snapshot exposes it as `source:"agent"`, `editable:false`
([`src/ui/hooks/useTerminalReview.ts:1382-1394`](../../../oss/hunk/src/ui/hooks/useTerminalReview.ts)).

Running the command in a separate process, waiting for an SSE revision, then
asserting that GoLand displays the note proves that the update is genuinely
external rather than a local fixture mutation.

## Implemented real-daemon E2E proof

The enabled task uses an isolated daemon port and condition-based waits
(Awaitility on the JVM side), never `Thread.sleep`:

1. Start the installed upstream `hunk daemon serve` and await capabilities.
2. Launch real GoLand on a generated Git-backed Go fixture.
3. Let the plugin start `hunk diff --watch` in its off-screen PTY and discover the
   native session by repository root and process ID.
4. From the GoLand comment action, navigate the stock TUI to the selected line and
   relay `c`, the body, and `Ctrl-S` over the owned PTY.
5. Fetch `/session-api` review with notes and assert:
   - a stable `user:*` note ID;
   - `source:"user"` and `editable:true`;
   - exact body, file, and expected old/new line range.
6. Add an agent annotation through native `comment-add`; assert the reviewer note
   remains and the `source:"agent"` card appears in the open GoLand thread.
7. Delete the `user:*` note through `/session-api comment-rm`; assert it disappears
   while the agent response remains.
8. Save the upstream version and all three review snapshots as JSON evidence.

"Persistence" here means persistence in the live Hunk session's in-memory
`ReviewStore`, reflected through later daemon snapshots and retained across
unrelated mutations/reloads. The store is in process memory
([`src/core/review/store.ts:23-51`](../../../oss/hunk/src/core/review/store.ts));
this upstream commit does not promise reviewer-note durability after the TUI
process exits.

## Acceptance language for the report

Accurate:

> The real-upstream E2E launches an unmodified upstream Hunk 0.20.0 binary. An
> IDE-owned PTY relays the GoLand reviewer comment through Hunk's native TUI
> composer because this upstream wire version cannot carry draft text. The test
> then verifies the real session snapshot, native deletion, and an external
> upstream agent comment reflected in the open GoLand thread.

Not accurate:

> GoLand creates a reviewer note through the upstream review HTTP API.

That statement must wait until upstream exposes a draft-body/create-user action,
or another supported semantic reviewer-note mutation.
