# Hunk live-session attach/join research

Date: 2026-09-02

## Conclusion

Upstream Hunk does not currently expose a `hunk session attach` or `join` command, and no issue or pull request was found whose scope is specifically attaching a second TUI to an existing live review.

The recommended implementation is a second **semantic review client**, not a PTY/screen clone. The original process remains the review producer while attached clients maintain their own viewport and send authenticated review actions. This aligns with Hunk's browser-review rebuild.

## Closest upstream work

- [#707: synchronized browser reviews](https://github.com/modem-dev/hunk/pull/707): a large prototype that included shared review state and `hunk session open`; closed in favor of rebuilding it in staged changes.
- [#729: review wire protocol and daemon mirror](https://github.com/modem-dev/hunk/pull/729): merged semantic action vocabulary, actor identity, broker mirror, resources, and review-action application.
- [#732: loopback HTTP/SSE surface](https://github.com/modem-dev/hunk/pull/732): merged authenticated publication/resources/event/action endpoints, but no client or CLI entry point.
- [#746: read-only browser mirror](https://github.com/modem-dev/hunk/pull/746): open first client phase. Interactivity, note editing, and selection synchronization are explicitly deferred to a follow-up.
- [#90: TTY and tmux pane in session list](https://github.com/modem-dev/hunk/pull/90): merged terminal metadata, useful for a tmux-specific workaround but not generic attachment.
- [#113: persist comments and continue after quit](https://github.com/modem-dev/hunk/issues/113) and [#473: note sidecar persistence](https://github.com/modem-dev/hunk/pull/473): adjacent persistence/resume work, not live multi-client attachment.
- [#770: replace browser WebSocket assertion](https://github.com/modem-dev/hunk/issues/770): adjacent browser transport cleanup, not session attachment.

The installed `hunk session --help` exposes `list`, `get`, `context`, `review`, `navigate`, `reload`, `comment`, and `highlight`; it has no attach/join/open operation.

## Effort estimates

These are engineering estimates, not upstream commitments.

| Scope | Estimate | Trade-offs |
| --- | ---: | --- |
| IDE-managed tmux workaround | 2–4 days | Fast on Unix, but requires tmux, shares one screen/input stream, and cannot attach to arbitrary non-tmux sessions. |
| Raw PTY relay | 2–4 weeks Unix-only; 4–6 weeks upstream-grade | Must solve resize arbitration, simultaneous input, terminal capabilities, disconnects, alternate-screen behavior, and Windows ConPTY. |
| Read-only semantic TUI client | 5–8 days after the reusable client pieces in #746 land; about 1.5–2 weeks from current main | Independent viewport and robust transport, but no comments/actions yet. |
| Interactive semantic TUI parity | 3–6 weeks | Includes navigation, selection/follow behavior, note composition/edit/delete, auth/capability flow, reload/reconnect semantics, lifecycle, conformance tests, and two-terminal E2E. |
| Narrow GoLand participation in a TUI-owned session | roughly 4–7 upstream days plus 2–3 plugin days | Solves IDE review actions but does not itself provide a second TUI attach command. |

## Proposed upstream issue

Add `hunk session attach <session-id>` as a semantic client:

- Keep the existing review process as producer/owner.
- Give each attached TUI an independent viewport and selection by default.
- Offer an explicit `--follow` mode for following the owner terminal.
- Authenticate against an exact session capability; do not infer a session by repository alone.
- Carry note create/edit/delete operations and actor identity through the review protocol.
- Make attached-client quit local; producer termination should disconnect attached clients cleanly.
- Define reload/generation and reconnect behavior.
- Mark producer-only operations such as host editor integration clearly unavailable or forward them explicitly.
- Gate it with protocol conformance tests and a real two-terminal PTY E2E test.

## Recommendation

Do not build generic attach by mirroring terminal bytes. Continue the staged browser architecture through interactive review actions, then make both browser and TUI thin clients of the same protocol. This gives GoLand a supported integration seam and avoids terminal-sharing edge cases.
