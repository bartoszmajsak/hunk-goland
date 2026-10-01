"""Publish the guide and allowlisted, fresh fixture captures; never copy raw reports."""

import argparse
from html import escape
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

from ci import ROOT, digest, fresh_file, instant, require, source_commit, test_results, versions


class ScreenshotCards(HTMLParser):
    def __init__(self):
        super().__init__()
        self.cards = {}
        self.card = None
        self.field = None

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "article" and attrs.get("class") == "card":
            self.card = {"title": "", "description": ""}
        if self.card is not None:
            if tag in ("h2", "p"):
                self.field = "title" if tag == "h2" else "description"
            elif tag == "img":
                self.card["file"] = attrs.get("src", "")

    def handle_endtag(self, tag):
        if tag in ("h2", "p"):
            self.field = None
        if tag == "article" and self.card is not None:
            require(self.card.get("file") not in self.cards, "Duplicate screenshot card")
            self.cards[self.card.get("file")] = self.card
            self.card = None

    def handle_data(self, data):
        if self.card is not None and self.field:
            self.card[self.field] += data


def select_captures(report, filenames, since):
    report_file = report / "index.html"
    fresh_file(report_file, since)
    html = report_file.read_text()
    require('Driver run: passed' in html, "Fixture UX report did not finish successfully")
    parser = ScreenshotCards()
    parser.feed(html)
    require(filenames and len(set(filenames)) == len(filenames), "Empty or duplicate screenshot allowlist")
    selected = []
    for name in filenames:
        require(re.fullmatch(r"\d{2}-[a-z0-9-]+\.png", name), "Unsafe screenshot filename")
        path = report / name
        fresh_file(path, since)
        require(path.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n", f"Not a PNG: {name}")
        require(name in parser.cards, f"Screenshot was not captured in this report: {name}")
        selected.append({**parser.cards[name], "sha256": digest(path)})
    return selected


def capture_card(card):
    return (
        f'<article><h3>{escape(card["title"])}</h3><p>{escape(card["description"])}</p>'
        f'<a href="assets/{card["file"]}"><img loading="lazy" src="assets/{card["file"]}" '
        f'alt="{escape(card["title"])}"></a></article>'
    )


def illustrate_guide(content, captures):
    sections = {
        "start-a-review-and-add-a-comment": ["02-editor-thread.png", "11-diff-right-thread.png"],
        "find-comments-and-read-agent-responses": ["02-review-navigator.png", "06-external-sync.png"],
    }
    by_file = {card["file"]: card for card in captures}
    placed = set()
    for anchor, filenames in sections.items():
        require(all(name in by_file for name in filenames), f"Missing guide illustrations: {anchor}")
        cards = "\n".join(capture_card(by_file[name]) for name in filenames)
        pattern = rf'(<h2\b[^>]*\bid="{re.escape(anchor)}"[^>]*>.*?)(?=<h2\b|\Z)'
        content, count = re.subn(
            pattern,
            lambda match: match.group(1) + f'<div class="gallery guide-captures">{cards}</div>\n',
            content,
            flags=re.DOTALL,
        )
        require(count == 1, f"Missing or duplicate guide section: {anchor}")
        placed.update(filenames)
    return content, [card for card in captures if card["file"] not in placed]


def validate_output(output, refresh):
    require(output != ROOT and output not in ROOT.parents, "Unsafe site output directory")
    require(not any(path.is_symlink() for path in (output, *output.parents)),
            "Site output cannot use symlinked directories")
    if not output.exists():
        return
    require(refresh, "Site output already exists; use --refresh to replace a generated guide")
    require(output.is_dir(), "Site output is not a generated guide directory")
    manifest_file = output / "manifest.json"
    require(manifest_file.is_file() and not manifest_file.is_symlink(),
            "Refusing to replace a directory without a guide manifest")
    manifest = json.loads(manifest_file.read_text())
    require(isinstance(manifest, dict), "Refusing to replace an unrecognized guide manifest")
    version = manifest.get("pluginVersion", "")
    require(isinstance(version, str) and re.fullmatch(r"\d+\.\d+\.\d+", version)
            and manifest.get("testOutcome") == "passed",
            "Refusing to replace an unrecognized guide manifest")
    captures = manifest.get("screenshots", [])
    require(isinstance(captures, list) and all(isinstance(capture, dict) for capture in captures),
            "Refusing to replace an unsafe guide manifest")
    names = [capture.get("file") for capture in captures]
    require(all(isinstance(name, str) for name in names), "Refusing to replace an unsafe guide manifest")
    require(names and len(set(names)) == len(names)
            and all(re.fullmatch(r"\d{2}-[a-z0-9-]+\.png", name) for name in names),
            "Refusing to replace an unsafe guide manifest")
    expected = {"index.html", "manifest.json", "assets/site.css", f"versions/{version}/index.html"}
    expected.update(f"assets/{name}" for name in names)
    paths = list(output.rglob("*"))
    require(not any(path.is_symlink() for path in paths), "Existing guide contains symlinks")
    actual = {path.relative_to(output).as_posix() for path in paths if path.is_file()}
    require(actual == expected, "Existing guide contains missing or unrelated files; leaving it untouched")
    directories = {parent.as_posix() for name in expected for parent in Path(name).parents
                   if parent != Path(".")}
    require(all(path.is_file() or path.relative_to(output).as_posix() in directories for path in paths),
            "Existing guide contains unrelated directories; leaving it untouched")


def publish(staged, output):
    previous = None
    if output.exists():
        archive = Path(tempfile.mkdtemp(prefix=f"{output.name}.previous-", dir=output.parent))
        previous = archive / "site"
        output.rename(previous)
    try:
        staged.rename(output)
    except OSError:
        if previous is not None:
            previous.rename(output)
            previous.parent.rmdir()
        raise
    if previous is not None:
        print(f"Previous guide retained at {previous}")


def build(since, output, refresh=False):
    output = Path(os.path.abspath(output))
    validate_output(output, refresh)
    results = test_results(ROOT / "build/test-results/integrationTest", since)
    require(results["tests"] >= 2, "Guide generation requires the complete visual and cross-view suite")
    version, ide = versions()
    report = ROOT / "build/reports/ux"
    captures = select_captures(report, json.loads((ROOT / "docs/screenshots.json").read_text()), since)
    guide = (ROOT / "docs/user-guide.md").read_text()
    # The guide is tracked Markdown; rendering does not ingest logs, request bodies, or raw report HTML.
    content = subprocess.run(["pandoc", "--from=gfm", "--to=html5"], input=guide,
                             text=True, capture_output=True, check=True).stdout
    content, remaining_captures = illustrate_guide(content, captures)
    commit = source_commit()
    manifest = {"pluginVersion": version, "ideVersion": ide, "sourceCommit": commit,
                "testOutcome": "passed", "testResults": results,
                "verificationStartedAt": since.isoformat(), "screenshots": captures}
    cards = "\n".join(capture_card(card) for card in remaining_captures)
    html = f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Hunk Session: user guide</title><link rel="stylesheet" href="assets/site.css"></head>
<body><header><p class="eyebrow">Hunk Session for GoLand</p><h1>Review together, inside your editor.</h1>
<p>Setup, comments, agent responses, and navigation, illustrated by real GoLand tests.</p>
<p class="evidence">Plugin {escape(version)} · GoLand {escape(ide)} · fixture tests passed ·
run started <time datetime="{escape(since.isoformat())}">{escape(since.isoformat())}</time> ·
commit <code>{escape(commit[:12])}</code> · <a href="manifest.json">capture manifest</a></p>
<nav><a href="#guide">User guide</a><a href="#features">More feature captures</a></nav></header>
<main><section id="guide">{content}</section><section id="features"><h2>More features in GoLand</h2>
<p>These screenshots exercise the generated Go/Git fixture. Native Hunk semantics are verified
separately by the daemon and restart E2E gates.</p><div class="gallery">{cards}</div></section></main>
<footer>Hunk is installed separately. Screenshots are captured from the IDE, not UI mockups.</footer></body></html>'''
    # Assemble everything beside the destination before moving the previous valid guide.
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f".{output.name}.staging-", dir=output.parent) as directory:
        staged = Path(directory) / "site"
        assets = staged / "assets"
        assets.mkdir(parents=True)
        shutil.copyfile(ROOT / "docs/site.css", assets / "site.css")
        for capture in captures:
            shutil.copyfile(report / capture["file"], assets / capture["file"])
        (staged / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        (staged / "index.html").write_text(html)
        snapshot = staged / "versions" / version
        snapshot.mkdir(parents=True)
        (snapshot / "index.html").write_text(html.replace('href="assets/', 'href="../../assets/')
                                           .replace('src="assets/', 'src="../../assets/')
                                           .replace('href="manifest.json"', 'href="../../manifest.json"'))
        validate_output(output, refresh)
        publish(staged, output)
    print(f"Built {len(captures)} verified feature captures at {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--since", required=True, help="UTC start time of the successful verification run")
    parser.add_argument("--output", type=Path, default=ROOT / "build/site")
    parser.add_argument("--refresh", action="store_true",
                        help="Replace only a generated guide, retaining its previous version beside it")
    args = parser.parse_args()
    try:
        build(instant(args.since), args.output, args.refresh)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"Documentation rejected: {error}", file=sys.stderr)
        sys.exit(1)
