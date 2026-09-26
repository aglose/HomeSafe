#!/usr/bin/env python3
"""Turns two renders of the UI previews into a gallery and a pull request section.

The "UI previews" workflow (.github/workflows/ui-previews.yml) draws every preview on the branch
and on its merge base with scripts/render-ui-previews.sh, then:

  ui_previews.py build HEAD_DIR BASE_DIR GALLERY_DIR
      Lays out what gets published to refs/previews/<branch>: the branch's images, the merge
      base's copy of each one that changed or went away (under base/), a README.md that GitHub
      shows as the gallery, and summary.json.

  ui_previews.py section GALLERY_DIR IMAGE_URL_BASE
      Prints the markdown that goes in the pull request's description: before/after for each
      preview that changed, the new ones, the removed ones. IMAGE_URL_BASE is where the
      published gallery's files can be fetched from (raw.githubusercontent.com/<repo>/<commit>).

  ui_previews.py splice BODY_FILE SECTION_FILE
      Prints BODY_FILE with the section put in place of the previous one, or appended.

A preview's identity is its file name; two renders of it are the same when the PNGs are byte
for byte the same, which they are from one renderer on one runner image. Standard library only.
See docs/ui-previews.md.
"""

import hashlib
import json
import os
import re
import shutil
import sys
from urllib.parse import quote

SETS = [
    ("android", "Android", "Layoutlib, through Compose Preview Screenshot Testing (`:androidApp`'s `@PreviewTest`s)"),
    ("desktop", "Desktop", "the JVM, through `:shared:renderPreviews` (every `@Preview` in `:shared`)"),
]
# Gallery only: not compared with the merge base and not in the pull request. The journeys run the
# real app over real HTTP, so a frame can differ from run to run with timing alone.
EXTRA_SETS = [
    ("journeys", "Journeys", "the JVM integration journeys with `-PjourneyScreens`: the whole app after each tap"),
]
START = "<!-- ui-previews:start -->"
END = "<!-- ui-previews:end -->"
MAX_ROWS = 30
THUMB_WIDTH = 220


def pngs(directory):
    if not os.path.isdir(directory):
        return {}
    return {
        name: hashlib.sha256(open(os.path.join(directory, name), "rb").read()).hexdigest()
        for name in sorted(os.listdir(directory))
        if name.endswith(".png")
    }


def display_name(file_name):
    """"HomeFeed · Home" from Layoutlib's HomeFeed_Home_1a2b3c4d_0.png (or com.….SharedPreviewScreenshotsKt.HomeFeed_…)."""
    name = file_name[: -len(".png")]
    if "Kt." in name:
        name = name.split("Kt.", 1)[1]
    name = re.sub(r"(_[0-9a-f]{8})+_\d+$", "", name)
    # "HomeFeed_Home, loading" (function, then the @Preview's name) reads better as "HomeFeed · Home, loading".
    function, _, preview = name.partition("_")
    return f"{function} · {preview}" if preview else function


def build(head_dir, base_dir, gallery):
    shutil.rmtree(gallery, ignore_errors=True)
    os.makedirs(gallery)
    summary = {}
    for key, _, _ in SETS:
        head, base = pngs(os.path.join(head_dir, key)), pngs(os.path.join(base_dir, key))
        compared = bool(base)
        changes = {"changed": [], "new": [], "removed": [], "unchanged": []}
        for name, digest in head.items():
            if name not in base:
                changes["new"].append(name)
            elif base[name] != digest:
                changes["changed"].append(name)
            else:
                changes["unchanged"].append(name)
        changes["removed"] = [name for name in base if name not in head]

        os.makedirs(os.path.join(gallery, key), exist_ok=True)
        for name in head:
            shutil.copy(os.path.join(head_dir, key, name), os.path.join(gallery, key, name))
        for name in changes["changed"] + changes["removed"]:
            os.makedirs(os.path.join(gallery, "base", key), exist_ok=True)
            shutil.copy(os.path.join(base_dir, key, name), os.path.join(gallery, "base", key, name))
        summary[key] = {"compared": compared, **changes}

    for key, _, _ in EXTRA_SETS:
        head = pngs(os.path.join(head_dir, key))
        if head:
            os.makedirs(os.path.join(gallery, key))
            for name in head:
                shutil.copy(os.path.join(head_dir, key, name), os.path.join(gallery, key, name))
        summary[key] = {"screens": list(head)}

    meta = {name: os.environ.get(variable, "") for name, variable in [
        ("repo", "GITHUB_REPOSITORY"), ("branch", "PREVIEW_BRANCH"), ("head", "PREVIEW_HEAD_SHA"),
        ("base", "PREVIEW_BASE_SHA"), ("base_ref", "PREVIEW_BASE_REF"), ("run", "PREVIEW_RUN_URL"),
    ]}
    meta["failed"] = os.environ.get("PREVIEW_HEAD_FAILED") == "true"
    summary["meta"] = meta
    with open(os.path.join(gallery, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)
    with open(os.path.join(gallery, "README.md"), "w") as f:
        f.write(readme(summary))


def short(sha):
    return sha[:7] if sha else "?"


def counts(changes):
    return " · ".join(f"{len(changes[k])} {k}" for k in ("changed", "new", "removed", "unchanged"))


def readme(summary):
    meta = summary["meta"]
    lines = [
        f"# UI previews of `{meta['branch']}` at `{short(meta['head'])}`",
        "",
        f"Compared with `{meta['base_ref']}` at `{short(meta['base'])}`, their merge base. "
        f"Drawn by [the UI previews workflow]({meta['run']}); see docs/ui-previews.md.",
        "",
    ]
    if meta["failed"]:
        lines += ["> [!WARNING]", "> A renderer failed on this commit, so some previews may be missing. See the workflow run.", ""]
    for key, title, how in SETS:
        changes = summary[key]
        lines += [f"## {title}", "", f"Drawn by {how}. {counts(changes)}.", ""]
        order = [("changed", "changed"), ("new", "new"), ("unchanged", "")]
        for bucket, label in order:
            for name in changes[bucket]:
                tag = f" ({label})" if label else ""
                lines += [f"### {display_name(name)}{tag}", ""]
                if bucket == "changed":
                    lines += [f"| Before | After |", "|---|---|",
                              f"| <img src=\"{quote(f'base/{key}/{name}')}\" width=\"320\"> | <img src=\"{quote(f'{key}/{name}')}\" width=\"320\"> |", ""]
                else:
                    lines += [f"<img src=\"{quote(f'{key}/{name}')}\" width=\"320\">", ""]
        for name in changes["removed"]:
            lines += [f"### {display_name(name)} (removed)", "", f"<img src=\"{quote(f'base/{key}/{name}')}\" width=\"320\">", ""]
    for key, title, how in EXTRA_SETS:
        screens = summary[key]["screens"]
        if not screens:
            continue
        lines += [f"## {title}", "", f"Drawn by {how}. {len(screens)} screens.", ""]
        journey = None
        for name in screens:
            this_journey, _, step = name[: -len(".png")].partition("__")
            if this_journey != journey:
                journey = this_journey
                lines += [f"### {journey}", ""]
            lines += [f"<img src=\"{quote(f'{key}/{name}')}\" width=\"320\" title=\"{step}\"> "]
        lines.append("")
    return "\n".join(lines)


def section(gallery, image_url_base):
    summary = json.load(open(os.path.join(gallery, "summary.json")))
    meta = summary["meta"]
    url = lambda path: f"{image_url_base.rstrip('/')}/{quote(path)}"
    img = lambda path: f"<img src=\"{url(path)}\" width=\"{THUMB_WIDTH}\">"
    tree = image_url_base.replace("https://raw.githubusercontent.com/", "https://github.com/").rstrip("/")
    # raw.githubusercontent.com/<owner>/<repo>/<commit> -> github.com/<owner>/<repo>/tree/<commit>
    owner_repo, _, commit = tree.rpartition("/")
    gallery_link = f"{owner_repo}/tree/{commit}"

    out = [
        START,
        "## UI previews",
        "",
        f"<sub>`{short(meta['head'])}` compared with `{meta['base_ref']}` at `{short(meta['base'])}` · "
        f"[every preview]({gallery_link}) · [run]({meta['run']}) · updated on each push</sub>",
        "",
    ]
    if meta["failed"]:
        out += ["> [!WARNING]", "> A renderer failed on this commit, so some previews may be missing. See the run.", ""]
    for index, (key, title, how) in enumerate(SETS):
        changes = summary[key]
        body = []
        if not changes["compared"]:
            body += [f"_Nothing to compare with: the merge base has no {title.lower()} renders. Every preview is shown as new._", ""]
        rows = (
            [(n, "changed", img(f"base/{key}/{n}"), img(f"{key}/{n}")) for n in changes["changed"]]
            + [(n, "new", "", img(f"{key}/{n}")) for n in changes["new"]]
            + [(n, "removed", img(f"base/{key}/{n}"), "") for n in changes["removed"]]
        )
        if rows:
            body += ["| Preview | Before | After |", "|---|---|---|"]
            body += [f"| **{display_name(n)}**<br><sub>{what}</sub> | {before} | {after} |" for n, what, before, after in rows[:MAX_ROWS]]
            if len(rows) > MAX_ROWS:
                body += ["", f"…and {len(rows) - MAX_ROWS} more in [the gallery]({gallery_link})."]
        else:
            body += ["_No preview changed._"]
        if index == 0:
            out += [f"**{title}** — {how}: {counts(changes)}", ""] + body + [""]
        else:
            # Markdown doesn't render inside <summary>, so this one is spelled in HTML.
            how_html = re.sub(r"`([^`]*)`", r"<code>\1</code>", how)
            out += ["<details>", f"<summary><b>{title}</b> — {how_html}: {counts(changes)}</summary>", ""] + body + ["", "</details>", ""]
    out.append(END)
    return "\n".join(out)


def splice(body, new_section):
    body = body.replace("\r\n", "\n")
    pattern = re.compile(re.escape(START) + r".*?" + re.escape(END), re.S)
    if pattern.search(body):
        return pattern.sub(lambda _: new_section, body)
    return body.rstrip("\n") + ("\n\n" if body.strip() else "") + new_section + "\n"


def main(argv):
    if len(argv) == 4 and argv[0] == "build":
        build(argv[1], argv[2], argv[3])
    elif len(argv) == 3 and argv[0] == "section":
        print(section(argv[1], argv[2]))
    elif len(argv) == 3 and argv[0] == "splice":
        print(splice(open(argv[1]).read(), open(argv[2]).read().rstrip("\n")), end="")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
