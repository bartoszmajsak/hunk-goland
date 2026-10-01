# Release operations

## Repository setup

Before the first public release:

1. Configure the intended GitHub repository as the Git remote and push the commits to `main`.
2. Choose a project license and confirm the plugin vendor and support/contact details.
3. In **Settings | Pages**, select **GitHub Actions** as the publishing source. Configure the
   `github-pages` environment to deploy only from `main`.
4. Configure the `release` environment and protect version tags/default-branch changes as needed.
   Require the verification workflow before merging. No personal access token is needed for the
   release job; it uses the repository's scoped workflow token.
5. Run **Verify** and inspect the fixture screenshots, native daemon evidence, restart results,
   Plugin Verifier report, and installable bundle. GitHub-hosted CI still needs its first run.

All third-party workflow actions are pinned to full commit SHAs. Upstream Hunk, actionlint, and
the Gradle wrapper distribution have explicit checksums. Gradle and exact-version IDE downloads
are cached; test reports and IDE profiles are not. Only trusted successful default-branch runs
save the IDE download cache. Dependency updates are proposed by Dependabot. Gradle verifies
dependency artifacts and metadata against tracked SHA-256 checksums. GoLand verification versions
and Plugin Verifier are explicit, so they cannot silently advance to the latest release.

After intentionally changing dependencies or the compatibility matrix, regenerate checksums locally
with `--write-verification-metadata sha256` while running the affected build/test tasks. Review the
new artifacts and checksums against their upstream repositories before committing them. CI uses
strict verification and never generates or automatically accepts new checksums. The initial checksum
set was bootstrapped from the resolved upstream repository artifacts; it is not signature verification.

## Living documentation

Successful verification generates `build/site/` from `docs/user-guide.md` and the screenshot
allowlist in `docs/screenshots.json`. The builder requires a successful, fresh Driver report and
JUnit results; expected captures must exist and have valid PNG headers. The site includes a
manifest with the plugin/IDE version, source commit, test outcome, screenshot captions and hashes.
Pandoc renders the tracked guide; no raw report HTML or session payloads are copied.

Default-branch runs deploy through the Pages job. PRs upload `hunk-goland-docs` as a downloadable
preview without Pages permissions. The site has a version-specific page, and every release
attaches a complete documentation archive to preserve that version when the latest site changes.

The local UI-test runner also refreshes `build/site/index.html` automatically after the complete
fixture suite passes. Install `pandoc` and Python 3, then run:

```bash
./scripts/run-ui-tests weston integrationTest
```

Use `native` for visible GoLand or `xvfb` for headless X11. The runner records the evidence start
time and forces fresh tests instead of reusing cached results. Open `build/site/index.html` after
it succeeds. The generator stages the complete site first and retains an existing generated guide
under `build/site.previous-*/site/`. Failed tests, stale or missing captures, and rendering failures
leave the previous guide untouched. Unrelated files and symlinked output directories are never replaced.

For focused tests without guide generation, use `HUNK_BUILD_GUIDE=0 ./scripts/run-ui-tests ...`.
Calling `./gradlew integrationTest` directly remains test-only; `buildPlugin` does not launch the IDE
or generate documentation. To render an already verified capture set separately, invoke
`scripts/build-site.py --since <verification-start-time>` with `--refresh`, or with `--output` pointing
to a new preview directory. Old screenshots are intentionally rejected.

## Cut a draft release

1. Set the plugin version in `build.gradle.kts`; update installation paths and create matching
   `docs/releases/<version>.md` notes. Keep runtime behavior and dependencies within tested support.
2. Commit, push, and wait for Verify to pass. Review the generated documentation and install the ZIP.
3. Create an annotated tag such as `v0.1.0` on that commit and push it.
4. **Release** calls the same verification pipeline on the tag: unit tests, compatibility checks,
   real GoLand Driver, native Hunk sequential submissions/sync/deletion, and same-profile restart.
5. Only after all gates pass does it download the verified assets and recheck tag/version,
   source-commit identity, embedded plugin identity, and checksums. It creates a draft release with
   the ZIP, documentation archive, release notes, `SHA256SUMS`, and `build.json` provenance metadata.
6. Inspect the draft, do a manual install, then publish it on GitHub. Reruns may update a draft;
   they refuse to overwrite an already published release.

GitHub releases do not publish to JetBrains Marketplace. Marketplace ownership, signing, and
credentials would be configured separately. The public release is Linux-tested; additional
operating systems need their own native integration gates before they are advertised.

`JOURNEY.md` is a local ignored journal. It is not included in documentation, source tracking,
or release artifacts. Historical copies were removed before publishing the repository.
