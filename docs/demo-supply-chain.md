# Demo: Red Hat Hardened Images and Lightwell Network

**Audience:** platform, security and supply-chain conversations — the same Industrial Edge
accounts as the [PRP LAN failure demo](demo-prp-lan-failure.md), but a different room.
**Goal:** Show, on a real workload, that a hardened base image removes almost all OS-level
scanner findings — and that it provably does **not** touch your application dependencies,
which is the gap Lightwell Network fills.

PRP Bench is used here only as a realistic Quarkus workload. Nothing about the PRP
measurement behaviour changes between the image variants.

---

## Talk track (keep it short)

1. Same application, same uber-jar, three images. Only the **base image** and the
   **dependency source** differ.
2. Scan all three with whatever the customer already runs — grype, trivy, quay, ACS, Insights.
3. The hardened base collapses OS findings to near zero. **The Java findings do not move at
   all** — identical count, identical CVEs.
4. That residue is the honest handoff to Lightwell Network: your dependencies are yours, and
   no base image will ever fix them.
5. Trade-off, stated out loud: a smaller image is a less debuggable image. Say what you lose.

**Do not claim:** that a hardened base makes the application secure, that "zero CVEs" is a
guarantee rather than Red Hat's stated goal for the base layer, or that any number below is
true today. Scanner counts are a function of the database, and the database moves daily.

---

## The three variants

| Tag | Builder | Runtime base | Dependencies from | Isolates |
|---|---|---|---|---|
| `:legacy` | `ubi8/openjdk-17:1.20` | `ubi8/openjdk-17-runtime:1.20` | Maven Central | the "before" |
| `:hardened` | `ubi9/openjdk-21:latest` | `hi/openjdk:21` | Maven Central | the **base image** effect |
| `:hardened-lightwell` | `ubi9/openjdk-21:latest` | `hi/openjdk:21` | Lightwell `validated` | the **dependency** effect |

Three rather than two on purpose. Going straight from `:legacy` to a hardened-plus-Lightwell
image confounds the two changes and neither number means anything on its own.

[Containerfile](../Containerfile) stays pinned on UBI8/JDK17 as the legacy artifact
and runs **`dnf update`** (the pre-Hardened remediation habit). Do not bump its
base tag or JDK — that destroys the comparison. Hardened variants have no `dnf`;
they refresh by rebuilding against a newer `hi/openjdk:21` digest.

### Why the builder stage is still UBI

There is no `hi/maven` image, and hardened images ship no package manager, so the build
itself has to happen somewhere else. `registry.access.redhat.com/ubi9/openjdk-21` carries
Maven 3.9 and does the job. Only the runtime stage is hardened — which is the stage that
ships.

### Why Java 21

The hardened catalog has no JDK 17 tag; `hi/openjdk` publishes `21`, `25` and `21-fips`.
Bytecode compiled at release 17 runs unchanged on a 21 JVM, so [pom.xml](../pom.xml) is not
modified and all three images contain the same application classes.

---

## Prerequisites

- `podman`. Nothing else — the scanners all run as containers, and grype, syft and cosign
  are themselves pulled from the hardened catalog.
- Network access to `registry.access.redhat.com`. **No subscription and no login**: the
  hardened images are published unauthenticated.
- For the third variant only: a Lightwell Network service account. The repositories return
  HTTP 401 anonymously, so without credentials the demo runs with two variants.

---

## Steps

### 1. Build and scan

```bash
# Two variants, no credentials needed
tools/scan-images.sh

# All three
LW_USER='<org>|<service-account>' LW_TOKEN='<token>' tools/scan-images.sh
```

The script builds each variant, exports it as a docker archive, and runs grype, trivy and a
syft CycloneDX SBOM over each. Everything lands in `target/scans/` (gitignored), with the
comparison in `target/scans/summary.md`.

### 2. Read the table

Measured on this laptop on **2026-10-20**, grype vulnerability DB built **2026-10-20T06:29:49Z**:

| Variant | Size | Layers | SBOM pkgs | grype total | grype OS | grype app | crit | high |
|---|---|---|---|---|---|---|---|---|
| `legacy` | 384 MB | 3 | 6595 | 779 | 707 | 72 | 1 | 114 |
| `hardened` | 312 MB | 33 | 1972 | 72 | 0 | 72 | 1 | 33 |
| `hardened-lightwell` | 312 MB | 33 | 1972 | 72 | 0 | 72 | 1 | 33 |

Trivy agreed on the same images (778 and 72 total): two independent scanners, same conclusion.

**The line to land is the `grype OS` / `grype app` split:**

- OS findings: **707 → 0**. That is what the hardened base image did.
- Application findings: **72 → 72**. Not a reduction — the *same* findings, from the same
  uber-jar. The base image cannot touch them and does not pretend to.

A worked example of why these numbers carry a date: the same images four days earlier
(DB built 2026-10-16) scored 759/688/71 and 73/2/71. Nothing about the images changed —
only the database did. The hardened image's OS findings went 2 → 0 and the legacy image's
went 688 → 707 on their own. Re-run the script before every conversation; do not quote this
table.

Resist leading with image size. 384 MB → 312 MB is a real but unremarkable 19%, because a
full JDK plus a Quarkus uber-jar dominates both. The CVE split is the result; the size is a
footnote.

### 3. Hand off to Lightwell Network

Those 71 Java findings are the entire reason the third variant exists. They come from the
dependency tree, so the fix has to come from the dependency source.

```bash
LW_USER='<org>|<service-account>' LW_TOKEN='<token>' tools/scan-images.sh
```

[tools/maven-settings-lightwell.xml.template](../tools/maven-settings-lightwell.xml.template)
is rendered to `target/maven-settings-lightwell.xml` and passed to the build as a
`--mount=type=secret`, so the token never lands in an image layer. The rendered file is
deleted after the build and is gitignored. Verify both:

```bash
podman history prp-bench:hardened-lightwell | grep -i lightwell   # expect nothing
git status --porcelain | grep settings                            # expect nothing
```

#### What actually resolved — measured

The build succeeds and Lightwell really does serve this dependency tree, but only partly:

| Source | jars | poms |
|---|---|---|
| Lightwell `validated` | **41** | 75 |
| Maven Central (fallback) | 330 | 522 |

So about **11% of the jars** in the uber-jar came from Lightwell — `smallrye-common-*`,
`commons-codec` and similar. The rest fell back to Central because Lightwell does not carry
them at these coordinates. Maven attempted Lightwell 969 times and succeeded 116 times; it
logs `Downloading from` on every attempt and `Downloaded from` only on success, then falls
back silently.

That distinction matters enough that the script counts it: grepping the log for
`packages.redhat.com` alone matches the 969 *attempts* and would have reported near-total
coverage for what is actually 11%. The script now reports the jar ratio and only claims
success when jars, not poms, came from Lightwell.

**And the scan result is identical to plain `:hardened`** — 72 findings, same SBOM, same
size. That is the correct outcome, not a failure: `validated` ships *securely rebuilt*
artifacts at the *same versions*, so a scanner matching on coordinates reports the same
CVEs. The value of `validated` is provenance and reproducibility, not CVE reduction. If you
want the finding count to move, that is what `remediated` and its `.rhlw-*` version pins are
for.

The honest framing: "the hardened base eliminated the OS findings; Lightwell `validated`
gave us verified provenance for part of the dependency tree at identical versions; closing
the remaining 72 application findings is a `remediated` exercise we have not done yet."

**Credential format.** `LW_USER` is the service account username in Red Hat's
`XXXXXXX|service-account-name` form — the org ID, a pipe, then the account name as shown
in the console. It is **not** the client-ID UUID. This is worth stating because the UUID
and secret authenticate perfectly well against `sso.redhat.com` (a client-credentials
exchange returns an access token), while `packages.redhat.com` answers
`401 … invalid token` — so the obvious diagnosis, "my credentials are wrong", is false and
sends you down the wrong path. The script warns if `LW_USER` contains no `|`.

**Which repository.** Select with `LW_REPO`; the default is `validated`:

| `LW_REPO` | Contents | Cost to adopt |
|---|---|---|
| `validated` *(default)* | Securely rebuilt, reproducible, SLSA L3 provenance. **Not patched.** | A URL change, no code change |
| `remediated` | The CVE fixes | Requires pinning `.rhlw-0000X` versions in `pom.xml` |
| `predisclosure` | Fixes not yet public or not yet accepted upstream | As above; not on all membership tiers |

```bash
LW_REPO=remediated LW_USER='<org-id>|<service-account-name>' LW_TOKEN='<token>' tools/scan-images.sh
```

All three repositories are declared in the template with matching `<server>` entries —
Maven matches credentials to a repository by `id`, so a `<repository>` without a
same-`id` `<server>` is requested anonymously and gets a 401. Only the `<activeProfile>`
changes between runs.

Pointing at `remediated` **without** pinning versions resolves ordinary artifacts and
changes nothing, so expect the Java finding count to stay at 71 until `pom.xml` carries
the `.rhlw-*` versions. Move there once a scan shows a specific dependency worth fixing.
Do not describe `validated` as patched.

### 4. Scan somewhere the customer already looks

The same images, read by whatever is already in their pipeline:

```bash
# Quay: push and let Quay's own scanner report
podman tag prp-bench:hardened quay.io/rhn_support_arolivei/prp-bench:hardened
podman push quay.io/rhn_support_arolivei/prp-bench:hardened

# Pull Quay Clair into the same summary as local grype/trivy (public API, no token)
tools/quay-scan-report.sh
# → target/scans/quay-summary.md  and  quay-<tag>.json

# Advanced Cluster Security
roxctl image scan --image quay.io/rhn_support_arolivei/prp-bench:hardened

# Red Hat Insights picks up images running in a connected cluster
```

The CI workflow runs `tools/quay-scan-report.sh` after push and pastes the Quay table
into the Actions run summary. The live tags page —
[quay.io/repository/rhn_support_arolivei/prp-bench?tab=tags](https://quay.io/repository/rhn_support_arolivei/prp-bench?tab=tags)
— is usually the most persuasive single screenshot, because it is the customer's own
tooling drawing the conclusion.

#### Let CI do the pushing

[.github/workflows/build-push-scan.yml](../.github/workflows/build-push-scan.yml) runs
**Build → Push → Scan → Report** on a self-hosted runner labeled `prp-bench`. It pushes
to `quay.io/rhn_support_arolivei/prp-bench` on every merge to `main`, on a **daily**
schedule, and on manual dispatch. Pull requests build/scan/report but never push.

One-time setup:

1. **Self-hosted runner on this workstation.**
   ```bash
   cd ~/actions-runner/prp-bench
   gh auth login -h github.com -p https   # once; needs repo admin
   ./register-prp-bench.sh
   nohup ./run.sh >runner.log 2>&1 &
   ```
2. **Quay credentials (never commit).** Either:
   - keep an interactive `podman login quay.io -u rhn_support_arolivei` on the host, or
   - set Actions secrets `QUAY_USERNAME` / `QUAY_TOKEN` (prefer a Quay **robot** account
     scoped to write on `prp-bench`).
3. **Lightwell (optional, third variant).** Either Actions secrets `LIGHTWELL_USER` /
   `LIGHTWELL_TOKEN`, or a mode-`600` file at `~/.config/prp-bench/ci.env` exporting
   `LW_USER` / `LW_TOKEN`. Do not put that file in the git repo.
4. **Make the Quay repository public.** Quay creates repositories **private** on first
   push — set *Repository Settings → Visibility → Make Public* afterwards, or no
   external scanner can reach them.

Each variant gets a stable tag (`:hardened`) and an immutable `:hardened-<short-sha>`.
`:latest` is an alias of `:legacy` for `k8s/base`. Scan results never gate the push —
the legacy image is *supposed* to accumulate findings.

### 5. Verify the base image is what it claims to be

```bash
# Signature, against the Red Hat public key
podman run --rm registry.access.redhat.com/hi/cosign verify \
  --key https://security.access.redhat.com/data/63405576.txt \
  registry.access.redhat.com/hi/openjdk:21

# SBOM straight from the catalog, no local scan involved
# https://images.redhat.com -> openjdk -> SBOM tab
```

Red Hat publishes an SBOM and build provenance per tag. Scanning hardened images *with*
hardened scanners (`hi/grype`, `hi/syft`, `hi/cosign`) is a small closing flourish.

### 6. Runtime posture

Both variants already run non-root and tolerate a read-only root filesystem — verified:

```bash
podman run --rm --read-only --cap-drop=ALL --security-opt=no-new-privileges \
  -e PRP_BENCH_MODE=RECEIVER -e PRP_BENCH_BIND_ADDRESS=0.0.0.0 -p 18080:8080 \
  prp-bench:hardened
curl -s localhost:18080/api/stats
```

On a cluster, `k8s/overlays/hardened` runs the `:hardened` image with that same
securityContext:

```bash
oc kustomize k8s/overlays/hardened      # preview
oc apply -k k8s/overlays/hardened
```

`:legacy` defaults to UID 185, `:hardened` to UID 65532, and **both** start cleanly with
`--read-only --cap-drop=ALL`. So do not sell the securityContext as a hardened-image win here
— on this workload it is not one. The application only ever reads `/proc/net/snmp` and
`/sys/class/net`, so it was already compatible. Say so; it costs nothing and buys credibility
for the claims that do hold.

---

## The trade-off, stated honestly

The README's [15× throughput regression](../README.md#case-study-finding-a-15x-throughput-regression)
was diagnosed with `jcmd` inside a running pod. That mattered enough that the UBI runtime
image was chosen partly for it. So check what the hardened image costs you:

```bash
podman exec <container> jcmd 1 VM.version      # works
podman exec <container> /bin/sh -c 'echo hi'   # also works
```

Both work. **`hi/openjdk:21` is not fully distroless** — it ships `bash` and `sh`, about 210
binaries in `/usr/bin` against the legacy image's 235, and `jcmd` is present because the image
is a full JDK rather than a JRE. What it drops is the package manager: no `dnf`, no `microdnf`,
no `rpm`, no `curl`.

That is a meaningful reduction, and it is also much less dramatic than "distroless" implies.
Quote the 688 → 2 number, not a shell-removal story the image does not support. Other images
in the hardened catalog are far more minimal; `openjdk` is not, and claiming otherwise in a
room with someone who will `podman run --entrypoint /bin/sh` is an avoidable own goal.

---

## Repeating the measurement

Numbers in this file are from one laptop on one date with one database version. Re-run
`tools/scan-images.sh` before any customer conversation and use what it prints, not what is
written above. The CI workflow
([.github/workflows/build-push-scan.yml](../.github/workflows/build-push-scan.yml)) does the same thing
daily and publishes the table to the run summary, which is the easiest way to get a current
screenshot without a local build.
