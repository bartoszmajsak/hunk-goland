"""Publish the guide and allowlisted, fresh fixture captures; never copy raw reports."""

import argparse
from html import escape
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

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


def build(since, output):
    results = test_results(ROOT / "build/test-results/integrationTest", since)
    version, ide = versions()
    report = ROOT / "build/reports/ux"
    captures = select_captures(report, json.loads((ROOT / "docs/screenshots.json").read_text()), since)
    guide = (ROOT / "docs/user-guide.md").read_text()
    # The guide is tracked Markdown; rendering does not ingest logs, request bodies, or raw report HTML.
    content = subprocess.run(["pandoc", "--from=gfm", "--to=html5"], input=guide,
                             text=True, capture_output=True, check=True).stdout
    commit = source_commit()
    manifest = {"pluginVersion": version, "ideVersion": ide, "sourceCommit": commit,
                "testOutcome": "passed", "testResults": results,
                "verificationStartedAt": since.isoformat(), "screenshots": captures}
    require(not output.exists(), "Site output already exists; generate into a fresh directory")
    assets = output / "assets"
    assets.mkdir(parents=True)
    shutil.copyfile(ROOT / "docs/site.css", assets / "site.css")
    for capture in captures:
        shutil.copyfile(report / capture["file"], assets / capture["file"])
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    cards = "\n".join(
        f'<article><h3>{escape(card["title"])}</h3><p>{escape(card["description"])}</p>'
        f'<a href="assets/{card["file"]}"><img loading="lazy" src="assets/{card["file"]}" '
        f'alt="{escape(card["title"])}"></a></article>' for card in captures)
    html = f'''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Hunk Session: user guide</title><link rel="stylesheet" href="assets/site.css"></head>
<body><header><p class="eyebrow">Hunk Session for GoLand</p><h1>Review together, inside your editor.</h1>
<p>Setup, comments, agent responses, and navigation, illustrated by real GoLand tests.</p>
<p class="evidence">Plugin {escape(version)} · GoLand {escape(ide)} · fixture tests passed ·
commit <code>{escape(commit[:12])}</code> · <a href="manifest.json">capture manifest</a></p>
<nav><a href="#guide">User guide</a><a href="#features">Features in GoLand</a></nav></header>
<main><section id="guide">{content}</section><section id="features"><h2>Features in GoLand</h2>
<p>These screenshots exercise the generated Go/Git fixture. Native Hunk semantics are verified
separately by the daemon and restart E2E gates.</p><div class="gallery">{cards}</div></section></main>
<footer>Hunk is installed separately. Screenshots are captured from the IDE, not UI mockups.</footer></body></html>'''
    (output / "index.html").write_text(html)
    snapshot = output / "versions" / version
    snapshot.mkdir(parents=True)
    (snapshot / "index.html").write_text(html.replace('href="assets/', 'href="../../assets/')
                                       .replace('src="assets/', 'src="../../assets/')
                                       .replace('href="manifest.json"', 'href="../../manifest.json"'))
    print(f"Built {len(captures)} verified feature captures at {output}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--since", required=True, help="UTC start time of the successful verification run")
    parser.add_argument("--output", type=Path, default=ROOT / "build/site")
    args = parser.parse_args()
    try:
        build(instant(args.since), args.output)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"Documentation rejected: {error}", file=sys.stderr)
        sys.exit(1)
