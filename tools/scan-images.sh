#!/usr/bin/env bash
#
# Build the three PRP Bench image variants and scan them with grype, trivy and syft.
#
# Nothing is installed on the host: every scanner runs as a container, and grype/syft
# come from the Red Hat Hardened Images catalog themselves.
#
#   tools/scan-images.sh                 # build + scan legacy and hardened
#   LW_USER=... LW_TOKEN=... tools/scan-images.sh    # also build + scan hardened-lightwell
#   LW_REPO=remediated LW_USER=... LW_TOKEN=... tools/scan-images.sh
#   tools/scan-images.sh --no-build      # scan images already present locally (used by CI)
#
# LW_USER is the service account username in Red Hat's "XXXXXXX|service-account-name"
# form (org ID, a pipe, then the account name shown in the console) — NOT the client-ID
# UUID. LW_TOKEN is the service account token. Using the UUID gets a 401 from
# packages.redhat.com even though the same UUID authenticates fine against sso.redhat.com.
#
# LW_REPO selects which Lightwell repository is active: validated (default), remediated
# or predisclosure. remediated only changes anything once pom.xml pins the .rhlw-0000X
# versions; pointing at it without pinning resolves ordinary artifacts.
#
# Output lands in target/scans/ (gitignored): per-image JSON, CycloneDX SBOMs, and a
# summary.md comparison table.
#
# The counts this produces are "what these scanners reported on this date with this
# database version" — not a property of the image. Record the date and DB version with
# any number you quote. See docs/demo-supply-chain.md.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${REPO_ROOT}/target/scans"
CACHE="${OUT}/cache"
IMAGE_NAME="${IMAGE_NAME:-prp-bench}"
LW_REPO="${LW_REPO:-validated}"

GRYPE_IMAGE="registry.access.redhat.com/hi/grype:latest"
SYFT_IMAGE="registry.access.redhat.com/hi/syft:latest"
TRIVY_IMAGE="docker.io/aquasec/trivy:latest"

BUILD=1
[[ "${1:-}" == "--no-build" ]] && BUILD=0

log() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33mwarning: %s\033[0m\n' "$*" >&2; }

mkdir -p "${OUT}" "${CACHE}/grype" "${CACHE}/trivy"

# The rendered Lightwell settings file holds a live service-account token. Remove it on
# any exit path, not just the happy one — set -e would otherwise leave it behind when a
# build fails.
SETTINGS="${REPO_ROOT}/target/maven-settings-lightwell.xml"
cleanup() { rm -f "${SETTINGS}"; }
trap cleanup EXIT

# ---------------------------------------------------------------- which variants?

VARIANTS=(legacy hardened)

if [[ -n "${LW_USER:-}" && -n "${LW_TOKEN:-}" ]]; then
  case "${LW_REPO}" in
    validated|remediated|predisclosure) ;;
    *) echo "LW_REPO must be validated, remediated or predisclosure (got '${LW_REPO}')" >&2
       exit 2 ;;
  esac
  # The console shows the username as "<org-id>|<name>". A bare UUID is the client ID and
  # will 401, so catch the most likely mistake before spending a build on it.
  if [[ "${LW_USER}" != *"|"* ]]; then
    warn "LW_USER does not contain '|'. Lightwell expects \"XXXXXXX|service-account-name\","
    warn "not the client-ID UUID. Expect a 401 from packages.redhat.com."
  fi
  VARIANTS+=(hardened-lightwell)
else
  warn "LW_USER/LW_TOKEN not set — skipping the hardened-lightwell variant."
  warn "Lightwell Network repositories require a service account; they 401 anonymously."
fi

# ---------------------------------------------------------------- build

if [[ "${BUILD}" == "1" ]]; then
  for v in "${VARIANTS[@]}"; do
    log "Building ${IMAGE_NAME}:${v}"
    case "${v}" in
      legacy)
        podman build -t "${IMAGE_NAME}:legacy" -f "${REPO_ROOT}/Containerfile" "${REPO_ROOT}"
        ;;
      hardened)
        podman build -t "${IMAGE_NAME}:hardened" -f "${REPO_ROOT}/Containerfile.hardened" "${REPO_ROOT}"
        ;;
      hardened-lightwell)
        # Render the template with the service-account credentials. Written with a
        # restrictive umask and under target/, which is gitignored. Removed by the
        # EXIT trap above.
        log "Lightwell repository: ${LW_REPO}"
        # Substituted in python, not sed: LW_USER is "<org-id>|<name>" and the pipe is
        # sed's delimiter here, so the expansion splits the s|||g expression and sed
        # fails with "unknown option to `s'". Credentials are passed through the
        # environment rather than argv so they never appear in the process list, and
        # str.replace treats them as literal text whatever characters they contain.
        ( umask 077
          LW_ACTIVE_PROFILE="lightwell-${LW_REPO}" \
          python3 -I -c '
import os, sys
src, dst = sys.argv[1], sys.argv[2]
with open(src, encoding="utf-8") as fh:
    text = fh.read()
for key in ("LW_USER", "LW_TOKEN", "LW_ACTIVE_PROFILE"):
    text = text.replace("__%s__" % key, os.environ[key])
with open(dst, "w", encoding="utf-8") as fh:
    fh.write(text)
' "${REPO_ROOT}/tools/maven-settings-lightwell.xml.template" "${SETTINGS}" )

        # Fail here rather than four minutes into a Maven run. Tokens are JWTs and can
        # contain "--", which is illegal inside an XML comment, so a stray placeholder
        # mention in the template renders a file no parser will accept.
        if ! python3 -I -c 'import sys,xml.etree.ElementTree as ET; ET.parse(sys.argv[1])' \
             "${SETTINGS}" 2>/dev/null; then
          echo "rendered ${SETTINGS} is not well-formed XML" >&2
          echo "check tools/maven-settings-lightwell.xml.template for placeholder names" >&2
          echo "appearing outside the <servers>/<activeProfiles> elements" >&2
          exit 1
        fi
        podman build -t "${IMAGE_NAME}:hardened-lightwell" \
          -f "${REPO_ROOT}/Containerfile.lightwell" \
          --secret "id=lightwell-settings,src=${SETTINGS}" \
          "${REPO_ROOT}" 2>&1 | tee "${OUT}/build-lightwell.log"
        # How much actually came from Lightwell? Maven logs "Downloading from X" when it
        # *tries* a repository and "Downloaded from X" only on success, then silently
        # falls back to the next repository. Grepping for the hostname alone counts the
        # failed attempts too and badly overstates coverage — a real build here logged
        # 969 Lightwell attempts for 116 successes. Count successes, and count jars
        # separately: poms are resolution metadata, jars are what lands in the uber-jar
        # and therefore what a scanner sees.
        lw_jars=$(grep -c 'Downloaded from lightwell[^:]*:.*\.jar' "${OUT}/build-lightwell.log" || true)
        central_jars=$(grep -c 'Downloaded from central:.*\.jar' "${OUT}/build-lightwell.log" || true)
        total_jars=$(( lw_jars + central_jars ))
        if (( lw_jars > 0 )); then
          log "Lightwell supplied ${lw_jars}/${total_jars} jars (${central_jars} from Central)"
          warn "Report that ratio, not 'built with Lightwell'. The rest came from Central."
        else
          warn "Zero jars resolved from Lightwell — this build is Maven Central only."
          warn "Do not present it as a Lightwell result."
        fi
        ;;
    esac
  done
fi

# ---------------------------------------------------------------- export + scan

for v in "${VARIANTS[@]}"; do
  ref="${IMAGE_NAME}:${v}"

  if ! podman image exists "${ref}"; then
    warn "${ref} not present locally — skipping."
    continue
  fi

  # docker-archive, not oci-archive: trivy --input rejects an OCI archive tar (it wants a
  # docker archive or an unpacked OCI directory), while grype and syft read both.
  # One export format keeps all three scanners looking at the same bytes.
  log "Exporting ${ref}"
  # podman save refuses to write into an existing docker-archive.
  rm -f "${OUT}/${v}.tar"
  podman save --format docker-archive -o "${OUT}/${v}.tar" "${ref}"

  # The hardened scanners run as UID 65532, which maps to a subuid that cannot write
  # to a host directory owned by the invoking user. ":U" chowns the cache volume to the
  # container user; the workspace only needs to be readable, so it is mounted read-only.
  log "grype ${ref}"
  podman run --rm \
    -v "${OUT}:/workspace:ro,Z" -v "${CACHE}/grype:/cache:U,Z" \
    -e GRYPE_DB_CACHE_DIR=/cache \
    "${GRYPE_IMAGE}" "docker-archive:/workspace/${v}.tar" -o json \
    > "${OUT}/${v}.grype.json"

  log "trivy ${ref}"
  podman run --rm \
    -v "${OUT}:/workspace:ro,Z" -v "${CACHE}/trivy:/cache:U,Z" \
    -e TRIVY_CACHE_DIR=/cache \
    "${TRIVY_IMAGE}" image --input "/workspace/${v}.tar" --format json --quiet --no-progress \
    > "${OUT}/${v}.trivy.json"

  log "syft SBOM ${ref}"
  podman run --rm \
    -v "${OUT}:/workspace:ro,Z" \
    "${SYFT_IMAGE}" "docker-archive:/workspace/${v}.tar" -o cyclonedx-json \
    > "${OUT}/${v}.sbom.cdx.json"
done

# ---------------------------------------------------------------- summarise

log "Writing ${OUT}/summary.md"

python3 - "${OUT}" "${IMAGE_NAME}" "${VARIANTS[@]}" <<'PY'
import json, os, subprocess, sys

out, image_name, *variants = sys.argv[1:]

def load(path):
    try:
        with open(path) as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None

def grype_counts(doc):
    """Severity counts, plus a split by artifact type.

    The OS/application split is the whole point of the comparison: a hardened base
    image can only move the rpm findings. The java-archive findings come from the
    uber-jar and are unchanged by the base image — that is the gap Lightwell
    Network addresses.
    """
    sev, kind = {}, {"os": 0, "app": 0}
    for m in (doc or {}).get("matches", []):
        s = m.get("vulnerability", {}).get("severity", "Unknown")
        sev[s] = sev.get(s, 0) + 1
        t = m.get("artifact", {}).get("type", "")
        kind["os" if t == "rpm" else "app"] += 1
    return sev, kind

def trivy_counts(doc):
    sev = {}
    for res in (doc or {}).get("Results", []) or []:
        for v in res.get("Vulnerabilities", []) or []:
            s = (v.get("Severity") or "UNKNOWN").title()
            sev[s] = sev.get(s, 0) + 1
    return sev

def podman(*args):
    try:
        return subprocess.run(["podman", *args], capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""

rows = []
for v in variants:
    g, gkind = grype_counts(load(os.path.join(out, f"{v}.grype.json")))
    t = trivy_counts(load(os.path.join(out, f"{v}.trivy.json")))
    sbom = load(os.path.join(out, f"{v}.sbom.cdx.json")) or {}
    pkgs = len(sbom.get("components", []) or [])

    ref = f"{image_name}:{v}"
    size = podman("image", "inspect", ref, "--format", "{{.Size}}")
    size_mb = f"{int(size)/1e6:.0f} MB" if size.isdigit() else "n/a"
    layers = podman("image", "inspect", ref, "--format", "{{len .RootFS.Layers}}") or "n/a"
    base = podman("image", "inspect", ref, "--format",
                  "{{index .Config.Labels \"org.opencontainers.image.base.name\"}}")

    rows.append({
        "variant": v, "size": size_mb, "layers": layers, "pkgs": pkgs,
        "g_total": sum(g.values()), "g_crit": g.get("Critical", 0), "g_high": g.get("High", 0),
        "g_os": gkind["os"], "g_app": gkind["app"],
        "t_total": sum(t.values()), "t_crit": t.get("Critical", 0), "t_high": t.get("High", 0),
    })

db_ver = ""
first = load(os.path.join(out, f"{variants[0]}.grype.json")) if variants else None
if first:
    status = ((first.get("descriptor") or {}).get("db") or {}).get("status") or {}
    db_ver = status.get("built") or status.get("schemaVersion", "")

lines = [
    "# Image scan comparison",
    "",
    f"Generated: {subprocess.run(['date','-u','+%Y-%m-%dT%H:%M:%SZ'], capture_output=True, text=True).stdout.strip()}",
]
if db_ver:
    lines.append(f"Grype vulnerability DB built: {db_ver}")
lines += [
    "",
    "These are scanner results at a point in time, not properties of the images.",
    "Quote them with the date and DB version attached.",
    "",
    "`grype OS` counts rpm findings — what the base image controls. `grype app` counts",
    "java-archive findings from the uber-jar — what the dependency source controls.",
    "",
    "| Variant | Size | Layers | SBOM pkgs | grype total | grype OS | grype app | crit | high | trivy total | trivy crit | trivy high |",
    "|---|---|---|---|---|---|---|---|---|---|---|---|",
]
for r in rows:
    lines.append(
        f"| `{r['variant']}` | {r['size']} | {r['layers']} | {r['pkgs']} | "
        f"{r['g_total']} | {r['g_os']} | {r['g_app']} | {r['g_crit']} | {r['g_high']} | "
        f"{r['t_total']} | {r['t_crit']} | {r['t_high']} |"
    )

with open(os.path.join(out, "summary.md"), "w") as fh:
    fh.write("\n".join(lines) + "\n")

print("\n".join(lines))
PY

log "Done. Artifacts in ${OUT}"
