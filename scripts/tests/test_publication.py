import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ci

spec = importlib.util.spec_from_file_location("build_site", Path(__file__).resolve().parents[1] / "build-site.py")
site = importlib.util.module_from_spec(spec)
spec.loader.exec_module(site)


def bundle(path, version):
    jar = io.BytesIO()
    with ZipFile(jar, "w") as archive:
        archive.writestr("META-INF/plugin.xml", f"<idea-plugin><id>dev.hunk.session</id><version>{version}</version></idea-plugin>")
    with ZipFile(path, "w") as archive:
        archive.writestr("hunk-goland/lib/plugin.jar", jar.getvalue())


class PublicationGuardsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.since = datetime.now(timezone.utc) - timedelta(seconds=10)

    def junit(self, failures=0, timestamp=None):
        path = self.root / "TEST-fixture.xml"
        path.write_text(f'<testsuite tests="2" failures="{failures}" errors="0" skipped="0" timestamp="{timestamp or datetime.now(timezone.utc).isoformat()}"/>')
        return path

    def report(self, name="01-editor-line.png"):
        (self.root / name).write_bytes(b"\x89PNG\r\n\x1a\nfixture")
        (self.root / "index.html").write_text(
            f'<p>Driver run: passed</p><article class="card"><h2>Editor &amp; diff</h2>'
            f'<p>Fixture capture</p><img src="{name}"></article>'
            '<section>PRIVATE REQUEST BODY</section>')
        return name

    def test_failed_or_missing_tests_block_publication(self):
        with self.assertRaisesRegex(ValueError, "No JUnit"):
            ci.test_results(self.root, self.since)
        self.junit(failures=1)
        with self.assertRaisesRegex(ValueError, "did not pass"):
            ci.test_results(self.root, self.since)

    def test_newly_copied_old_test_results_are_rejected(self):
        self.junit(timestamp=(self.since - timedelta(days=1)).isoformat())
        with self.assertRaisesRegex(ValueError, "Stale test run"):
            ci.test_results(self.root, self.since)

    def test_allowlist_excludes_private_report_content(self):
        name = self.report()
        (self.root / "idea.log").write_text("PRIVATE LOG")
        (self.root / "real-daemon-e2e.json").write_text('{"private": "body"}')
        selected = site.select_captures(self.root, [name], self.since)
        self.assertEqual(selected[0]["title"], "Editor & diff")
        self.assertEqual(selected[0]["file"], name)
        self.assertNotIn("PRIVATE", json.dumps(selected))

    def test_missing_capture_blocks_docs(self):
        self.report()
        with self.assertRaisesRegex(ValueError, "Missing"):
            site.select_captures(self.root, ["02-missing.png"], self.since)

    def test_old_capture_blocks_docs(self):
        name = self.report()
        os.utime(self.root / name, (0, 0))
        with self.assertRaisesRegex(ValueError, "Stale evidence"):
            site.select_captures(self.root, [name], self.since)

    def test_path_traversal_and_symlink_captures_are_rejected(self):
        name = self.report()
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            site.select_captures(self.root, ["../secret.png"], self.since)
        link = self.root / "02-link.png"
        link.symlink_to(self.root / name)
        with self.assertRaisesRegex(ValueError, "symlinked"):
            site.select_captures(self.root, [link.name], self.since)

    def test_capture_must_be_in_successful_report(self):
        name = self.report()
        (self.root / "index.html").write_text("Driver run: failed")
        with self.assertRaisesRegex(ValueError, "did not finish"):
            site.select_captures(self.root, [name], self.since)

    def test_tag_and_embedded_bundle_version_are_checked(self):
        with self.assertRaisesRegex(ValueError, "tag"):
            ci.check_tag("0.1.0", "v0.5.0")
        path = self.root / "plugin.zip"
        bundle(path, "0.1.0")
        self.assertEqual(ci.bundle_version(path), "0.1.0")

    def test_release_assets_reject_tampering_and_wrong_commit(self):
        version = "0.1.0"
        bundle(self.root / f"hunk-goland-{version}.zip", version)
        (self.root / f"hunk-goland-docs-{version}.zip").write_bytes(b"docs")
        (self.root / "release-notes.md").write_text("notes")
        artifacts = {path.name: ci.digest(path) for path in sorted(self.root.iterdir())}
        manifest = {"sourceCommit": "abc", "pluginVersion": version, "artifacts": artifacts}
        (self.root / "build.json").write_text(json.dumps(manifest))
        (self.root / "SHA256SUMS").write_text("".join(f"{sha}  {name}\n" for name, sha in artifacts.items()))
        with patch.object(ci, "versions", return_value=(version, "2026.2.1.1")), \
                patch.object(ci, "source_commit", return_value="abc"):
            ci.verify_package("v0.1.0", self.root)
            (self.root / "release-notes.md").write_text("tampered")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                ci.verify_package("v0.1.0", self.root)
        with patch.object(ci, "source_commit", return_value="different"):
            with self.assertRaisesRegex(ValueError, "tagged source"):
                ci.verify_package("v0.1.0", self.root)


if __name__ == "__main__":
    unittest.main()
