import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class UiRunnerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        scripts = self.root / "scripts"
        scripts.mkdir()
        self.runner = scripts / "run-ui-tests"
        shutil.copyfile(Path(__file__).resolve().parents[1] / "run-ui-tests", self.runner)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for command in ("bash", "dirname", "date", "python3", "mktemp", "chmod", "mkdir", "rm", "sleep", "cat"):
            (self.bin / command).symlink_to(shutil.which(command))
        self.executable(self.bin / "pandoc", "#!/usr/bin/env bash\nexit 0\n")
        self.executable(self.bin / "xvfb-run", "#!/usr/bin/env bash\nshift 2\nexec \"$@\"\n")
        self.executable(self.bin / "weston", '''#!/usr/bin/env python3
import json, os, signal, socket, sys
if "--version" in sys.argv:
    print(os.environ.get("TEST_WESTON_VERSION", "weston 15.0.1"))
    sys.exit(0)
print("WESTON " + json.dumps(sys.argv[1:]), flush=True)
log = next(arg.split("=", 1)[1] for arg in sys.argv if arg.startswith("--log="))
with open(log, "w") as output:
    output.write("Fixture compositor startup\\n")
if os.environ.get("TEST_WESTON_EXIT"):
    sys.exit(int(os.environ["TEST_WESTON_EXIT"]))
name = next(arg.split("=", 1)[1] for arg in sys.argv if arg.startswith("--socket="))
connection = socket.socket(socket.AF_UNIX)
connection.bind(os.path.join(os.environ["XDG_RUNTIME_DIR"], name))
signal.pause()
''')
        self.executable(self.root / "gradlew",
                        '#!/usr/bin/env bash\nprintf "GRADLE %s\\n" "$@"\nexit "${TEST_GRADLE_EXIT:-0}"\n')
        (scripts / "build-site.py").write_text(
            'import json, os, sys\nprint("GUIDE " + json.dumps(sys.argv[1:]))\n'
            'sys.exit(int(os.environ.get("TEST_GUIDE_EXIT", "0")))\n')
        self.env = {**os.environ, "PATH": str(self.bin), "HUNK_BUILD_GUIDE": "1"}

    def executable(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def run_runner(self, *arguments, **environment):
        return subprocess.run([str(self.bin / "bash"), str(self.runner), *arguments],
                              cwd=self.root, env={**self.env, **environment},
                              text=True, capture_output=True, timeout=30)

    def guide_arguments(self, result):
        return json.loads(next(line.removeprefix("GUIDE ") for line in result.stdout.splitlines()
                               if line.startswith("GUIDE ")))

    def test_default_run_generates_guide_after_fresh_tests(self):
        result = self.run_runner()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("GRADLE integrationTest", result.stdout)
        self.assertIn("GRADLE --rerun-tasks", result.stdout)
        self.assertIn("GRADLE --no-build-cache", result.stdout)
        args = self.guide_arguments(result)
        self.assertEqual(args[0], "--since")
        self.assertRegex(args[1], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
        self.assertEqual(args[2:], ["--refresh"])
        self.assertLess(result.stdout.index("GRADLE integrationTest"), result.stdout.index("GUIDE "))

    def test_explicit_task_generates_guide_for_native_and_xvfb(self):
        for backend in ("native", "xvfb"):
            with self.subTest(backend=backend):
                result = self.run_runner(backend, ":integrationTest")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(f"GRADLE -PuiDisplayBackend={backend}", result.stdout)
                self.assertIn("GUIDE ", result.stdout)

    def test_failed_tests_never_invoke_generator(self):
        result = self.run_runner("native", "integrationTest", TEST_GRADLE_EXIT="3")
        self.assertEqual(result.returncode, 3)
        self.assertNotIn("GUIDE ", result.stdout)

    def test_weston_uses_fake_seat_wayland_and_cleans_runtime_before_guide(self):
        temporary = self.root / "runtime"
        temporary.mkdir()
        result = self.run_runner("weston", "integrationTest", TMPDIR=str(temporary))
        self.assertEqual(result.returncode, 0, result.stderr)
        arguments = json.loads(next(line.removeprefix("WESTON ") for line in result.stdout.splitlines()
                                    if line.startswith("WESTON ")))
        self.assertIn("--fake-seat", arguments)
        self.assertIn("--backend=headless", arguments)
        self.assertIn("--renderer=pixman", arguments)
        self.assertIn("GRADLE -PuiDisplayBackend=weston", result.stdout)
        self.assertIn("GRADLE -PuiToolkit=WLToolkit", result.stdout)
        self.assertIn("GUIDE ", result.stdout)
        self.assertEqual(list(temporary.iterdir()), [])

    def test_unsupported_weston_fails_before_launch_or_guide(self):
        for version in ("weston 13.0.0", "weston 14.0.2", "unknown"):
            with self.subTest(version=version):
                result = self.run_runner("weston", TEST_WESTON_VERSION=version)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn("requires Weston 15", result.stderr)
                self.assertNotIn("WESTON ", result.stdout)
                self.assertNotIn("GRADLE", result.stdout)
                self.assertNotIn("GUIDE ", result.stdout)

    def test_failed_weston_never_starts_ide_or_publishes_guide(self):
        result = self.run_runner("weston", TEST_WESTON_EXIT="7")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("Weston exited before creating", result.stderr)
        self.assertNotIn("GRADLE", result.stdout)
        self.assertNotIn("GUIDE ", result.stdout)

    def test_failed_generator_is_not_reported_as_success(self):
        result = self.run_runner("native", "integrationTest", TEST_GUIDE_EXIT="4")
        self.assertEqual(result.returncode, 4)
        self.assertIn("GUIDE ", result.stdout)

    def test_native_e2e_dry_run_and_opt_out_do_not_require_pandoc(self):
        (self.bin / "pandoc").unlink()
        for arguments, environment in ((("native", "realHunkE2e"), {}),
                                       (("native", "integrationTest", "--dry-run"), {}),
                                       (("native", "integrationTest", "-m"), {}),
                                       (("native", "integrationTest"), {"HUNK_BUILD_GUIDE": "0"})):
            with self.subTest(arguments=arguments, environment=environment):
                result = self.run_runner(*arguments, **environment)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertNotIn("GUIDE ", result.stdout)
                self.assertNotIn("GRADLE --rerun-tasks", result.stdout)

    def test_missing_pandoc_and_invalid_opt_out_fail_before_starting_ide(self):
        result = self.run_runner(HUNK_BUILD_GUIDE="maybe")
        self.assertEqual(result.returncode, 2)
        self.assertIn("must be 0 or 1", result.stderr)
        (self.bin / "pandoc").unlink()
        result = self.run_runner()
        self.assertEqual(result.returncode, 2)
        self.assertIn("requires 'pandoc'", result.stderr)
        self.assertNotIn("GRADLE", result.stdout)


if __name__ == "__main__":
    unittest.main()
