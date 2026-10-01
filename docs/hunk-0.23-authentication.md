# Hunk 0.23 authenticated session integration

Audited against unmodified upstream tag `v0.23.0`, commit
`7991d3034af1d2116e9c77db31265e409a73bd9f`, on 2026-10-01. This supersedes the unsigned
transport assumptions in the [earlier API audit](upstream-no-change-e2e.md).

## Local identity, without manual tokens

Stock Hunk creates `hunk-mcp/security-v1/{caller,daemon,producer}.json` beneath
`XDG_RUNTIME_DIR`, or `~/.hunk` on Unix when that environment variable is absent. Credentials
belong to the OS user, not one repository or daemon port. The security directory is private
(`0700`); credential files are private regular files (`0600`). Symlinks, unsafe permissions,
wrong ownership, oversized files, and malformed credentials are rejected. The plugin reads
the caller's PKCS8 Ed25519 private key and the daemon's SPKI public key. It does not write
credentials or use the producer identity.

This is Hunk's existing bootstrap caller grant, also used by its session CLI. It is not an
invented IDE actor or delegated browser-review capability. GoLand and the daemon must share
the same OS user and runtime directory. Replacing an executable does not restart an existing
daemon; both must use the tested release.

Source: upstream [credential store](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/broker/credentials.ts)
and [stock CLI client](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/agent/cliClient.ts).

## Signed transport

The project's `HunkSessionTransport` implements upstream's caller handshake and control client:

1. POST a caller proposal to `/session-auth/challenge` for broker revision 1, app ID `dev.hunk`,
   app revision 16, and no negotiated features.
2. Verify the daemon's generation-bound Ed25519 challenge before signing its canonical transcript.
3. POST the proof to `/session-auth/proof`. Verify the signed acknowledgement, expected grant
   principal/scopes, selected revisions, transcript hash, and initial sequence `1`.
4. Reuse that short-lived caller session. Sign each exact request body digest, method, target,
   request ID, and monotonic unsigned-64-bit sequence using the four `x-session-broker-*` headers.
5. Bound responses to 8 MiB and reject invalid UTF-8/JSON. Verify the response signature,
   canonical body digest, status and request bindings before exposing the unwrapped body.
   Targeted actions additionally bind app revision 16 and the empty feature selection.

Canonical transcripts and response bodies use RFC 8785, including ECMAScript number formatting
and UTF-16 property ordering. The plugin uses JCA Ed25519 and the bundled, checksum-pinned
`io.github.erdtman:java-json-canonicalization:1.1` implementation. No Bun/Node runtime or CLI
subprocess is added to refreshes. Concurrent control calls share one caller per origin; their
short requests are serialized so sequence ordering stays within the broker replay window.

One unsigned authentication rejection (HTTP 401) permits a fresh handshake and one retry, matching
stock Hunk's client. Timeouts, disconnects, unverified successes, and malformed responses never
automatically repeat a mutation. There is no anonymous compatibility fallback. Authentication
errors omit payloads, keys, signatures, and their potentially sensitive exception causes.
Identity/protocol failures are distinct from an absent daemon and do not trigger an automatic
review launch. An unbootstrapped credential store can still start the owned native review so
stock Hunk creates its own credentials.

Source: upstream [client authentication](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/session-broker/src/clientAuthentication.ts),
[transcripts](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/session-broker-core/src/auth.ts),
and [application contract](https://github.com/modem-dev/hunk/blob/v0.23.0/packages/hunk/src/session/broker/appContract.ts).

## Review semantics stay explicit

Reviewer creation still goes through the plugin-owned native TUI composer and is reported as
successful only after exact daemon read-back as `source: "user"`. `/session-api` provides discovery,
navigation, reading, verification, and native deletion. Its `comment-add` creates agent annotations
and must not replace the reviewer composer. The protected review surface is a separate capability
model; automatic caller authentication does not establish browser-review token delegation or
interactive TUI attach/join. Two-second bounded refresh remains in place.

In 0.23, failures resolving a targeted selector are deliberately returned as the signed generic
`protocol-validation-failed` error. On a repository-selector failure, the plugin checks the
authorized session list to distinguish a missing or ambiguous review before deciding whether to
start its own process. It does not treat every protocol failure as permission to launch a review.

Hunk now rejects `--watch` with non-terminal stdout. The plugin already owns a genuine IDE PTY;
the external E2E TUI must also use a real PTY rather than a host-side `PtyCommandLine` pipe fallback.
Terminal output is drained without storing a transcript.

## Verification

Protocol tests use an independent signed-server fixture and isolated owner-private credentials.
They cover hello verification and reuse, ordered concurrent sequences, renegotiation, fail-closed
response/binding/contract checks, invalid UTF-8, response bounds, ambiguous-write timeouts,
credential permissions/symlinks, and canonicalization vectors.

The native GoLand gate additionally exercises stock 0.23 reviewer provenance, sequential wrapped
Markdown, navigation, external agent updates, exact live CLI inspection, selective deletion, and
same-profile restart recovery. Fresh JUnit success is required in addition to the native JSON
evidence: a scenario can finish its semantic assertions and still fail during cleanup. CI pins the
0.23.0 Linux x64 release archive to SHA-256
`79ab6406cbb2b800e73c6fbc2546afb97ed4fbc3b50aad072eb9ad12151581e6`.
