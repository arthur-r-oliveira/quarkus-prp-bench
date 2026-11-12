#!/usr/bin/env python3
"""Build the published CI scan dashboard from harvested + current scan dirs.

  tools/ci-dashboard/build.py \\
    --artifacts-root target/ci-dashboard-artifacts \\
    --current target/scans \\
    --out target/ci-dashboard-site \\
    --run-id 43008880498 --sha 6d486e2 --event schedule \\
    --run-url https://github.com/.../actions/runs/43008880498

Reads summary.json / quay-summary.json when present, otherwise parses the
legacy markdown tables so older artifacts still chart. History is keyed by
run_id when known, else by the scan Generated timestamp.

Also publishes per-run security report packs under data/runs/<id>/ (summaries
plus grype/trivy JSON) and links them from the dashboard for analysis.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"

VARIANT_ORDER = ("legacy", "legacy-lightwell", "hardened", "hardened-lightwell")
QUAY_TAGS_UI = "https://quay.io/repository/rhn_support_arolivei/prp-bench?tab=tags"

# Lightweight enough for Pages; skip SBOMs and docker-archive tars.
REPORT_COPY_NAMES = (
    "summary.md",
    "summary.json",
    "quay-summary.md",
    "quay-summary.json",
)
REPORT_COPY_GLOBS = (
    "*.grype.json",
    "*.trivy.json",
)


def parse_summary_md(text: str) -> dict[str, Any]:
    generated = None
    grype_db = None
    variants: list[dict[str, Any]] = []
    for line in text.splitlines():
        if line.startswith("Generated:"):
            generated = line.split(":", 1)[1].strip()
        elif line.startswith("Grype vulnerability DB built:"):
            grype_db = line.split(":", 1)[1].strip()
        elif line.startswith("| `"):
            cells = [c.strip().strip("`") for c in line.strip().strip("|").split("|")]
            if len(cells) < 12 or cells[0] == "Variant":
                continue
            variants.append(
                {
                    "variant": cells[0],
                    "size": cells[1],
                    "layers": _intish(cells[2]),
                    "sbom_pkgs": _intish(cells[3]),
                    "grype_total": _intish(cells[4]),
                    "grype_os": _intish(cells[5]),
                    "grype_app": _intish(cells[6]),
                    "grype_crit": _intish(cells[7]),
                    "grype_high": _intish(cells[8]),
                    "trivy_total": _intish(cells[9]),
                    "trivy_crit": _intish(cells[10]),
                    "trivy_high": _intish(cells[11]),
                }
            )
    return {
        "generated": generated,
        "grype_db_built": grype_db,
        "variants": variants,
    }


def parse_quay_md(text: str) -> dict[str, Any]:
    fetched = None
    tags: list[dict[str, Any]] = []
    for line in text.splitlines():
        if line.startswith("Fetched:"):
            fetched = line.split(":", 1)[1].strip()
        elif line.startswith("| `"):
            cells = [c.strip().strip("`") for c in line.strip().strip("|").split("|")]
            if len(cells) < 7 or cells[0] == "Tag":
                continue
            tags.append(
                {
                    "tag": cells[0],
                    "status": cells[1],
                    "critical": _intish(cells[2]),
                    "high": _intish(cells[3]),
                    "medium": _intish(cells[4]),
                    "low": _intish(cells[5]),
                    "total": _intish(cells[6]),
                    "digest": cells[7] if len(cells) > 7 else "",
                }
            )
    return {"fetched": fetched, "tags": tags}


def _intish(value: Any) -> Any:
    if value is None or value == "n/a":
        return None
    if isinstance(value, int):
        return value
    s = str(value).strip().rstrip("…")
    if re.fullmatch(r"-?\d+", s):
        return int(s)
    return value


def _load_json(path: Path) -> dict[str, Any] | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def run_slug(snap: dict[str, Any]) -> str:
    if snap.get("run_id") is not None:
        return str(snap["run_id"])
    gen = (snap.get("generated") or "unknown").replace(":", "").replace("Z", "")
    return f"gen-{gen}"


def default_report_links(snap: dict[str, Any]) -> dict[str, Any]:
    slug = run_slug(snap)
    base = f"data/runs/{slug}"
    links: dict[str, Any] = {
        "slug": slug,
        "dir": base,
        "summary_md": f"{base}/summary.md",
        "summary_json": f"{base}/summary.json",
        "quay_md": f"{base}/quay-summary.md",
        "quay_json": f"{base}/quay-summary.json",
        "index": f"{base}/index.html",
        "quay_tags": QUAY_TAGS_UI,
        "actions_run": snap.get("run_url"),
        "actions_artifacts": (
            f"{snap['run_url']}#artifacts" if snap.get("run_url") else None
        ),
        "files": [],
    }
    return links


def load_scan_dir(path: Path, overrides: dict[str, Any] | None = None) -> dict[str, Any] | None:
    summary = _load_json(path / "summary.json")
    if summary is None and (path / "summary.md").is_file():
        summary = parse_summary_md((path / "summary.md").read_text(encoding="utf-8"))
    if not summary or not summary.get("variants"):
        return None

    quay = _load_json(path / "quay-summary.json")
    if quay is None and (path / "quay-summary.md").is_file():
        quay = parse_quay_md((path / "quay-summary.md").read_text(encoding="utf-8"))

    meta = _load_json(path / "run-meta.json") or {}
    snap: dict[str, Any] = {
        "generated": summary.get("generated"),
        "grype_db_built": summary.get("grype_db_built"),
        "variants": summary.get("variants") or [],
        "quay": quay,
        "run_id": meta.get("run_id"),
        "run_url": meta.get("run_url"),
        "sha": (meta.get("sha") or "")[:7] or None,
        "event": meta.get("event"),
        "created_at": meta.get("created_at"),
        "display_title": meta.get("display_title"),
        "_source_dir": str(path.resolve()),
    }
    if overrides:
        for k, v in overrides.items():
            if v is not None and v != "":
                snap[k] = v
    if snap.get("sha"):
        snap["sha"] = str(snap["sha"])[:7]
    return snap


def snapshot_key(snap: dict[str, Any]) -> str:
    if snap.get("run_id") is not None:
        return f"run:{snap['run_id']}"
    return f"gen:{snap.get('generated') or snap.get('created_at') or id(snap)}"


def merge_history(snaps: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_key: dict[str, dict[str, Any]] = {}
    for snap in snaps:
        key = snapshot_key(snap)
        prev = by_key.get(key)
        if prev is None:
            by_key[key] = snap
            continue
        # Prefer the snap that still has a local source dir; keep older reports
        # links if the new one lacks them.
        merged = dict(prev)
        merged.update({k: v for k, v in snap.items() if v is not None})
        if snap.get("_source_dir"):
            merged["_source_dir"] = snap["_source_dir"]
        elif prev.get("_source_dir"):
            merged["_source_dir"] = prev["_source_dir"]
        if not merged.get("reports") and prev.get("reports"):
            merged["reports"] = prev["reports"]
        by_key[key] = merged

    def sort_ts(s: dict[str, Any]) -> str:
        return s.get("generated") or s.get("created_at") or ""

    return sorted(by_key.values(), key=sort_ts)


def _collect_report_files(source: Path) -> list[Path]:
    files: list[Path] = []
    for name in REPORT_COPY_NAMES:
        p = source / name
        if p.is_file():
            files.append(p)
    for pattern in REPORT_COPY_GLOBS:
        files.extend(sorted(source.glob(pattern)))
    # De-dupe while preserving order
    seen: set[Path] = set()
    out: list[Path] = []
    for f in files:
        rp = f.resolve()
        if rp in seen:
            continue
        seen.add(rp)
        out.append(f)
    return out


def _write_run_index(dest: Path, snap: dict[str, Any], filenames: list[str]) -> None:
    lines = [
        "<!DOCTYPE html>",
        '<html lang="en"><head><meta charset="utf-8"/>',
        f"<title>Scan reports · run {run_slug(snap)}</title>",
        "<style>body{font-family:system-ui,sans-serif;max-width:52rem;margin:2rem auto;padding:0 1rem;line-height:1.45}"
        "code,a{word-break:break-all} li{margin:0.35rem 0}</style>",
        "</head><body>",
        f"<h1>Security reports · run {run_slug(snap)}</h1>",
        f"<p>Generated: <code>{snap.get('generated') or '—'}</code> · "
        f"event <code>{snap.get('event') or '—'}</code> · "
        f"sha <code>{snap.get('sha') or '—'}</code></p>",
        "<p>These are dated scanner observations (grype / trivy / Quay Clair), "
        "not permanent image properties. Full SBOMs remain in the GitHub Actions "
        "<code>image-scans</code> artifact.</p>",
        "<h2>Files</h2><ul>",
    ]
    for name in filenames:
        lines.append(f'<li><a href="{name}"><code>{name}</code></a></li>')
    lines.append("</ul><h2>External</h2><ul>")
    if snap.get("run_url"):
        lines.append(
            f'<li><a href="{snap["run_url"]}">GitHub Actions run</a> '
            f'(download full <code>image-scans</code> artifact)</li>'
        )
    lines.append(f'<li><a href="{QUAY_TAGS_UI}">Quay Security / tags</a></li>')
    lines.append("</ul><p><a href=\"../../index.html\">← CI dashboard</a></p>")
    lines.append("</body></html>")
    (dest / "index.html").write_text("\n".join(lines) + "\n", encoding="utf-8")


def publish_run_reports(out: Path, snap: dict[str, Any]) -> dict[str, Any]:
    """Copy analysis files into data/runs/<slug>/ and return reports links."""
    links = default_report_links(snap)
    dest = out / links["dir"]
    dest.mkdir(parents=True, exist_ok=True)
    filenames: list[str] = []

    source = Path(snap["_source_dir"]) if snap.get("_source_dir") else None
    if source and source.is_dir():
        for src in _collect_report_files(source):
            shutil.copy2(src, dest / src.name)
            filenames.append(src.name)
    else:
        # Keep whatever was already published / listed on a prior history entry.
        prior = snap.get("reports") or {}
        for name in prior.get("files") or []:
            if (dest / name).is_file():
                filenames.append(name)

    # Prefer canonical names when present
    for name in REPORT_COPY_NAMES:
        if (dest / name).is_file() and name not in filenames:
            filenames.append(name)

    filenames = sorted(set(filenames), key=lambda n: (n not in REPORT_COPY_NAMES, n))
    _write_run_index(dest, snap, filenames)
    links["files"] = filenames
    if not (dest / "summary.md").is_file():
        links["summary_md"] = None
    if not (dest / "summary.json").is_file():
        links["summary_json"] = None
    if not (dest / "quay-summary.md").is_file():
        links["quay_md"] = None
    if not (dest / "quay-summary.json").is_file():
        links["quay_json"] = None
    return links


def recover_prior_reports(out: Path, snap: dict[str, Any], prior_site_url: str) -> bool:
    """Best-effort fetch of a previously published run pack from live Pages."""
    if not prior_site_url or snap.get("_source_dir"):
        return False
    prior_reports = snap.get("reports") or {}
    slug = prior_reports.get("slug") or run_slug(snap)
    files = list(prior_reports.get("files") or REPORT_COPY_NAMES)
    if "index.html" not in files:
        files.append("index.html")
    dest = out / "data" / "runs" / str(slug)
    dest.mkdir(parents=True, exist_ok=True)
    base = prior_site_url.rstrip("/") + f"/data/runs/{slug}"
    got = 0
    for name in files:
        url = f"{base}/{name}"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "prp-bench-ci-dashboard"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                (dest / name).write_bytes(resp.read())
            got += 1
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError):
            continue
    if got:
        snap["_recovered_dir"] = str(dest)
    return got > 0


def write_site(
    out: Path,
    history: list[dict[str, Any]],
    prior_site_url: str | None = None,
) -> None:
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    data = out / "data"
    data.mkdir()

    published: list[dict[str, Any]] = []
    for snap in history:
        clean = {k: v for k, v in snap.items() if not k.startswith("_")}
        if prior_site_url and not snap.get("_source_dir"):
            recover_prior_reports(out, snap, prior_site_url)
            if snap.get("_recovered_dir"):
                # Treat recovered files as source for index rewrite / link fill.
                snap["_source_dir"] = snap["_recovered_dir"]
        reports = publish_run_reports(out, snap)
        clean["reports"] = reports
        published.append(clean)

    payload = {
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "disclaimer": (
            "Scanner counts are dated observations (grype/trivy DB + Quay Clair), "
            "not properties of the images — quote with the generated timestamp and "
            "grype DB built time. Hardened Images move grype OS (rpm / base). "
            "Lightwell owns the uber-jar: validated = provenance (same CVE count as "
            "Central is expected); remediated + .rhlw-* pins = CVE backports. "
            "Identical *-lightwell rows on validated are correct, not a failed demo. "
            "Open each run's report pack (or Actions image-scans artifact) for raw JSON."
        ),
        "variant_order": list(VARIANT_ORDER),
        "quay_tags": QUAY_TAGS_UI,
        "runs": published,
    }
    (data / "history.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    for name in ("index.html", "app.js", "style.css"):
        shutil.copy2(STATIC / name, out / name)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--artifacts-root", type=Path, default=None)
    ap.add_argument("--current", type=Path, default=None)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--run-id", default=None)
    ap.add_argument("--run-url", default=None)
    ap.add_argument("--sha", default=None)
    ap.add_argument("--event", default=None)
    ap.add_argument("--history-in", type=Path, default=None,
                    help="Optional prior history.json to merge (e.g. from live Pages)")
    ap.add_argument("--prior-site-url", default=None,
                    help="Base URL of the live Pages site, to recover older report packs")
    args = ap.parse_args()

    snaps: list[dict[str, Any]] = []

    if args.history_in and args.history_in.is_file():
        prior = _load_json(args.history_in) or {}
        snaps.extend(prior.get("runs") or [])

    if args.artifacts_root and args.artifacts_root.is_dir():
        for child in sorted(args.artifacts_root.iterdir()):
            if not child.is_dir():
                continue
            loaded = load_scan_dir(child)
            if loaded:
                snaps.append(loaded)

    if args.current and args.current.is_dir():
        overrides = {
            "run_id": int(args.run_id) if args.run_id and str(args.run_id).isdigit() else args.run_id,
            "run_url": args.run_url,
            "sha": args.sha,
            "event": args.event,
        }
        loaded = load_scan_dir(args.current, overrides)
        if loaded:
            snaps.append(loaded)

    history = merge_history(snaps)
    if not history:
        raise SystemExit("no scan snapshots found — nothing to publish")

    write_site(args.out, history, prior_site_url=args.prior_site_url)
    print(f"Wrote {len(history)} runs → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
