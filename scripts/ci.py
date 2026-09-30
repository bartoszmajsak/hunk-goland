"""Release identity and evidence guards; uses only the Python standard library."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
import xml.etree.ElementTree as ET
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def versions(root=ROOT):
    version = re.search(r'^version = "([0-9]+\.[0-9]+\.[0-9]+)"$',
                        (root / "build.gradle.kts").read_text(), re.MULTILINE)
    ide = re.search(r'^platformVersion=(\S+)$',
                    (root / "gradle.properties").read_text(), re.MULTILINE)
    require(version and ide, "Cannot resolve explicit plugin and GoLand versions")
    return version.group(1), ide.group(1)


def check_tag(version, tag):
    require(not tag or tag == f"v{version}", "Release tag must equal the plugin version")


def instant(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed


def fresh_file(path, since):
    require(path.is_file() and not path.is_symlink(), f"Missing or symlinked evidence: {path}")
    require(path.stat().st_mtime >= since.timestamp(), f"Stale evidence: {path}")


def test_results(directory, since):
    reports = sorted(directory.glob("TEST-*.xml"))
    require(reports, f"No JUnit reports in {directory}")
    counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for report in reports:
        fresh_file(report, since)
        suite = ET.parse(report).getroot()
        require(suite.tag == "testsuite", f"Unexpected JUnit document: {report}")
        require(instant(suite.attrib["timestamp"]) >= since, f"Stale test run: {report}")
        for key in counts:
            counts[key] += int(suite.attrib.get(key, "0"))
    require(counts["tests"] > 0 and not any(counts[key] for key in
                                          ("failures", "errors", "skipped")),
            f"Verification did not pass completely in {directory}: {counts}")
    return counts


def bundle_version(path):
    with ZipFile(path) as bundle:
        descriptors = []
        for name in bundle.namelist():
            if name.endswith(".jar"):
                with ZipFile(io.BytesIO(bundle.read(name))) as jar:
                    if "META-INF/plugin.xml" in jar.namelist():
                        descriptors.append(ET.fromstring(jar.read("META-INF/plugin.xml")))
        require(len(descriptors) == 1, "Bundle must contain exactly one plugin descriptor")
        require(descriptors[0].findtext("id") == "dev.hunk.session", "Unexpected plugin identity")
        return descriptors[0].findtext("version")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def source_commit():
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    require(not os.environ.get("GITHUB_SHA") or os.environ["GITHUB_SHA"] == commit,
            "Checked-out source does not match the workflow commit")
    if os.environ.get("GITHUB_SHA"):
        dirty = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
        require(not dirty.strip(), "Workflow sources changed during verification")
    return commit


def check_pins():
    for workflow in (ROOT / ".github/workflows").glob("*.yml"):
        for line in workflow.read_text().splitlines():
            if re.search(r"\buses:", line) and "uses: ./" not in line:
                require(re.search(r"uses:\s+[\w./-]+@[0-9a-f]{40}\s+#\s+v\S+", line),
                        f"Action needs a full SHA and release comment: {workflow.name}: {line}")


def package(since, tag):
    version, ide = versions()
    check_tag(version, tag)
    results = {name: test_results(ROOT / "build/test-results" / name, since)
               for name in ("test", "integrationTest", "realHunkE2e")}
    require(results["integrationTest"]["tests"] >= 2 and results["realHunkE2e"]["tests"] >= 2,
            "Visual cross-view and native restart scenarios must both run")
    native = ROOT / "build/reports/ux"
    for name in ("real-daemon-e2e.json", "real-daemon-recovery-e2e.json"):
        path = native / name
        fresh_file(path, since)
        evidence = json.loads(path.read_text())
        require(evidence.get("status") == "passed" and evidence.get("hunkVersion") == "0.20.0",
                f"Native Hunk evidence is not successful for the pinned version: {name}")
    bundle = ROOT / f"build/distributions/hunk-goland-{version}.zip"
    fresh_file(bundle, since)
    require(bundle_version(bundle) == version, "Embedded bundle version does not match sources")
    site = ROOT / "build/site"
    site_manifest = json.loads((site / "manifest.json").read_text())
    commit = source_commit()
    require(site_manifest["sourceCommit"] == commit and site_manifest["pluginVersion"] == version,
            "Documentation belongs to a different build")
    output = ROOT / "build/release"
    require(not output.exists(), "Release output already exists; use a fresh verification directory")
    output.mkdir(parents=True)
    shutil.copyfile(bundle, output / bundle.name)
    shutil.make_archive(str(output / f"hunk-goland-docs-{version}"), "zip", site)
    notes = ROOT / f"docs/releases/{version}.md"
    require(notes.is_file(), f"Missing release notes: {notes}")
    shutil.copyfile(notes, output / "release-notes.md")
    artifacts = {path.name: digest(path) for path in sorted(output.iterdir())}
    manifest = {"pluginVersion": version, "ideVersion": ide, "hunkVersion": "0.20.0",
                "sourceCommit": commit, "testResults": results, "artifacts": artifacts,
                "verifiedAt": datetime.now(timezone.utc).isoformat(),
                "runUrl": f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/"
                          f"{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/"
                          f"{os.environ.get('GITHUB_RUN_ID', '')}"}
    (output / "build.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (output / "SHA256SUMS").write_text("".join(f"{sha}  {name}\n" for name, sha in artifacts.items()))


def verify_package(tag, output=None):
    output = output or ROOT / "build/release"
    version, _ = versions()
    check_tag(version, tag)
    manifest = json.loads((output / "build.json").read_text())
    require(manifest["pluginVersion"] == version and manifest["sourceCommit"] == source_commit(),
            "Release assets do not match the tagged source")
    expected = {f"hunk-goland-{version}.zip", f"hunk-goland-docs-{version}.zip", "release-notes.md"}
    require(set(manifest["artifacts"]) == expected, "Unexpected release artifact set")
    require({path.name for path in output.iterdir()} == expected | {"build.json", "SHA256SUMS"},
            "Unverified files in release output")
    for name, sha in manifest["artifacts"].items():
        path = output / name
        require(path.is_file() and not path.is_symlink() and digest(path) == sha,
                f"Release artifact checksum mismatch: {name}")
    sums = "".join(f"{sha}  {name}\n" for name, sha in sorted(manifest["artifacts"].items()))
    require((output / "SHA256SUMS").read_text() == sums, "Checksum document differs from manifest")
    require(bundle_version(output / f"hunk-goland-{version}.zip") == version,
            "Release bundle has the wrong embedded version")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("metadata", "check-pins", "package", "verify-package"))
    parser.add_argument("--tag", default="")
    parser.add_argument("--since")
    args = parser.parse_args()
    if args.command == "metadata":
        version, ide = versions()
        check_tag(version, args.tag)
        print(f"plugin-version={version}\nide-version={ide}")
    elif args.command == "check-pins":
        check_pins()
    elif args.command == "package":
        require(args.since, "--since is required for packaging fresh evidence")
        package(instant(args.since), args.tag)
    else:
        require(args.tag, "--tag is required when publishing")
        verify_package(args.tag)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, ET.ParseError) as error:
        print(f"Verification rejected: {error}", file=sys.stderr)
        sys.exit(1)
