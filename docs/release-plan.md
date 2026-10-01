# First public release plan

The current bundle is `0.1.0`, the intended first public release version. Earlier version numbers
were used only for local testing; no plugin release has been published. Renumbering preserves all
current functionality and the existing commits. The ignored local journal keeps historical test
version labels. No Git tags or Git remote are configured in this checkout yet.

## 1. Preserve clean feature commits

The tested review navigator and user guide are committed separately. Keep the
existing feature, test, and CI commits. `JOURNEY.md` is now local and ignored; fresh checkouts use
tracked contributor instructions and integration research. Initial release notes live under
`docs/releases/`. Review the final public diff before publishing.

## 2. Complete verification CI

`.github/workflows/verify.yml` calls a shared pipeline that runs unit tests, builds the ZIP, runs Plugin Verifier,
drives real GoLand under Xvfb, and verifies an unmodified checksum-pinned Hunk 0.23.0. Actions are
SHA-pinned, permissions default to read-only, Gradle and exact-version IDE download caching are
enabled, and Dependabot covers GitHub Actions and Gradle. Dependency artifacts/metadata, the Gradle
distribution, and upstream Hunk downloads have checksum verification. Workflow linting and
publication-guard tests are included. Evidence is retained even on failure.

Before release, run it on the actual GitHub repository, confirm its timeout and artifact sizes,
and measure cold and warm builds. Split fast checks from expensive IDE/native gates if useful.
Test execution cannot reuse cached output, and stale evidence is rejected by publication guards.
Keep all new third-party actions pinned to full SHAs.

Release verification must include native reviewer provenance, sequential submissions, external
agent synchronization, selective deletion, and the same-profile restart scenario on the release
commit. Mock-backed screenshots do not replace native daemon evidence.

## 3. Publish living documentation

The [user guide](user-guide.md) is rendered with selected successful fixture screenshots. The
builder emits a manifest with source commit, plugin/IDE versions, test outcome, captions, and hashes.
Missing/stale screenshots or failed tests block generation. Only this allowlisted site is uploaded;
raw session JSON, logs, and personal-project screenshots are excluded.

The Pages job deploys only after trusted default-branch verification succeeds. PRs retain preview
artifacts without deployment credentials. Each release archives its captured guide; the latest
site displays the version and source commit it tested.

## 4. Automate GitHub releases

The release workflow requires agreement between a `v*` tag and the embedded plugin version. It
runs complete verification, rechecks asset hashes and source identity, and creates a draft release
with the exact verified ZIP, documentation archive, checksums, notes, and build metadata. Only the
publishing job receives release-write permissions. Published releases cannot be overwritten by a
rerun. GitHub bundle distribution is separate from JetBrains Marketplace publishing.

## 5. Resolve release prerequisites

- Create or select the public GitHub repository, configure its remote, and enable Pages.
- Choose a project license and include third-party notices where required.
- Review plugin ownership and contact metadata. Vendor metadata now identifies Bartosz Majsak
  rather than claiming the upstream Hunk vendor.
- Document tested operating systems and exact GoLand/Hunk versions; avoid promising compatibility
  with every newer Hunk build. Verify any platform advertised for the release.
- Add issue-reporting guidance and a security contact once the public repository is selected.
- Protect the release/default branches and tags as appropriate, and make verification required.
- Decide whether Marketplace distribution is a later step; if adopted, add its credentials and
  signing/publishing setup independently of the GitHub release.

Official workflow references:
[GitHub Pages deployment](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages),
[secure GitHub Actions](https://docs.github.com/en/actions/reference/security/secure-use), and
[JetBrains publishing](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html).
