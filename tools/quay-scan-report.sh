#!/usr/bin/env bash
#
# Pull Quay Security Scanner (Clair) results for the published PRP Bench tags and
# write a markdown comparison next to the local grype/trivy summary.
#
# The repo is public, so no credentials are required. After a fresh push Quay may
# still be scanning — this script polls until status is "scanned" or the timeout
# expires, then records whatever status it got.
#
#   tools/quay-scan-report.sh
#   QUAY_TAGS="legacy hardened" tools/quay-scan-report.sh
#   QUAY_POLL_SECONDS=300 tools/quay-scan-report.sh
#
# Output (gitignored under target/scans/):
#   quay-<tag>.json   raw Clair payload per tag
#   quay-summary.md   comparison table for the Actions run summary
#
# Numbers are dated Quay observations, not image properties — quote with the
# fetch timestamp. See docs/demo-supply-chain.md.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${REPO_ROOT}/target/scans"
NAMESPACE="${QUAY_NAMESPACE:-rhn_support_arolivei}"
REPOSITORY="${QUAY_REPOSITORY:-prp-bench}"
REGISTRY_UI="https://quay.io/repository/${NAMESPACE}/${REPOSITORY}?tab=tags"
API="https://quay.io/api/v1/repository/${NAMESPACE}/${REPOSITORY}"

IFS=' ' read -r -a TAGS <<< "${QUAY_TAGS:-legacy legacy-lightwell hardened hardened-lightwell latest}"
POLL_SECONDS="${QUAY_POLL_SECONDS:-300}"
POLL_INTERVAL="${QUAY_POLL_INTERVAL:-15}"

mkdir -p "${OUT}"

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

log "Quay Security Scanner → ${REGISTRY_UI}"

# Export for the Python block (urllib, no curl dependency inside the poll loop).
export OUT API REGISTRY_UI NAMESPACE REPOSITORY POLL_SECONDS POLL_INTERVAL
export QUAY_TAGS_CSV
QUAY_TAGS_CSV="$(IFS=','; echo "${TAGS[*]}")"

python3 -I <<'PY'
import json, os, time, urllib.error, urllib.request
from collections import Counter
from datetime import datetime, timezone

out = os.environ["OUT"]
api = os.environ["API"]
ui = os.environ["REGISTRY_UI"]
namespace = os.environ["NAMESPACE"]
repository = os.environ["REPOSITORY"]
poll_s = int(os.environ["POLL_SECONDS"])
interval_s = int(os.environ["POLL_INTERVAL"])
tags = [t for t in os.environ["QUAY_TAGS_CSV"].split(",") if t]

def get_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "prp-bench-quay-scan-report"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)

def digest_for(tag: str) -> str:
    data = get_json(f"{api}/tag/?specificTag={tag}&onlyActiveTags=true")
    found = data.get("tags") or []
    return found[0]["manifest_digest"] if found else ""

def security(digest: str) -> dict:
    return get_json(f"{api}/manifest/{digest}/security?vulnerabilities=true")

def counts(doc: dict) -> dict:
    sev = Counter()
    layer = ((doc.get("data") or {}).get("Layer") or {})
    for feat in layer.get("Features") or []:
        for vuln in feat.get("Vulnerabilities") or []:
            sev[vuln.get("Severity") or "Unknown"] += 1
    return {
        "total": sum(sev.values()),
        "Critical": sev.get("Critical", 0),
        "High": sev.get("High", 0),
        "Medium": sev.get("Medium", 0),
        "Low": sev.get("Low", 0),
    }

rows = []
deadline = time.time() + poll_s
na = {k: "n/a" for k in ("total", "Critical", "High", "Medium", "Low")}

for tag in tags:
    try:
        digest = digest_for(tag)
    except urllib.error.HTTPError as exc:
        print(f"warning: tag {tag}: HTTP {exc.code}", flush=True)
        rows.append({"tag": tag, "status": f"http-{exc.code}", "digest": "", **na})
        continue

    if not digest:
        print(f"warning: tag {tag} not found on Quay", flush=True)
        rows.append({"tag": tag, "status": "missing", "digest": "", **na})
        continue

    doc = None
    status = "unknown"
    while True:
        try:
            doc = security(digest)
        except urllib.error.HTTPError as exc:
            status = f"http-{exc.code}"
            print(f"warning: {tag} security HTTP {exc.code}", flush=True)
            break
        status = doc.get("status") or "unknown"
        if status == "scanned" or time.time() >= deadline:
            break
        if status not in ("queued", "scanning"):
            break
        remaining = max(0, int(deadline - time.time()))
        print(f"{tag}: status={status}, waiting up to {remaining}s for Quay scan…", flush=True)
        time.sleep(interval_s)

    path = os.path.join(out, f"quay-{tag}.json")
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc or {"status": status, "digest": digest}, fh, indent=2)
        fh.write("\n")

    c = counts(doc) if doc and status == "scanned" else None
    row = {
        "tag": tag,
        "status": status,
        "digest": digest,
        "total": c["total"] if c else "n/a",
        "Critical": c["Critical"] if c else "n/a",
        "High": c["High"] if c else "n/a",
        "Medium": c["Medium"] if c else "n/a",
        "Low": c["Low"] if c else "n/a",
    }
    rows.append(row)
    print(
        f"{tag}: status={status} total={row['total']} "
        f"crit={row['Critical']} high={row['High']}",
        flush=True,
    )

fetched = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
lines = [
    "## Quay Security Scanner",
    "",
    f"Fetched: {fetched}",
    f"UI: [{namespace}/{repository} tags]({ui})",
    "",
    "Quay Clair results for the tags currently published. After a push Quay may",
    "still be scanning — `status` other than `scanned` means the counts were not",
    "ready before the poll timeout. These are dated observations; re-fetch rather",
    "than quoting forever.",
    "",
    "| Tag | Status | Critical | High | Medium | Low | Total | Digest |",
    "|---|---|---:|---:|---:|---:|---:|---|",
]
for r in rows:
    digest_short = (r["digest"][:19] + "…") if r["digest"] else "n/a"
    lines.append(
        f"| `{r['tag']}` | {r['status']} | {r['Critical']} | {r['High']} | "
        f"{r['Medium']} | {r['Low']} | {r['total']} | `{digest_short}` |"
    )
lines += [
    "",
    "Local grype/trivy numbers above and Quay Clair numbers here will not match",
    "exactly — different scanners and databases — but the OS→0 / app-residue shape",
    "should agree for the hardened variants.",
]

summary_path = os.path.join(out, "quay-summary.md")
text = "\n".join(lines) + "\n"
with open(summary_path, "w", encoding="utf-8") as fh:
    fh.write(text)

quay_json = {
    "fetched": fetched,
    "namespace": namespace,
    "repository": repository,
    "ui": ui,
    "tags": [
        {
            "tag": r["tag"],
            "status": r["status"],
            "digest": r["digest"],
            "total": r["total"],
            "critical": r["Critical"],
            "high": r["High"],
            "medium": r["Medium"],
            "low": r["Low"],
        }
        for r in rows
    ],
}
with open(os.path.join(out, "quay-summary.json"), "w", encoding="utf-8") as fh:
    json.dump(quay_json, fh, indent=2)
    fh.write("\n")

print(text, end="")
PY
