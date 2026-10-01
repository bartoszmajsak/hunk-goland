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
        for command in ("bash", "dirname", "date", "python3"):
            (self.bin / command).symlink_to(shutil.which(command))
        self.executable(self.bin / "pandoc", "#!/usr/bin/env bash\nexit 0\n")
        self.executable(self.bin / "xvfb-run", "#!/usr/bin/env bash\nshift 2\nexec \"$@\"\n")
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
                              text=True, capture_output=True)

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
