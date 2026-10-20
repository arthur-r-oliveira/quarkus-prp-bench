# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

PRP Bench is a Quarkus + Vert.x UDP load generator and measurement tool that runs in two roles
(sender / receiver) over a host Parallel Redundancy Protocol netdev (`prp0`) on OpenShift SNO
nodes. It measures what OT cares about — zero loss, p99.9 latency, jitter — and reads kernel
counters so a dead redundant LAN is visible even when the application looks perfect.

The README is a deliverable, not just docs: it carries the customer narrative, the measured
results, and a "lessons learned" list. Changes that invalidate a documented number or claim
should update the README in the same commit.

## Build, run, test

```bash
mvn quarkus:dev                     # dev mode, :8080, hot reload
mvn package                         # JVM jar -> target/quarkus-app/
mvn package -Dnative                # native image
mvn test                            # no tests exist yet (no src/test); surefire is configured
mvn test -Dtest=ClassName#method    # single test, if you add one

podman build -t quay.io/rhn_support_arolivei/prp-bench:latest -f Containerfile .
```

There is no Maven wrapper, and `mvn` is not necessarily on PATH in this environment — the
`Containerfile` does a two-stage build (`ubi8/openjdk-17` builder producing an uber-jar, then
`ubi8/openjdk-17-runtime`). Note that `ubi8/openjdk-17-runtime` **does** ship `jcmd`, which is
how the receive-path regression documented in the README was diagnosed in-pod.

### Three image variants — `Containerfile` is frozen

The same application builds three ways for the supply-chain demo
(`docs/demo-supply-chain.md`):

| Tag | File | Runtime base | Deps |
|---|---|---|---|
| `:legacy` | `Containerfile` | `ubi8/openjdk-17-runtime:1.20` | Maven Central |
| `:hardened` | `Containerfile.hardened` | `hi/openjdk:21` | Maven Central |
| `:hardened-lightwell` | `Containerfile.lightwell` | `hi/openjdk:21` | Lightwell `validated` |

- **`Containerfile` must not be modernised.** It is the control in a measured comparison.
  Bumping its base, its JDK or its pinned `1.20` tag silently invalidates every number in
  the README's image-variant table. Changes go in the `.hardened` / `.lightwell` files.
- **The builder stage stays on UBI.** There is no `hi/maven`, and hardened images ship no
  package manager, so `RUN` is impossible in a hardened stage. `ubi9/openjdk-21` has Maven 3.9.
- **The hardened catalog has no JDK 17** (`hi/openjdk` publishes `21`, `25`, `21-fips`).
  `pom.xml` deliberately stays at `maven.compiler.release=17` — release-17 bytecode runs on
  a 21 JVM, so all three images carry identical application classes and the scan delta is
  attributable to the base image and dependency source alone. Do not "align" the pom to 21.
- **Never commit a rendered `maven-settings-lightwell.xml`** — it holds a live service-account
  token. `tools/maven-settings-lightwell.xml.template` is the committed source; the rendered
  copy goes to `target/`, is passed to the build via `--mount=type=secret`, and is gitignored.
- **`LW_USER` is `XXXXXXX|service-account-name`, not the client-ID UUID.** The UUID plus
  secret *do* authenticate against `sso.redhat.com` (client-credentials returns an access
  token), but `packages.redhat.com` rejects them with `invalid token`. Do not re-diagnose
  that 401 as bad credentials or a missing entitlement without first checking the username
  form. `LW_REPO` selects `validated` (default) / `remediated` / `predisclosure`.
- Every `<repository>` in the template needs a `<server>` with the **same `id`** — Maven
  matches credentials by id, and a mismatch silently requests anonymously and 401s.
- `tools/scan-images.sh` exports **docker-archive**, not oci-archive: trivy's `--input`
  rejects an OCI archive tar. It also mounts the scanner cache with `:U` because `hi/grype`
  and `hi/syft` run as UID 65532 and cannot otherwise write to a host directory.

Run locally as either role:

```bash
PRP_BENCH_MODE=RECEIVER PRP_BENCH_BIND_ADDRESS=127.0.0.1 mvn quarkus:dev
PRP_BENCH_MODE=SENDER PRP_BENCH_TARGET_HOST=127.0.0.1 PRP_BENCH_MESSAGES_PER_SECOND=1000 mvn quarkus:dev
```

Deploy with `oc apply -k k8s/base` (small/virtualised lab) or `oc apply -k k8s/overlays/baremetal`.
Both Deployments go to both clusters; `PRP_BENCH_MODE` decides which one is active. Pods need
`hostNetwork: true` and the `hostnetwork` SCC.

## Architecture

One artifact, eight classes in `com.redhat.prpbench`, role selected at startup:

- `BenchConfig` — `@ConfigMapping(prefix = "prp-bench")`, so every knob is `PRP_BENCH_*` as an
  env var. Defaults live here *and* in `application.properties`; they do not fully agree
  (`bindAddress` is `10.10.10.1` in the interface, `0.0.0.0` in the properties file — the
  properties file wins at runtime, and the Deployments set it explicitly anyway).
- `SenderService` / `ReceiverService` — both observe `StartupEvent` and return immediately if
  `config.mode()` is not theirs. The idle role costs only an idle JVM.
- `BenchMetrics` — Micrometer counters, RTT `Timer`, jitter and throughput `DistributionSummary`.
- `HostNetworkMetrics` — `@Scheduled(every = "1s")` reader of `/proc/net/snmp` and
  `/sys/class/net`, exposed as both gauges and the `host` block of `/api/stats`.
- `ThroughputReporter` — 1 s sampler feeding `prp.throughput.mbps`.
- `StatsResource` — `/api/stats`, `/api/stats/start`, `/api/stats/stop`, `/api/stats/peer`.
- `PrpMessage` — wire format `[8B seq][8B senderNanos][NB payload]`.

Dashboard is a single hand-written `src/main/resources/META-INF/resources/index.html` (no build
step, no framework). It fetches `/api/stats`, reads `mode`, and builds a sender or receiver
layout; the sender additionally polls `/api/stats/peer`.

### Runtime config lives in SenderService, not BenchConfig

Rate, payload size and duration are seeded from `BenchConfig` at startup and then held as
`volatile` fields on `SenderService` so a rate sweep needs no pod restart. **Always report and
read them via `sender.getMessagesPerSecond()` etc., never via `config`** — reporting the config
value while the timer ran at the old rate was a real bug here. The three-way agreement between
the app rate and the two LAN counters is what caught it.

`POST /api/stats/start` must `stop()` before `configure()` + `start()`, because `start()` is a
no-op while a run is in flight. It also resets the peer receiver over HTTP so both ends count
the same run.

## Invariants that look like cleanups but are not

Each of these was a bug that cost debugging time; the README's "Lessons learned" section is the
long form.

- **Sender pacing carries a fractional credit** across 1 ms ticks. Rounding per tick
  (`rate/1000 + 1`) turned 1500 msg/s into 2000 pps — a 33% overshoot that looked like a network
  problem. Do not "simplify" the credit loop.
- **`receiveBufferBytes` defaults to `0` and should stay there.** Vert.x's
  `DatagramSocketOptions.setReceiveBufferSize()` also sizes Netty's per-read buffer allocator, so
  a large value makes every read allocate and zero-fill a buffer that big — measured 15×
  throughput loss at 8 MB. Socket buffer sizing belongs on the node (`k8s/node-tuning.yaml`
  raises `net.core.rmem_default`), not in the application.
- **Loss is `highestSeq - firstSeq + 1 - received`**, anchored on the first sequence actually
  observed, so resetting the receiver mid-run does not report phantom loss.
- **RTT samples above 10 s are discarded.** `System.nanoTime()` has a per-process origin, so a
  reading spanning a restart on either side is meaningless rather than slow.
- **Histograms need `maximumExpectedValue`.** Without a ceiling the backing HdrHistogram spans
  the full long range and allocates buckets to match.
- **`ValueAtPercentile.value()` on a Timer returns nanoseconds.** Divide, do not multiply —
  getting this backwards produced RTTs of 3×10¹⁵ µs.
- **`quarkus.http.static-resources.caching-enabled=false` is deliberate.** Quarkus otherwise
  serves the dashboard as `immutable, max-age=24h` and a redeployed `:latest` image keeps
  rendering the previous build with no error anywhere.
- **Per-LAN counters are the only redundancy signal.** PRP hides a dead LAN by design, so
  application metrics cannot detect it. LANs are discovered from `lower_*` symlinks on the PRP
  device; `redundancyOk` requires exactly two LANs, neither below half the busier one's rate.

## Claims discipline

The README, `docs/demo-prp-lan-failure.md`, and `docs/student-guide-tanenbaum.md` are carefully
scoped, and several commits exist purely to walk back overclaims. When touching docs or
customer-facing strings:

- This tool **does not implement or certify IEC 62439-3** (PRP/HSR). That behaviour lives in the
  kernel, NIC and switch below `prp0`. The tool *exercises and observes* such an underlay.
- GOOSE / Sampled Values / OPC-UA appear as **motivation and roadmap only**. The data path today
  is unicast UDP with a 16-byte seq/timestamp header. Real GOOSE/SV are raw L2 multicast
  (EtherType `0x88B8` / `0x88BA`) — not UDP, except the R-GOOSE/R-SV variants.
  `docs/opcua-pubsub-synth-plan.md` is a plan, not shipped behaviour.
- **HSR is unverified.** `PRP_BENCH_PRP_INTERFACE` can point at `hsr0`, but do not present the
  tool as HSR-conformant.
- Measured figures in the README come from KVM guests on one host and are labelled as such.
  Keep that caveat attached to any number quoted from that lab.

On the hardened-image / Lightwell material specifically:

- **Scanner counts are dated observations, not image properties.** Any number quoted must
  carry the date and the vulnerability-DB version, exactly as benchmark figures carry the
  KVM-lab caveat. Re-run `tools/scan-images.sh` rather than reusing a table.
- **"Zero CVEs" is Red Hat's stated goal for the base layer**, not a guarantee and not a
  property of the PRP Bench image. The uber-jar's dependencies are ours.
- **`hi/openjdk:21` is not distroless.** Verified: it ships `bash` and `sh` (~210 binaries in
  `/usr/bin` vs the legacy image's 235) and `jcmd` works, because it is a full JDK. What it
  drops is `dnf`/`microdnf`/`rpm`/`curl`. Do not repeat a shell-removal claim the image does
  not support — `oc rsh` still works.
- **The securityContext angle does not hold on this workload.** Both `:legacy` (UID 185) and
  `:hardened` (UID 65532) run non-root and start cleanly under
  `--read-only --cap-drop=ALL`. Measured, not assumed. Do not sell read-only root as a
  hardened-image win here.
- **Lightwell `validated` is not patched** — it is securely rebuilt and reproducible.
  `remediated` carries the CVE fixes and requires `.rhlw-0000X` version pins.
- Lead with the OS/app split (688 → 2 rpm findings, 71 → 71 jar findings), not image size.
  384 MB → 312 MB is a real but unremarkable 19% and is a weak headline.

`docs/prp-hsr-compliance-grade.md` is in `.gitignore` as an internal self-assessment — read it
for context, but do not commit, publish, or quote it into tracked files.

## Payload budget

`PRP_BENCH_PAYLOAD_BYTES` is clamped only at 65000 in code, not to the PRP budget. On a real PRP
link the RCT means `payloadBytes + 16 + 8 (UDP) + 20 (IPv4) ≤ 1496`, i.e. `payloadBytes ≤ 1452`.
Above that you are measuring IP fragmentation, not the PRP path.
