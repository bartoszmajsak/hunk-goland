# Hunk GoLand contributor instructions

These instructions apply to the entire `hunk-goland` project. Read [JOURNEY.md](JOURNEY.md) before
changing session integration, review provenance, target resolution, PTY relay behavior, or E2E
coverage.

## Integration invariants

- Build and test against unmodified upstream `modem-dev/hunk`. Never create, use, or depend on a
  `hunk-goland-hunk` fork/clone, and never patch an upstream checkout to make plugin tests pass.
- Treat a GoLand submission as a reviewer note. It must be observed as `source: "user"`; never use
  `/session-api` `comment-add` for it because that creates `source: "agent"`.
- Do not invent Hunk actions, envelopes, actor kinds, capabilities, or persistence semantics. Check
  the installed/upstream source and document the exact supported contract.
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
- `hunk session list`'s `comments:` field is `liveCommentCount` for agent annotations, not the
  reviewer-note count. Inspect `reviewNotes` with `hunk session review <id> --include-notes` and
  classify individual entries by `source`; `reviewNoteCount` includes agent entries when present.
- Saved reviewer-note deletion is supported; editing is not. `editable: true` does not imply an edit
  action.
- Reviewer anchors are single-line targets inside published Hunk hunks. Multiline Markdown bodies are
  valid, but a multi-line code selection resolves to one line.
- Diff composition is current/right (`new`) side only. Support changed and contextual lines inside a
  hunk, split and unified viewers, and one-sided added-file diffs; reject out-of-hunk targets.
- Match the actual JetBrains comparison, not only the repository root: working tree uses
  `hunk diff --watch`, base/current comparison uses `hunk diff <base> --watch`, and concrete
  revisions use `hunk diff <before>..<after> --watch`.
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
  previous/next thread navigation. Display shortcuts from the active JetBrains keymap rather than
  hard-coding labels.
- Overwriting a built ZIP does not hot-reload an installed GoLand plugin. For manual verification,
  reinstall the ZIP and accept the IDE reload/restart prompt.

## Verification gates

- Normal UI tests must use the generated Git-backed Go fixture. Do not depend on a personal checkout,
  `dynamic-crd-watch`, KServe, or another repository being present or on the test classpath.
- Use JetBrains Driver against a real GoLand process. Use `native`, `weston`, or `xvfb` through
  `scripts/run-ui-tests`; keep Xvfb working for GitHub Actions and Weston for headless Wayland.
- Do not use `Thread.sleep` or fixed-delay synchronization. Use Awaitility/condition waits, AssertJ,
  and domain-specific assertions with actionable failure messages.
- `test` and `integrationTest` cover logic and visual interaction. Any claim about native Hunk
  persistence, provenance, external synchronization, or deletion additionally requires the enabled
  `realHunkE2e` against an installed, unmodified upstream binary.
- When changing the PTY relay, retain release-gating cases for two sequential comments on one line, a
  long wrapped multiline Markdown body, another comment after navigation, an external agent update,
  exact daemon verification, live `hunk session review --include-notes --json` inspection, and
  selective deletion.
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
- Preserve user changes and avoid broad or destructive cleanup. Generated Driver/Allure artifacts may
  contain evidence from earlier failures; do not cite them as current results without checking the
  latest task reports.
- Do not hide GUI-test flakes behind a green rerun. Retain the original failure condition in
  `JOURNEY.md` when it exposes a distinct JetBrains/Driver race, and distinguish it from Hunk
  transport or persistence failures using the structured logs.
