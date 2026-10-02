import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class WestonInstallerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.events = self.root / "events"
        for command in ("bash", "mktemp"):
            (self.bin / command).symlink_to(shutil.which(command))
        for command in ("curl", "tar", "meson"):
            self.stub(command, f'printf "{command} %s\\n" "$*" >> "$TEST_EVENTS"\n'
                      'if [[ "${TEST_FAILURE:-}" = "' + command + '" ]]; then exit 7; fi\n')
        self.stub("sha256sum", 'IFS= read -r checksum\n'
                  'printf "checksum %s\\n" "$checksum" >> "$TEST_EVENTS"\n'
                  'if [[ "${TEST_FAILURE:-}" = "checksum" ]]; then exit 8; fi\n')
        self.env = {**os.environ, "PATH": str(self.bin), "RUNNER_TEMP": str(self.root),
                    "TEST_EVENTS": str(self.events), "GITHUB_PATH": str(self.root / "github-path"),
                    "GITHUB_ENV": str(self.root / "github-env")}
        self.installer = Path(__file__).resolve().parents[1] / "install-ci-weston"

    def stub(self, name, body):
        path = self.bin / name
        path.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body)
        path.chmod(0o755)

    def run_installer(self, failure):
        return subprocess.run([str(self.bin / "bash"), str(self.installer)],
                              env={**self.env, "TEST_FAILURE": failure},
                              capture_output=True, text=True, timeout=10)

    def test_download_and_checksum_fail_before_extracting_or_exporting(self):
        for failure, code in (("curl", 7), ("checksum", 8)):
            with self.subTest(failure=failure):
                self.events.write_text("")
                result = self.run_installer(failure)
                self.assertEqual(result.returncode, code, result.stderr)
                events = self.events.read_text()
                self.assertNotIn("tar ", events)
                self.assertNotIn("meson ", events)
                self.assertFalse((self.root / "github-path").exists())
                self.assertFalse((self.root / "github-env").exists())

    def test_each_pinned_archive_is_verified_before_extracting(self):
        result = self.run_installer("meson")
        self.assertEqual(result.returncode, 7, result.stderr)
        events = self.events.read_text().splitlines()
        expected = (
            ("wayland-protocols-1.49", "ec4c8f74942d6dff7ace8b4ce4764f0ef9ff618a935d974ea77edee2ad240b14"),
            ("libdisplay-info-0.3.0", "6ae77cd937f9cf7d1321d35c116062c4911e8447010a6a713ac4286f7a9d5987"),
            ("weston-15.0.1", "551d039bfb0c837ba5a4d027cdb8ee16bded0eedb789821f8025d8a64b791f6d"),
        )
        for index, (archive, checksum) in enumerate(expected):
            download, verify, extract = events[index * 3:index * 3 + 3]
            self.assertTrue(download.startswith("curl --proto =https --proto-redir =https"), download)
            self.assertIn(archive, download)
            self.assertTrue(verify.startswith(f"checksum {checksum}  "), verify)
            self.assertIn(archive, verify)
            self.assertTrue(extract.startswith("tar -xJf "), extract)
            self.assertIn(archive, extract)
        self.assertIn("--wrap-mode=nofallback", events[-1])
        self.assertFalse((self.root / "github-path").exists())

    def test_ci_uses_wayland_for_both_driver_gates_without_xvfb_fallback(self):
        workflow = (self.installer.parent.parent / ".github/workflows/verification.yml").read_text()
        self.assertIn("bash scripts/install-ci-weston", workflow)
        self.assertIn("run-ui-tests weston integrationTest", workflow)
        self.assertIn("run-ui-tests weston realHunkE2e", workflow)
        self.assertNotIn("xvfb", workflow.lower())
        self.assertNotIn("continue-on-error", workflow)


if __name__ == "__main__":
    unittest.main()
