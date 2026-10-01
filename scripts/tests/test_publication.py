import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
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

    def test_native_evidence_requires_successful_current_upstream_version(self):
        ci.check_native_evidence({"status": "passed", "hunkVersion": "0.23.0"}, "fixture")
        for status, version in (("passed", "0.20.0"), ("failed", "0.23.0"), ("passed", "0.22.0")):
            with self.subTest(status=status, version=version), self.assertRaisesRegex(ValueError, "pinned version"):
                ci.check_native_evidence({"status": status, "hunkVersion": version}, "fixture")

    def test_installer_and_release_guard_pin_same_version(self):
        installer = (Path(__file__).resolve().parents[1] / "install-ci-hunk").read_text()
        self.assertIn(f"hunk_version={ci.HUNK_VERSION}\n", installer)

    def test_historical_audit_uses_portable_commit_pinned_sources(self):
        audit = (ci.ROOT / "docs/upstream-no-change-e2e.md").read_text()
        self.assertNotIn("/home/", audit)
        self.assertNotIn("/Users/", audit)
        self.assertNotIn("../../../oss/hunk/", audit)
        self.assertIn("Unshipped capability-capture experiment", audit)
        links = re.findall(r"https://github\.com/modem-dev/hunk/blob/([^/]+)/", audit)
        self.assertTrue(links, "Historical audit has no upstream source citations")
        self.assertEqual(set(links), {"c75a9a3b084059d8ff4ccfb257251e594a40b4a5"})

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

    def guide_captures(self):
        return [{"file": name, "title": "Fixture <capture>", "description": "Reviewer & agent"}
                for name in ("02-editor-thread.png", "11-diff-right-thread.png",
                             "02-review-navigator.png", "06-external-sync.png", "01-editor-line.png")]

    def test_guide_illustrations_stay_with_their_sections_without_gallery_duplicates(self):
        guide = ('<h2 id="start-a-review-and-add-a-comment">Add</h2><p>Compose</p>'
                 '<h2 id="find-comments-and-read-agent-responses">Find</h2><p>Navigate</p>'
                 '<h2 id="sessions-and-restarts">Sessions</h2><p>Recover</p>')
        rendered, remaining = site.illustrate_guide(guide, self.guide_captures())
        compose, navigate = rendered.split('<h2 id="find-comments-and-read-agent-responses">')
        self.assertIn("02-editor-thread.png", compose)
        self.assertIn("11-diff-right-thread.png", compose)
        self.assertNotIn("02-review-navigator.png", compose)
        self.assertIn("02-review-navigator.png", navigate)
        self.assertIn("06-external-sync.png", navigate)
        self.assertEqual([card["file"] for card in remaining], ["01-editor-line.png"])
        self.assertIn("Fixture &lt;capture&gt;", rendered)
        self.assertIn("Reviewer &amp; agent", rendered)

    def test_missing_guide_section_or_capture_blocks_publication(self):
        with self.assertRaisesRegex(ValueError, "guide section"):
            site.illustrate_guide("<h2>Renamed section</h2>", self.guide_captures())
        with self.assertRaisesRegex(ValueError, "illustrations"):
            site.illustrate_guide("<h2>Renamed section</h2>", [])

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


class GuideGenerationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / "build/site"
        self.report = self.root / "build/reports/ux"
        self.report.mkdir(parents=True)
        self.since = datetime.now(timezone.utc) - timedelta(seconds=10)
        self.names = ["02-editor-thread.png", "11-diff-right-thread.png",
                      "02-review-navigator.png", "06-external-sync.png", "01-editor-line.png"]
        self.junit = self.root / "build/test-results/integrationTest/TEST-fixture.xml"
        self.junit.parent.mkdir(parents=True)
        self.junit.write_text(f'<testsuite tests="2" timestamp="{datetime.now(timezone.utc).isoformat()}"/>')
        docs = self.root / "docs"
        docs.mkdir()
        (docs / "screenshots.json").write_text(json.dumps(self.names))
        (docs / "site.css").write_text("body { color: black; }")
        (docs / "user-guide.md").write_text("# Tracked guide")
        for name in self.names:
            (self.report / name).write_bytes(b"\x89PNG\r\n\x1a\nfixture")
        self.write_report()
        guide = ('<h2 id="start-a-review-and-add-a-comment">Add</h2>'
                 '<h2 id="find-comments-and-read-agent-responses">Find</h2>')
        for replacement in (patch.object(site, "ROOT", self.root),
                            patch.object(site, "versions", return_value=("0.1.0", "262.9437.286")),
                            patch.object(site, "source_commit", return_value="abc123"),
                            patch.object(site.subprocess, "run",
                                         return_value=subprocess.CompletedProcess([], 0, stdout=guide))):
            replacement.start()
            self.addCleanup(replacement.stop)

    def write_report(self, status="passed"):
        cards = "".join(f'<article class="card"><h2>Fixture</h2><p>Capture</p><img src="{name}"></article>'
                        for name in self.names)
        (self.report / "index.html").write_text(f"Driver run: {status}{cards}<pre>PRIVATE PAYLOAD</pre>")

    def render(self, refresh=False):
        with patch("sys.stdout", new_callable=io.StringIO):
            site.build(self.since, self.output, refresh)

    def files(self, directory):
        return {path.relative_to(directory).as_posix(): path.read_bytes()
                for path in directory.rglob("*") if path.is_file()}

    def test_complete_site_contains_only_guide_and_allowlisted_captures(self):
        (self.report / "idea.log").write_text("PRIVATE LOG")
        (self.report / "real-daemon-e2e.json").write_text("PRIVATE DAEMON")
        self.render()
        manifest = json.loads((self.output / "manifest.json").read_text())
        self.assertEqual(manifest["testResults"]["tests"], 2)
        self.assertEqual(manifest["verificationStartedAt"], self.since.isoformat())
        self.assertIn(f'<time datetime="{self.since.isoformat()}">',
                      (self.output / "index.html").read_text())
        self.assertEqual([card["file"] for card in manifest["screenshots"]], self.names)
        files = self.files(self.output)
        self.assertEqual(len(files), len(self.names) + 4)
        self.assertNotIn(b"PRIVATE", b"".join(files.values()))
        for capture in manifest["screenshots"]:
            self.assertEqual(capture["sha256"], ci.digest(self.output / "assets" / capture["file"]))

    def test_refresh_replaces_whole_site_and_retains_previous_preview(self):
        self.render()
        previous = self.files(self.output)
        self.names.remove("01-editor-line.png")
        (self.root / "docs/screenshots.json").write_text(json.dumps(self.names))
        (self.root / "docs/site.css").write_text("body { color: blue; }")
        self.write_report()
        self.render(refresh=True)
        self.assertFalse((self.output / "assets/01-editor-line.png").exists())
        self.assertIn("blue", (self.output / "assets/site.css").read_text())
        archives = list(self.output.parent.glob("site.previous-*/site"))
        self.assertEqual(len(archives), 1)
        self.assertEqual(self.files(archives[0]), previous)
        self.assertEqual(list(self.output.parent.glob(".site.staging-*")), [])

    def test_failed_incomplete_or_stale_test_run_preserves_previous_guide(self):
        self.render()
        previous = self.files(self.output)
        for attributes, message in (("tests=\"2\" failures=\"1\"", "did not pass"),
                                    ("tests=\"1\"", "complete visual"),
                                    ("tests=\"2\" skipped=\"1\"", "did not pass")):
            with self.subTest(attributes=attributes):
                self.junit.write_text(f'<testsuite {attributes} timestamp="{datetime.now(timezone.utc).isoformat()}"/>')
                with self.assertRaisesRegex(ValueError, message):
                    self.render(refresh=True)
                self.assertEqual(self.files(self.output), previous)
        self.junit.write_text('<testsuite tests="2" timestamp="2000-01-01T00:00:00Z"/>')
        with self.assertRaisesRegex(ValueError, "Stale test run"):
            self.render(refresh=True)
        self.assertEqual(self.files(self.output), previous)
        self.assertEqual(list(self.output.parent.glob("site.previous-*")), [])

    def test_failed_report_missing_or_stale_capture_preserves_previous_guide(self):
        self.render()
        previous = self.files(self.output)
        self.write_report(status="failed")
        with self.assertRaisesRegex(ValueError, "did not finish"):
            self.render(refresh=True)
        self.write_report()
        capture = self.report / self.names[0]
        os.utime(capture, (0, 0))
        with self.assertRaisesRegex(ValueError, "Stale evidence"):
            self.render(refresh=True)
        capture.unlink()
        with self.assertRaisesRegex(ValueError, "Missing"):
            self.render(refresh=True)
        self.assertEqual(self.files(self.output), previous)

    def test_render_or_asset_copy_failure_leaves_previous_guide_intact(self):
        self.render()
        previous = self.files(self.output)
        with patch.object(site.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "pandoc")):
            with self.assertRaises(subprocess.CalledProcessError):
                self.render(refresh=True)
        with patch.object(site.shutil, "copyfile", side_effect=OSError("copy failed")):
            with self.assertRaisesRegex(OSError, "copy failed"):
                self.render(refresh=True)
        self.assertEqual(self.files(self.output), previous)
        self.assertEqual(list(self.output.parent.glob(".site.staging-*")), [])
        self.assertEqual(list(self.output.parent.glob("site.previous-*")), [])

    def test_failed_promotion_restores_previous_guide(self):
        self.render()
        previous = self.files(self.output)
        rename = Path.rename

        def fail_staged_promotion(path, target):
            if ".staging-" in path.parent.name:
                raise OSError("promotion failed")
            return rename(path, target)

        with patch.object(Path, "rename", new=fail_staged_promotion):
            with self.assertRaisesRegex(OSError, "promotion failed"):
                self.render(refresh=True)
        self.assertEqual(self.files(self.output), previous)
        self.assertEqual(list(self.output.parent.glob("site.previous-*")), [])

    def test_refresh_refuses_unrelated_data_and_symlinks(self):
        self.render()
        for name, directory in (("notes.txt", False), ("notes", True)):
            with self.subTest(name=name):
                path = self.output / name
                path.mkdir() if directory else path.write_text("personal notes")
                with self.assertRaisesRegex(ValueError, "unrelated"):
                    self.render(refresh=True)
                path.rmdir() if directory else path.unlink()
        link = self.output / "assets/link.png"
        link.symlink_to(self.report / self.names[0])
        with self.assertRaisesRegex(ValueError, "symlinks"):
            self.render(refresh=True)
        link.unlink()
        alias = self.root / "linked-site"
        alias.symlink_to(self.output, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlinked"):
            site.build(self.since, alias, refresh=True)
        with self.assertRaisesRegex(ValueError, "Unsafe site output"):
            site.build(self.since, self.root, refresh=True)

    def test_existing_output_requires_explicit_refresh_and_a_generated_manifest(self):
        self.output.mkdir(parents=True)
        (self.output / "personal.txt").write_text("keep me")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.render()
        with self.assertRaisesRegex(ValueError, "without a guide manifest"):
            self.render(refresh=True)
        self.assertEqual((self.output / "personal.txt").read_text(), "keep me")

    def test_refresh_refuses_malformed_manifest_without_touching_site(self):
        self.render()
        manifest = self.output / "manifest.json"
        for value in ([], {"pluginVersion": "../unsafe", "testOutcome": "passed"},
                      {"pluginVersion": "0.1.0", "testOutcome": "passed", "screenshots": [None]},
                      {"pluginVersion": "0.1.0", "testOutcome": "passed", "screenshots": [{"file": None}]}):
            with self.subTest(value=value):
                manifest.write_text(json.dumps(value))
                previous = self.files(self.output)
                with self.assertRaisesRegex(ValueError, "Refusing to replace"):
                    self.render(refresh=True)
                self.assertEqual(self.files(self.output), previous)


if __name__ == "__main__":
    unittest.main()
