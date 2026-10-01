# Hunk GoLand contributor instructions

These instructions apply to the entire `hunk-goland` project. If the local, ignored `JOURNEY.md`
exists, read it before changing session integration, review provenance, target resolution, PTY
relay behavior, or E2E coverage. Fresh checkouts use these instructions and the tracked integration
research under `docs/`; the local journal is not required to build or test.

## Integration invariants

- Build and test against unmodified upstream `modem-dev/hunk`. Never create, use, or depend on a
  `hunk-goland-hunk` fork/clone, and never patch an upstream checkout to make plugin tests pass.
- Treat a GoLand submission as a reviewer note. It must be observed as `source: "user"`; never use
  `/session-api` `comment-add` for it because that creates `source: "agent"`.
- Do not invent Hunk actions, envelopes, actor kinds, capabilities, or persistence semantics. Check
  the installed/upstream source and document the exact supported contract.
- Hunk 0.23.0 requires signed caller authentication even for capabilities and session discovery.
  Use the shared `HunkSessionTransport`: owner-private local credentials, verified daemon hello,
  Ed25519 request signatures, and verified bounded responses. No anonymous fallback or manual token.
  The running daemon must match app revision 16. Tests must isolate the credential runtime directory
  and preserve Wayland connectivity with an absolute `WAYLAND_DISPLAY` socket path.
- Current stock Hunk reviewer creation uses the native TUI composer through a plugin-owned off-screen
  PTY. Keep that seam narrow: use `/session-api` for discovery, navigation, reading, exact
  verification, and deletion.
- Report submission success only after a daemon snapshot contains a previously unseen reviewer note
  matching the exact body, file, side, and line. A rendered terminal confirmation is not persistence
  evidence. Never write comment bodies, matching text fragments, terminal transcripts, request or
  response payloads, or capability tokens to logs or diagnostics.
- Do not type into or claim to join an externally owned TUI. Read/delete may target an explicitly
  selected external session; reviewer writes require the exact IDE-managed session.

## Upstream behavior to preserve

- The daemon is a broker, not a review owner. A live `hunk diff ... --watch` TUI owns the in-memory
  review state. Notes are not durable after that process exits.
- Local restart recovery is not native Hunk persistence. Replay reviewer comments only through the
  owned native composer, accept their new Hunk IDs/timestamps, and keep missing agent responses as
  explicitly labeled read-only local history. Never turn agent history into `source: "user"`.
- Guard recovery with the exact repository/comparison fingerprint, including resolved Git commits,
  Hunk hunk structure, and working-tree file contents. If the fingerprint is unavailable or stale,
  do not replay. Keep the cache non-roaming, bounded, and outside the repository.
- `hunk session list`'s `comments:` field is `liveCommentCount` for agent annotations, not the
  reviewer-note count. Inspect `reviewNotes` with `hunk session review <id> --include-notes` and
  classify individual entries by `source`; `reviewNoteCount` includes agent entries when present.
- Saved reviewer-note deletion is supported by the session CLI/API; editing is not exposed through
  that integration. Hunk 0.23's own review UI has additional edit/reply actions, but `editable: true`
  alone does not authorize a new plugin API or infer access to its protected review surface.
- Reviewer anchors may cover an inclusive source range inside one published Hunk hunk. Preserve both
  endpoints through editor/unified-diff mapping, exact daemon verification, and restart recovery.
  Gutter actions remain single-line targets, even when the editor has a selection elsewhere.
- Diff composition is current/right (`new`) side only. Support changed and contextual lines inside a
  hunk, split and unified viewers, and one-sided added-file diffs; reject out-of-hunk targets.
- Match the actual JetBrains comparison, not only the repository root: working tree uses
  `hunk diff --watch`, base/current comparison uses `hunk diff <base> --watch`, and concrete
  revisions use `hunk diff <before>..<after> --watch`.
- A normal editor must inherit the active IDE-managed current-version review for its repository.
  It must not replace a branch comparison with `hunk diff --watch` merely because normal editors
  lack diff-local VCS metadata. Fixed revision ranges remain diff-only.
- The session WebSocket is a producer channel. Protected review SSE needs a clear capability that a
  stock external TUI does not delegate to the plugin. Until upstream supplies delegation, bounded
  two-second `/session-api` polling is the supported two-way refresh path.

## UI and UX invariants

- Use ordinary review language such as **Add Hunk Comment…**. Do not reintroduce confusing “human,”
  “Reveal,” or **Show in Hunk** controls.
- Keep threads line-attached. Preserve chronological oldest-to-newest card order, natural-height
  compact/collapsible cards, safe Markdown, independently expandable conversation and composer
  areas, `Ctrl+Enter`, inline delete confirmation, and stable user-controlled scrolling across
  refreshes.
- Treat upstream reviewer `author: "user"` as a presentation placeholder: show the effective
  repository `git user.name` when accessible, retain explicit reviewer/agent names, and never change
  the stored author or `source: "user"` provenance to achieve the display label.
- Preserve distinct reviewer/agent glyphs, native VCS tree file badges, status counts, and
  previous/next thread navigation. Keep the persistent **Hunk Review** tool window, status widget,
  file badges, gutter summaries, and navigation actions backed by the same immutable
  `HunkReviewIndex`; do not create a second comment cache or ordering model. Display shortcuts from
  the active JetBrains keymap rather than hard-coding labels.
- Rebuild navigator trees from stable file/thread nodes and expand file nodes by `TreePath`, not
  mutable row numbers. Row indices change as parents expand, and duplicate line labels are valid
  across files.
- Overwriting a built ZIP does not hot-reload an installed GoLand plugin. For manual verification,
  reinstall the ZIP and accept the IDE reload/restart prompt.

## Verification gates

- Normal UI tests must use the generated Git-backed Go fixture. Do not depend on a personal checkout,
  `dynamic-crd-watch`, KServe, or another repository being present or on the test classpath.
- Use JetBrains Driver against a real GoLand process. Use `native`, `weston`, or `xvfb` through
  `scripts/run-ui-tests`; keep Xvfb working for GitHub Actions and Weston for headless Wayland.
- Serialize Driver suites: isolated IDE profiles still share Starter's default remote port 10500.
  Preserve Gradle task ordering and sequential CI calls to prevent port collisions.
- Outside the IDE, use `PtyProcessBuilder` for a real test TUI. `PtyCommandLine` needs an IntelliJ
  Application and can silently fall back to pipes in the host test JVM; Hunk 0.23 rejects that for
  `--watch`. For cleanup obtain a `ProcessHandle` from `pid()`, not pty4j's unsupported `toHandle()`.
- Do not use `Thread.sleep` or fixed-delay synchronization. Use Awaitility/condition waits, AssertJ,
  and domain-specific assertions with actionable failure messages.
- `test` and `integrationTest` cover logic and visual interaction. Any claim about native Hunk
  persistence, provenance, external synchronization, or deletion additionally requires the enabled
  `realHunkE2e` against an installed, unmodified upstream binary.
- When changing the PTY relay, retain release-gating cases for two sequential comments on one line, a
  long wrapped multiline Markdown body, another comment after navigation, an external agent update,
  exact daemon verification, live `hunk session review --include-notes --json` inspection, and
  selective deletion.
- Range gates must include a selection ending at the next line's exclusive start, native range
  creation amid existing note cards, exact CLI range read-back, and same-profile range recovery.
- Keep the same-profile real-GoLand restart gate: the original managed TUI must exit, a fresh session
  must receive recovered reviewer comments as `source: "user"`, and agent history must remain local.
- Keep `realProjectDiagnostic` as an optional but gating reproduction for the KServe **Compare with
  Branch** regression. It may use a user-supplied checkout, but the normal suite must not.
- Preserve the human report and screenshots under `build/reports/ux/` and the native daemon evidence
  in `build/reports/ux/real-daemon-e2e.json`. Screenshots alone do not prove persistence.
- Driver suites must enable `-Dhunk.diagnostics=true`; the real-project diagnostic must assert that
  request, session-scan, and snapshot diagnostic events reached its copied `idea.log`.
- Before handing off a manual test build, run proportionate gates, bump the plugin version, report the
  ZIP path and checksum, and make clear that the installed plugin must be reloaded.

## Repository hygiene

- Pin every third-party GitHub Action in `uses:` to its full commit SHA and retain the intended release
  tag in a trailing comment. Do not introduce floating action tags.
- Keep `JOURNEY.md` local and ignored. Do not add it to commits, public docs, or release artifacts.
- Keep Plugin Verifier and its GoLand matrix explicitly versioned. Review dependency checksum
  updates; CI must enforce the tracked verification metadata, not regenerate it.
- Publish only the allowlisted fixture screenshots and guide generated by `scripts/build-site.py`.
  Require successful, fresh test results; never deploy raw UX reports, logs, or session JSON to Pages.
- `scripts/run-ui-tests` automatically refreshes `build/site/` after a full fixture run. Preserve its
  fresh-run gate, staged generation, and previous-preview retention. Use `HUNK_BUILD_GUIDE=0` for
  focused test-only runs; direct Gradle invocations do not generate the guide.
- Preserve user changes and avoid broad or destructive cleanup. Generated Driver/Allure artifacts may
  contain evidence from earlier failures; do not cite them as current results without checking the
  latest task reports.
- Do not hide GUI-test flakes behind a green rerun. Retain distinct JetBrains/Driver failure
  conditions in the local `JOURNEY.md` when available, and distinguish them from Hunk transport
  or persistence failures using the structured logs.
