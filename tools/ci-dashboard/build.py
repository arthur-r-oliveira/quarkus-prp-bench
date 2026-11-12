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
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"

VARIANT_ORDER = ("legacy", "legacy-lightwell", "hardened", "hardened-lightwell")


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
        by_key[snapshot_key(snap)] = snap

    def sort_ts(s: dict[str, Any]) -> str:
        return s.get("generated") or s.get("created_at") or ""

    return sorted(by_key.values(), key=sort_ts)


def write_site(out: Path, history: list[dict[str, Any]]) -> None:
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    data = out / "data"
    data.mkdir()
    payload = {
        "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "disclaimer": (
            "Scanner counts are dated observations (grype/trivy DB + Quay Clair), "
            "not properties of the images. Quote with the generated timestamp and "
            "grype DB built time. Lightwell validated is provenance, not CVE patches."
        ),
        "variant_order": list(VARIANT_ORDER),
        "runs": history,
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

    write_site(args.out, history)
    print(f"Wrote {len(history)} runs → {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
