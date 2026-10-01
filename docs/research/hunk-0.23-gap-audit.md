# Hunk 0.23 integration gap audit

Audited on 2026-10-01 against the unmodified upstream `v0.23.0` tag, commit
`7991d3034af1d2116e9c77db31265e409a73bd9f`. The GitHub release API identifies it as
the latest non-prerelease release, published on 2026-09-30. A read-only inspection of a clean
tag checkout supplied the source evidence below; no upstream checkout was changed.
[Release](https://github.com/modem-dev/hunk/releases/tag/v0.23.0),
[release metadata](https://api.github.com/repos/modem-dev/hunk/releases/latest).

## Conclusion

The plugin and its real-daemon gate do not require a modified Hunk build. The owned off-screen
PTY remains a bridge over the stock TUI, not a Hunk patch, injected preload, or new session API.
The separately installed binary also supplies the test daemon and independent TUI. CI downloads
the official release archive with a pinned checksum.
See [the native gate](../../src/integrationTest/kotlin/dev/hunk/goland/HunkRealDaemonE2eTest.kt),
[the launcher](../../src/main/java/dev/hunk/goland/service/HunkReviewLauncher.java), and
[the CI installer](../../scripts/install-ci-hunk).

Some first-iteration limitations are no longer limitations of upstream Hunk itself. Upstream
now has reviewer editing, replies, multiline anchors, and a review action that supplies draft
text. Those capabilities belong to a protected review surface that the current plugin does not
have a supported way to authorize. They must not be presented as already available in GoLand,
or as proof that the current PTY bridge can be removed.
[Review intents](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/core/review/intents.ts#L110-L130),
[wire actions](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/reviewProtocol.ts#L145-L193),
[capability authorization](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/broker/browserReviewServer.ts#L315-L324).

## Released capabilities versus plugin behavior

| Concern | Stock Hunk 0.23 | Current plugin and remaining gap |
| --- | --- | --- |
| Local authentication | Session discovery and control require signed caller authentication. The application contract is revision 16, and stock owner-private credentials provide the caller identity. | Automatic signed transport is implemented; no manual token or anonymous fallback. This is not authorization for the separate browser-review capability. [Application contract](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/broker/appContract.ts), [credential store](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/broker/credentials.ts). |
| Reviewer creation | The TUI creates reviewer notes. Protected review actions can start a draft, replace its body, and commit a reviewer note. The ordinary session API still has no reviewer-create action; its comment-add/apply operations create agent annotations. | The plugin uses its own genuine TUI composer and reports success only after exact daemon read-back with reviewer provenance. Text submission is not supported through the plugin's ordinary session API. [Session request schema](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/protocolSchemas.ts#L191-L286), [intent vocabulary](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/core/review/intents.ts#L110-L130). |
| Editing and deletion | Saved reviewer editing exists in the TUI and protected action vocabulary. The ordinary session API supports removal, not saved reviewer editing. | GoLand offers native deletion, but no edit control. The limitation is the exposed integration, not an absent upstream edit intent. [Edit planning](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/core/review/intents.ts#L534-L568), [session schema](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/protocolSchemas.ts#L240-L259). |
| Replies | Reviewer replies are semantic notes with parent relationships. Agent CLI comments can also reply to a note. | Native parent IDs are retained for display and recovery; cards label their parent and explain Hunk's parent-deletion restriction. GoLand's add-comment control still creates a root reviewer note, not a reply to a selected card. Missing reviewer replies remain read-only local history rather than being replayed as unrelated roots. [Reply planning](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/core/review/intents.ts#L570-L600), [deletion constraint](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/core/review/intents.ts#L764-L792), [CLI reference](https://github.com/modem-dev/hunk/blob/v0.23.0/website/src/content/docs/docs/reference/cli.md#L453-L484). |
| Multiline anchors | Reviewer targets can contain inclusive source ranges, with coverage validation. This shipped in 0.22. | Implemented through the owned native visual selection for ranges within one published hunk, with exact endpoint verification and restart replay. Gutter actions remain single-line. Multiline Markdown bodies remain supported. [0.22 release notes](https://github.com/modem-dev/hunk/blob/v0.23.0/website/src/content/docs/changelog/0.22.md#L58-L60), [range validation](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/reviewProtocol.ts#L327-L364). |
| Outside the patch | The protected reviewer flow supports a single restored source line from an expanded gap, with explicit source proof. This does not mean arbitrary unrelated files or unverified targets are accepted. | GoLand requires a line or selected range entirely inside one published hunk. Expanded-gap reviewer targets are not implemented. [Expanded-line proof](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/reviewProtocol.ts#L134-L143), [wire validation](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/reviewProtocol.ts#L437-L454). |
| Push refresh | Protected review SSE exists. The session WebSocket connects review producers to the daemon. The clear review capability stays in the owner process; only its digest is registered. | The plugin uses bounded two-second session polling. Signed caller authentication does not supply a clear browser capability, so neither SSE nor protected note actions are automatically available. [Capability lifetime](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/app/review/capability.ts), [HTTP routing](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/broker/brokerServer.ts#L736-L757). |
| Durable sessions | Review state is held in an in-memory store. The daemon mirrors a live producer, rather than persisting its review. Persistence work remains open and unmerged. | Exact-fingerprint local recovery is plugin cache assistance, not native Hunk persistence. Reviewer replay creates new native IDs/timestamps; missing agent responses remain local history. [Review store](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/core/review/store.ts), [persistence issue](https://github.com/modem-dev/hunk/issues/113), [sidecar proposal](https://github.com/modem-dev/hunk/pull/473). |
| Changed review input | Existing notes remain tracked by the store across document reconciliation; stale anchors can remain renderable, while orphaned anchors are not rendered. The exported session summary follows notes associated with the current files. | A live session and its visible/exported note set are not equivalent. Restart recovery refuses stale fingerprints and does not promise to move comments onto agent-modified code. [Reconciliation](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/core/review/reducer.ts#L120-L156), [visibility policy](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/core/review/state.ts#L24-L36), [snapshot projection](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/ui/hooks/useTerminalReview.ts#L1649-L1690). |
| TUI attach/join | The released CLI has no command to open a second TUI on an existing review. A new TUI registers a separate session, and agent commands can target an existing session by ID. The browser mirror proposal remains open. | GoLand does not claim to join an external TUI for reviewer writes. It owns a separate review process. [Session command catalog](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/agent/surface.ts), [browser mirror proposal](https://github.com/modem-dev/hunk/pull/746). |
| Headless review ownership | The daemon and agent commands are headless, but a watched review requires interactive terminal output. Non-interactive diff output is a snapshot, not a durable session owner. | The off-screen PTY avoids a visible Terminal tool window but still runs a real TUI. It should not be described as an upstream headless-session API. [Startup checks](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/app/startup.ts#L571-L588). |

The absence of a normal capability handoff is verified at this tag: `reviewProcessUrl` has no
production caller, and session registration publishes only `reviewCapabilityDigest`. The
ordinary session schema contains no review-action/delegation operation. This is a conclusion
from the released source, not a claim that the protected actions do not exist.
[Capability generator](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/app/review/capability.ts#L43-L50),
[registration](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/app/session/registration.ts#L95-L106),
[session schema](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/protocolSchemas.ts#L191-L286).

## What the tests prove

`integrationTest` drives real GoLand with generated Git-backed files but uses a signed fake daemon
and fixture TUI. It exercises UI behavior, line mapping, ordering, and transport expectations.
It is not proof of stock Hunk storage. `realHunkE2e` separately starts the installed upstream
binary, isolated signed daemon, independent genuine PTY TUI, and GoLand-owned TUI. It checks
live snapshots and the native CLI for exact note bodies, targets, provenance, external agent
updates, deletion, and restart replay. Both tasks are separately configured and run in CI.
[UI fixture](../../src/integrationTest/kotlin/dev/hunk/goland/HunkHumanNoteUiTest.kt),
[real upstream gate](../../src/integrationTest/kotlin/dev/hunk/goland/HunkRealDaemonE2eTest.kt),
[task definitions](../../build.gradle.kts), [verification workflow](../../.github/workflows/verification.yml).

Here, persistence means that a live upstream session contains the note. It does not mean that
Hunk saves a durable database after its owner exits. The restart scenario verifies the plugin's
separate local recovery behavior. Existing screenshots or JSON evidence are not a current test
result without matching fresh successful JUnit reports.

## Documentation corrections completed

1. **How it works** now explains the tested 0.23 session-API limitation.
   Protected draft-body actions now exist; the ordinary session endpoint still does not expose them.
2. Editing is described as a current plugin integration limitation, not an absent upstream intent.
3. The implemented selected-range comments are documented, including same-hunk validation and exact
   native range verification. The earlier claim that upstream cannot represent multiline anchors
   was removed.
4. The normal UI suite's fake transport is explicit; stock-Hunk provenance and live-storage claims
   are reserved for the native gate.
5. The 0.23 daemon upgrade procedure is documented: inspect `hunk daemon status`; an old daemon is not
   upgraded merely by replacing the binary. `hunk daemon restart` requires a deliberate decision
   because it disconnects incompatible windows and their in-window notes can be lost.
   [Released daemon guidance](https://github.com/modem-dev/hunk/blob/v0.23.0/website/src/content/docs/docs/agents/live-session-control.md#L50-L54).
6. Personal absolute source/preload paths and sibling-checkout links in the historical audit were
   replaced by portable placeholders and GitHub permalinks pinned to the original commit. The
   historical scope is explicit and the preload experiment is marked unshipped.

The older upstream audit remains useful only as historical evidence for its explicitly stated
commit. Its source-preload capability experiment is not the shipped runtime or native E2E path.
Current claims should point to this audit and the
[0.23 authentication contract](../hunk-0.23-authentication.md), not infer present limitations from
an earlier source snapshot.
