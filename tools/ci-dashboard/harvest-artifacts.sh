#!/usr/bin/env bash
#
# Download image-scans artifacts from successful Build Push Scan Report runs.
# Used to seed / refresh the published CI dashboard history.
#
#   tools/ci-dashboard/harvest-artifacts.sh [out-dir]
#
# Requires gh authenticated against the repo. Skips runs that already have a
# local directory. Does not fail the whole harvest if one download 404s.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${1:-${REPO_ROOT}/target/ci-dashboard-artifacts}"
WORKFLOW="${WORKFLOW_FILE:-build-push-scan.yml}"
OWNER_REPO="${GITHUB_REPOSITORY:-}"

if [[ -z "${OWNER_REPO}" ]]; then
  OWNER_REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
fi

mkdir -p "${OUT}"

log() { printf '\033[1m==> %s\033[0m\n' "$*"; }

log "Harvesting image-scans artifacts → ${OUT}"

mapfile -t RUN_IDS < <(
  gh api --paginate \
    "repos/${OWNER_REPO}/actions/workflows/${WORKFLOW}/runs?status=completed&per_page=50" \
    --jq '.workflow_runs[] | select(.conclusion=="success") | .id' \
    | sort -u
)

if [[ ${#RUN_IDS[@]} -eq 0 ]]; then
  echo "No successful runs found for ${WORKFLOW}" >&2
  exit 0
fi

stamp_meta() {
  local id="$1" dest="$2"
  gh api "repos/${OWNER_REPO}/actions/runs/${id}" \
    --jq '{run_id:.id,event:.event,sha:.head_sha,run_url:.html_url,created_at:.created_at,display_title:.display_title}' \
    > "${dest}/run-meta.json"
}

for id in "${RUN_IDS[@]}"; do
  dest="${OUT}/${id}"
  if [[ -f "${dest}/summary.md" || -f "${dest}/summary.json" ]]; then
    [[ -f "${dest}/run-meta.json" ]] || stamp_meta "${id}" "${dest}"
    echo "skip ${id} (already present)"
    continue
  fi
  mkdir -p "${dest}"
  if gh run download "${id}" -n image-scans -D "${dest}" --repo "${OWNER_REPO}"; then
    # Stamp run metadata for the builder (artifact itself has no run id).
    stamp_meta "${id}" "${dest}"
    echo "got ${id}"
  else
    echo "warn: no image-scans artifact for run ${id}" >&2
    rm -rf "${dest}"
  fi
done

log "Done (${#RUN_IDS[@]} successful runs considered)"
