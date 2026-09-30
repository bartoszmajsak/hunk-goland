# First public release plan

The current development bundle is `0.5.0`. No Git tags or Git remote are configured in this checkout.
The first public release can be named `v0.1.0` without reverting functionality or rewriting the
existing commits. Change the plugin's embedded version, bundle filename, and installation docs
together in a dedicated release-preparation commit. People using the development `0.5.0` build
will need to install the public bundle explicitly; do not rely on automatic upgrade ordering.

## 1. Preserve clean feature commits

Commit the tested review navigator separately from the user guide and release planning. Keep the
existing feature, test, CI, and integration-history commits. Review the final public diff, remove
obsolete instructions, and record user-facing changes in a changelog.

## 2. Complete verification CI

`.github/workflows/verify.yml` already runs unit tests, builds the ZIP, runs Plugin Verifier,
drives real GoLand under Xvfb, and verifies an unmodified checksum-pinned Hunk 0.20.0. Actions are
SHA-pinned, permissions default to read-only, Gradle caching is enabled, and Dependabot covers
GitHub Actions. Evidence is retained even on failure.

Before release, run it on the actual GitHub repository, confirm its timeout and artifact sizes,
and measure cold and warm builds. Split fast checks from expensive IDE/native gates if useful.
Cache IDE downloads by exact GoLand version and runner platform; never cache test results or
reuse stale UX evidence. Add the Gradle distribution checksum, dependency verification, dependency
update coverage, and workflow linting. Keep all new third-party actions pinned to full SHAs.

Release verification must include native reviewer provenance, sequential submissions, external
agent synchronization, selective deletion, and the same-profile restart scenario on the release
commit. Mock-backed screenshots do not replace native daemon evidence.

## 3. Publish living documentation

Use the [user guide](user-guide.md) as the walkthrough, with selected screenshots produced by
successful fixture scenarios. Add a manifest tying each screenshot to its feature, source commit,
plugin version, IDE version, and test outcome. Fail documentation generation on missing expected
screenshots. Publish only an allowlisted static site: never deploy the entire diagnostics tree,
raw session JSON, logs, or personal-project screenshots.

Deploy GitHub Pages only after trusted default-branch verification succeeds. PRs retain preview
artifacts without deployment credentials. Keep installation, dependency setup, supported versions,
reviewing, agent handoff, navigation, recovery limits, and troubleshooting easy to find. Label the
site's tested version and preserve a release-specific copy alongside the latest documentation.

## 4. Automate GitHub releases

On a `v*` tag, require agreement between the tag and embedded plugin version. Run the complete
verification gates on that commit and publish the exact verified ZIP, SHA-256 checksum, release
notes, and build provenance. Give only the publishing job release-write permissions; tests retain
read-only permissions. Prefer a draft release for the first run so its assets can be inspected
before publication. GitHub bundle distribution is separate from JetBrains Marketplace publishing.

## 5. Resolve release prerequisites

- Create or select the public GitHub repository, configure its remote, and enable Pages.
- Choose a project license and include third-party notices where required.
- Confirm plugin name, ownership, and vendor/contact metadata: the current metadata identifies
  the upstream Hunk vendor and website, which needs review for an independent plugin release.
- Document tested operating systems and exact GoLand/Hunk versions; avoid promising compatibility
  with every newer Hunk build. Verify any platform advertised for the release.
- Add a changelog, issue-reporting guidance, and a security contact.
- Protect the release/default branches and tags as appropriate, and make verification required.
- Decide whether Marketplace distribution is a later step; if adopted, add its credentials and
  signing/publishing setup independently of the GitHub release.

Official workflow references:
[GitHub Pages deployment](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages),
[secure GitHub Actions](https://docs.github.com/en/actions/reference/security/secure-use), and
[JetBrains publishing](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html).
