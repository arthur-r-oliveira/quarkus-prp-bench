# PRP Bench

A UDP benchmarking tool for Parallel Redundancy Protocol (PRP) networks, built with Quarkus and Vert.x. Measures throughput, packet loss, round-trip time (RTT), and inter-arrival jitter between two endpoints connected over a PRP link.

## Architecture

The application runs in two modes on separate hosts:

```
  SNO-A (10.10.10.1)              PRP Network              SNO-B (10.10.10.2)
 ┌──────────────────┐          ┌───────────┐          ┌──────────────────┐
 │     SENDER       │  UDP :9200  │           │          │    RECEIVER      │
 │                  ├────────────►│   prp0    ├─────────►│                  │
 │  Sends packets   │          │           │          │  Receives packets│
 │  at configured   │◄────────────┤           │◄─────────┤  and sends ACKs  │
 │  rate            │  ACK :9201  │           │          │                  │
 └──────────────────┘          └───────────┘          └──────────────────┘
```

- **Sender** transmits UDP packets at a configurable rate with sequence numbers and nanosecond timestamps.
- **Receiver** records each packet and sends an ACK back to the sender for RTT measurement.
- Both expose a REST API on port 8080 for stats and Prometheus metrics.

## Metrics

| Metric | Description |
|--------|-------------|
| `prp.sent.total` | Total packets sent (sender side) |
| `prp.received.total` | Total packets received (receiver side) |
| `prp.lost.total` | Estimated lost packets (`highestSeq + 1 - received`) |
| `prp.rtt` | Round-trip time with p50/p95/p99/p99.9 percentiles |
| `prp.jitter.us` | Inter-arrival jitter in microseconds |
| `prp.throughput.mbps` | Instantaneous throughput in Mbit/s |

## Configuration

All settings are configured via environment variables (prefix `PRP_BENCH_`):

| Variable | Default | Description |
|----------|---------|-------------|
| `PRP_BENCH_MODE` | `SENDER` | `SENDER` or `RECEIVER` |
| `PRP_BENCH_TARGET_HOST` | `10.10.10.2` | Receiver IP address (sender only) |
| `PRP_BENCH_BIND_ADDRESS` | `0.0.0.0` | Local bind address |
| `PRP_BENCH_DATA_PORT` | `9200` | UDP port for benchmark traffic |
| `PRP_BENCH_ACK_PORT` | `9201` | UDP port for ACK responses |
| `PRP_BENCH_PAYLOAD_BYTES` | `256` | Payload size per packet |
| `PRP_BENCH_MESSAGES_PER_SECOND` | `50000` | Target send rate |
| `PRP_BENCH_DURATION_SECONDS` | `0` | Test duration (0 = unlimited) |
| `PRP_BENCH_ACK_SAMPLE_RATE` | `100` | ACK every Nth packet for RTT sampling (1 = every packet) |
| `PRP_BENCH_RECEIVE_BUFFER_BYTES` | `67108864` | Requested `SO_RCVBUF` on the receiver |

### Node tuning

The kernel silently clamps `SO_RCVBUF` to `net.core.rmem_max`, which defaults to
212992 B (208 KB) on RHCOS. A request for a larger buffer does not fail — it is
quietly reduced. Apply the bundled TuneD profile to raise the ceiling:

```bash
oc apply -f k8s/node-tuning.yaml
oc get profile -n openshift-cluster-node-tuning-operator   # expect APPLIED=True
```

The Node Tuning Operator applies these without rebooting the node.

Raising the ceiling only makes a larger buffer *possible*. Do not then request a
huge one — see the sizing note below.

## Interpreting packet loss

Loss reported by this tool is not necessarily network loss. Diagnose in this
order, because the application's own counters are the least trustworthy signal.

**1. Ask the kernel first.** On the receiver node:

```bash
netstat -su | grep -E 'receive buffer errors|packets received'
ss -u -a -m | grep -A1 ':9200'      # 'rb' = buffer actually granted
```

`receive buffer errors` climbing means the NIC and the link delivered the packets
fine and the *application* failed to drain its socket in time. That is a consumer
speed problem, not a network problem.

**2. Don't let ICMP mislead you.** `ping` runs at about 1 packet/sec. A clean ping
alongside heavy UDP loss is the normal, expected result at 50k packets/sec — four
orders of magnitude apart. It tells you the link is up, nothing more.

**3. Check the granted buffer, not the requested one.** Compare the `rb` value
from `ss` against `PRP_BENCH_RECEIVE_BUFFER_BYTES`. If they differ, `rmem_max` is
clamping you.

**4. Zero loss is not the same as healthy.** A buffer large enough to absorb an
overshoot hides it as latency instead of loss. Always read loss and RTT together:

| Offered | Loss | Kernel drops | RTT p50 |
|---|---|---|---|
| 50000 msg/s | ~95% | millions | — |
| 1500 msg/s | **0%** | **0** | **~2.9 s** |

The second row is not a healthy run. The sender was offering slightly more than
the receiver could drain, and an 8 MB buffer turned that small excess into
seconds of queueing delay. Textbook bufferbloat. If RTT is orders of magnitude
above your ping time, you are over capacity no matter what the loss counter says.

### Sizing the receive buffer

A socket buffer absorbs **bursts**. It cannot fix a consumer that is persistently
slower than the producer.

Measured on this setup: raising the buffer from 208 KB to 128 MB made throughput
dramatically *worse* — the receiver fell to roughly 20 packets/sec. The oversized
buffer converted bounded packet loss into an undrainable backlog that exhausted
the pod's heap, and the latency figures became meaningless because the receiver
was echoing timestamps from packets queued minutes earlier.

The default of 8 MB is about 300 ms of headroom at 50k packets/sec. If loss
persists at that size, the answer is to lower the offered rate or make the
receive path cheaper — not to raise the buffer again.

### Reducing receiver cost

`PRP_BENCH_ACK_SAMPLE_RATE` makes the receiver ACK every Nth packet rather than
every one, and jitter is written to the histogram 1-in-100 (the inter-arrival
delta is still computed on every packet, so it stays a true consecutive-packet
figure).

Both were added on the theory that the receive path was syscall-bound. **An A/B
measurement showed that is not the case** — disabling ACKs entirely changed
nothing:

| ACK sampling | Sustained receive rate |
|---|---|
| every 100 packets | ~1,838 pkt/s |
| effectively disabled | ~1,842 pkt/s |

The knobs are retained because they are cheap and reduce needless work, but they
are not the lever for throughput.

## Known limitation: receiver throughput ceiling

The receiver saturates at roughly **1,800 packets/sec**, far below what a Vert.x
UDP consumer should manage. Offered load above that is dropped by the kernel
regardless of buffer size. This ceiling is measured but **not yet explained**.

What has been ruled out, each by direct measurement:

| Hypothesis | Evidence against |
|---|---|
| Network / PRP link | Kernel reports `receive buffer errors`, not interface drops; ICMP clean |
| Socket buffer too small | Ceiling unchanged across 208 KB → 16 MB → 128 MB |
| ACK syscall per packet | Disabling ACKs entirely: no change (table above) |
| GC pressure | `jvm_gc_overhead` 0.09%, heap near-empty, 41 minor GCs |
| CPU throttling | 10 throttled periods out of 1,129; 0.18 s total |

What is known: a single `vert.x-eventloop` thread consumes ~100% of one core,
**97% of it user time** rather than system time, which points at in-process work
rather than the kernel or syscalls. Profiling that thread (JFR or async-profiler)
is the next step.

> **These numbers come from virtual machines, not baremetal.** The figures above
> were measured with both SNOs running as KVM guests on a single host, so the
> "PRP link" between them is a virtualised path and the two nodes share physical
> CPUs and NICs. The host itself was not saturated (72 threads, load ~9.5, 8 vCPU
> per guest), so this is not simple CPU starvation — but a virtio datapath and
> software PRP duplicate-discard are both plausible contributors that would not
> apply on real hardware. **Treat ~1,800 pkt/s as a property of this lab, not of
> the tool.** Re-measure on baremetal before drawing conclusions.

### A note on RTT across restarts

RTT is derived from a sender timestamp echoed back by the receiver. `System.nanoTime()`
has an arbitrary per-process origin, so a reading that spans a restart on either
side is meaningless rather than merely slow. Such samples are discarded above a
10-second plausibility bound; a run that restarts mid-flight will show fewer RTT
samples rather than absurd ones.

## Building

```bash
podman build -t quay.io/rhn_support_arolivei/prp-bench:latest -f Containerfile .
podman push quay.io/rhn_support_arolivei/prp-bench:latest
```

## Deploying on OpenShift (SNO)

The application uses `hostNetwork: true` to reach the PRP interface directly, so
the default service account needs the `hostnetwork` SCC.

### Profiles

| Profile | Path | CPU req/limit | Default rate | Use for |
|---|---|---|---|---|
| base (default) | `k8s/base` | 250m / 1 | 50000 msg/s | Constrained or virtualised nodes |
| baremetal | `k8s/overlays/baremetal` | 4 / 8 | 50000 msg/s | Real hardware |

The base profile is deliberately small so the benchmark runs on a modest VM lab.
The baremetal overlay raises CPU, memory, and the receive buffer; it does not
change application behaviour, only headroom.

```bash
export KC_A=/path/to/sno-a/auth/kubeconfig   # sender node
export KC_B=/path/to/sno-b/auth/kubeconfig   # receiver node

for KC in $KC_A $KC_B; do
  oc --kubeconfig=$KC apply -f k8s/base/namespace.yaml
  oc --kubeconfig=$KC adm policy add-scc-to-user hostnetwork -z default -n prp-bench
  oc --kubeconfig=$KC apply -f k8s/node-tuning.yaml
done

# Minimal / virtualised lab
oc --kubeconfig=$KC_A apply -k k8s/base
oc --kubeconfig=$KC_B apply -k k8s/base

# Baremetal
oc --kubeconfig=$KC_A apply -k k8s/overlays/baremetal
oc --kubeconfig=$KC_B apply -k k8s/overlays/baremetal
```

Each cluster receives both Deployments; `PRP_BENCH_MODE` decides which one is
active, so the idle role costs only an idle JVM. Preview any profile before
applying with `oc kustomize k8s/overlays/baremetal`.

### Tuning for your hardware

Every parameter is an environment variable, so no rebuild is needed:

```bash
oc --kubeconfig=$KC_A -n prp-bench set env deployment/prp-sender \
  PRP_BENCH_MESSAGES_PER_SECOND=20000 PRP_BENCH_PAYLOAD_BYTES=1024
```

Note that `oc set env` to a value a Deployment already has does **not** trigger a
rollout. When scripting a sweep, follow it with an explicit
`oc rollout restart deployment/prp-sender`, or a run will silently measure the
previous configuration.

Find the usable rate by starting low and increasing until either loss or RTT
degrades — both matter, since a large buffer trades one for the other.

## REST API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/stats` | GET | Current benchmark statistics |
| `/api/stats/stop` | POST | Stop the sender and/or receiver |
| `/q/metrics` | GET | Prometheus metrics |

## Wire Format

Each UDP packet is `16 + payloadBytes` bytes:

| Offset | Size | Field |
|--------|------|-------|
| 0 | 8 bytes | Sequence number (long) |
| 8 | 8 bytes | Sender timestamp in nanoseconds (long) |
| 16 | N bytes | Zero-filled payload |
