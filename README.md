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

## Why zero loss matters here

PRP (IEC 62439-3) exists because OT networks cannot tolerate the recovery times
that IT networks accept as normal. It is worth being precise about why, because
it determines what this tool should measure.

**PRP has no retransmission and no failover delay — by design.** A PRP node sends
every frame simultaneously over two independent LANs; the receiver accepts
whichever copy arrives first and discards the duplicate. If one LAN fails the
other already carried the frame, so recovery time is **zero**. Compare RSTP, which
reconverges in seconds — an eternity for a protection relay.

**The traffic it carries has hard deadlines and no second chance.** Typical
IEC 61850 payloads on these links:

| Traffic | Rate | Deadline | Recovery if lost |
|---|---|---|---|
| GOOSE (protection trip) | Event-driven, burst-repeated | 3–4 ms | None — it is multicast, fire-and-forget |
| Sampled Values (SV) | 4,000–4,800 frames/sec, continuous | Per-sample | None — the sample is simply gone |

These are Layer 2 / UDP multicast streams. There is no TCP underneath, no ACK, no
retry. A dropped Sampled Values frame is a hole in the current and voltage
waveform a protection algorithm is integrating; enough holes and the relay either
fails to trip on a real fault or trips on a phantom one. Both outcomes are
safety-relevant, and neither shows up as an error anywhere — just as a slightly
wrong answer.

**So the acceptance criteria are different from IT benchmarking.** Average
throughput is nearly irrelevant. What matters is:

1. **Zero loss at the offered rate** — not "four nines", zero, because loss is
   unrecoverable.
2. **Bounded worst-case latency** — the p99.9 matters far more than the median,
   since a single late GOOSE frame misses its window just as surely as a lost one.
3. **Low, stable jitter** — SV processing assumes evenly spaced samples.

This is why the tool reports percentiles rather than averages, why it tracks loss
and latency together, and why "0% loss" alone is treated as an insufficient
result throughout this document. A link that delivers every packet 2 seconds late
has failed an OT requirement just as badly as one that drops them.

## Dashboard

Each role serves a dashboard on port 8080 that shows only the metrics it actually
measures, rather than padding both pages with zeros:

| | Sender | Receiver |
|---|---|---|
| Tiles | Sent, send rate, RTT p50, target | Received, lost, loss %, throughput, bytes |
| Charts | Send rate, RTT p50/p95/p99 | Receive rate, throughput, jitter p50/p95/p99 |
| Controls | Rate / payload / duration, Apply & Restart | — |

The sender page also carries a **Receiver (peer)** panel fed by `/api/stats/peer`,
so loss and latency appear together on one screen. That pairing is deliberate: a
run can report zero loss while sitting at seconds of queueing delay, and seeing
only one of the two numbers hides it.

## Metrics

| Metric | Description |
|--------|-------------|
| `prp.sent.total` | Total packets sent (sender side) |
| `prp.received.total` | Total packets received (receiver side) |
| `prp.lost.total` | Gaps within the observed sequence range (`highestSeq - firstSeq + 1 - received`), so resetting mid-run does not report phantom loss |
| `prp.rtt` | Round-trip time with p50/p95/p99/p99.9 percentiles |
| `prp.jitter.us` | Inter-arrival jitter in microseconds |
| `prp.throughput.mbps` | Instantaneous throughput in Mbit/s |

## Host and PRP link metrics

Both dashboards carry a **Host & PRP link** panel fed by kernel counters. The pods
run with `hostNetwork: true`, so `/proc/net/snmp` and `/sys/class/net` already
refer to the host namespace — no DaemonSet, no extra privileges, no sidecar.

| Reported | Source | Why |
|---|---|---|
| UDP rcvbuf errors (total and /sec) | `/proc/net/snmp` | Authoritative loss signal; the application only ever sees packets that survived |
| UDP in errors | `/proc/net/snmp` | Malformed or undeliverable datagrams |
| Per-LAN rx packets and rate | `/sys/class/net/<slave>/statistics` | PRP redundancy health — see below |
| `REDUNDANCY OK` / `LAN DEGRADED` | derived | One-glance verdict |

### Why per-LAN counters matter more than they look

**PRP masks a single LAN failure by design.** If LAN A dies, every frame still
arrives over LAN B. Loss stays at zero, latency does not move, and *nothing* in
the application metrics changes. You are now running with no redundancy, on a
protocol chosen specifically for redundancy, and the benchmark would happily
report a perfect run.

The only evidence is that one slave interface stopped counting. The two LANs are
discovered automatically from the `lower_*` links on the PRP device
(`PRP_BENCH_PRP_INTERFACE`, default `prp0`), so no configuration is needed.

A healthy link looks like this — the two LANs track each other almost exactly,
because PRP sends every frame down both:

```
app-level receive   =    5000 pkt/s   (configured 5000)
LAN A enp6s0        =    5000 pkt/s
LAN B enp7s0        =    5000 pkt/s
redundancyOk        = True
```

That three-way agreement is also a useful correctness check on the tool itself:
during development it caught a bug where the API reported a newly configured rate
while the sender kept transmitting at the old one.

### Prometheus and node-exporter

These are published as Prometheus metrics too, so they can be scraped or
correlated in Grafana:

```
prp_host_udp_rcvbuf_errors
prp_host_udp_rcvbuf_errors_per_sec
prp_host_udp_in_errors
prp_host_lan_rx_packets{iface="enp6s0"}
prp_host_lan_rx_packets_per_sec{iface="enp6s0"}
```

OpenShift already ships node-exporter, which covers the generic counters — useful
for correlation:

```promql
rate(node_netstat_Udp_RcvbufErrors[1m])
rate(node_network_receive_packets_total{device=~"enp6s0|enp7s0"}[1m])
```

The in-app metrics exist alongside it for two reasons. node-exporter has no idea
that `enp6s0` and `enp7s0` are a PRP redundancy pair, so it cannot produce the
degraded-LAN verdict. And querying Thanos from the browser dashboard would mean
bearer tokens, RBAC and CORS — a lot of moving parts to display numbers the pod
can already read directly.

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
| `PRP_BENCH_RECEIVE_BUFFER_BYTES` | `0` | `0` = leave the socket buffer to the kernel. **Setting this is almost always wrong** — see below |
| `PRP_BENCH_PRP_INTERFACE` | `prp0` | PRP device whose two slave LANs are monitored for redundancy |

Rate, payload size and duration can also be changed at runtime through
`POST /api/stats/start`, with no pod restart.

### Node tuning

Socket buffer sizing belongs on the node, not in the application. Apply the
bundled TuneD profile:

```bash
oc apply -f k8s/node-tuning.yaml
oc get profile -n openshift-cluster-node-tuning-operator   # expect APPLIED=True
```

It raises `net.core.rmem_default` / `rmem_max` to 128 MB and
`netdev_max_backlog` to 250000. The Node Tuning Operator applies these without
rebooting the node.

Two things this fixes:

- `net.core.rmem_default` (208 KB by default on RHCOS) is what the receiver's
  socket inherits, since the application deliberately does not set `SO_RCVBUF`.
- `net.core.rmem_max` silently **clamps** any explicit `SO_RCVBUF` request. A
  request for more does not fail; it is quietly reduced, so always check the
  granted value rather than trusting the requested one.

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

**3. Check the granted buffer, not the requested one.** The `rb` field from `ss`
is what the socket actually got. With the default configuration it should reflect
`net.core.rmem_default`; if you set `PRP_BENCH_RECEIVE_BUFFER_BYTES` and `rb` is
smaller, `rmem_max` is clamping you.

**4. Zero loss is not the same as healthy.** A buffer large enough to absorb an
overshoot hides it as latency instead of loss. Always read loss and RTT together:

| Offered | Actual rate | Loss | Kernel drops | RTT p50 |
|---|---|---|---|---|
| 50000 msg/s | 50000 pps | ~95% | millions | — |
| 1500 msg/s *(sender overshooting to 2000 pps)* | 2000 pps | **0%** | **0** | **~2.9 s** |
| 1500 msg/s *(pacing fixed)* | 1525 pps | 0% | 0 | **2.6 ms** |

The middle row is not a healthy run, despite reporting zero loss. The sender was
offering slightly more than the receiver could drain and the buffer turned that
small excess into seconds of queueing delay — textbook bufferbloat. Correcting
the send rate dropped RTT by three orders of magnitude with loss unchanged.

If RTT sits far above your ping time, you are over capacity no matter what the
loss counter says.

### Sizing the receive buffer — do not set it in the application

**`PRP_BENCH_RECEIVE_BUFFER_BYTES` defaults to `0`, meaning "don't set it", and
that is almost always correct.** Raise `net.core.rmem_default` on the node (the
bundled TuneD profile does this) instead of setting a value here.

The reason is a sharp edge in Vert.x: `DatagramSocketOptions.setReceiveBufferSize()`
sizes **Netty's per-read buffer allocator** as well as `SO_RCVBUF`. Ask for an
8 MB socket buffer and Netty allocates *and zero-fills* an 8 MB direct buffer on
every single read. Above Netty's 4 MB chunk size it cannot even pool them, so each
packet costs a fresh `allocateDirect` plus a full `memset`.

Measured directly, at a constant 10,000 msg/s offered load:

| `RECEIVE_BUFFER_BYTES` | Throughput |
|---|---|
| 8 MB | 1,966 pkt/s |
| 256 KB | **9,986 pkt/s** |
| 64 KB | 9,471 pkt/s |

A profile of the receive thread showed 12 of 12 samples in
`Unsafe.setMemory0` under `PoolArena.allocateHuge` — the zero-fill, nothing else.

So the intuition that "a bigger buffer absorbs bursts" is right about the kernel
socket buffer and badly wrong about this knob. Leave it at `0`, and size the
kernel's buffer via sysctl where it has no effect on the read path.

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

## Case study: finding a 15x throughput regression

The receiver once saturated at roughly **1,800 packets/sec**, far below what a
Vert.x UDP consumer should manage. The cause turned out to be the
`setReceiveBufferSize` coupling described above — but four plausible explanations
were eliminated first, and the way they were eliminated is the useful part.

Ruled out, each by direct measurement:

| Hypothesis | Evidence against |
|---|---|
| Network / PRP link | Kernel reports `receive buffer errors`, not interface drops; ICMP clean |
| Socket buffer too small | Ceiling unchanged across 208 KB → 16 MB → 128 MB |
| ACK syscall per packet | Disabling ACKs entirely: no change (table above) |
| GC pressure | `jvm_gc_overhead` 0.09%, heap near-empty, 41 minor GCs |
| CPU throttling | 10 throttled periods out of 1,129; 0.18 s total |

Every one of those was true and every one was misleading. GC was clean *because*
the buffers were direct and off-heap; CPU time was 97% user *because* a `memset`
is pure userspace work. Both exonerating signals were fingerprints of the bug.

**What actually found it, in two steps:**

*A non-JVM baseline first.* `iperf3` over the same link with the same 272-byte
datagrams sustained **75,633 pkt/s at 0% loss**, which exonerated the network,
the virtualised datapath and the hardware in one measurement — and proved the
fault was ours, turning an open question into a bounded one.

| iperf3 offered | Datagrams | Loss |
|---|---|---|
| ~10k pps | 150,271 | 0% |
| ~50k pps | 749,996 | 0% |
| unlimited → 75,633 pps | 1,134,500 | 0% |

*Then a thread dump, which beat reaching for a profiler.* Twelve samples half a
second apart put 12 of 12 on one stack — `Unsafe.setMemory0` beneath
`PoolArena.allocateHuge`. `jcmd` ships in `ubi8/openjdk-17-runtime`, so this
attaches to a running pod with no rebuild, env var, or restart:

```bash
for i in $(seq 12); do
  oc exec -n prp-bench deploy/prp-receiver -- jcmd 1 Thread.print | grep -A12 'vert.x-eventloop-thread-0'
done
```

**Result** at identical offered load, after decoupling the read buffer:

| Offered | Before | After | Kernel drops | RTT p50 | Jitter p50 |
|---|---|---|---|---|---|
| 10k msg/s | 1,800 pkt/s | **11,452 pkt/s** | 0 | 770 µs | 38 µs |
| 50k msg/s | 1,800 pkt/s | **26,957 pkt/s** | 15,446 | 950 µs | 17 µs |

At 50k the receiver still trails the offered rate, so ~27k pkt/s is the current
practical ceiling here — but it is now a real processing limit rather than a
self-inflicted one.

> **These numbers come from KVM guests on a single host**, so the PRP path is
> virtualised. The iperf3 baseline shows the path itself carries 75k pkt/s, so the
> remaining gap is application cost, not the lab. Re-measure on baremetal before
> treating ~27k pkt/s as a hard figure.

## Lessons learned

Every item below cost real debugging time on this project. They are ordered
roughly by how much.

**On measurement**

1. **Ask the kernel before you believe the application.** `netstat -su` and
   `ss -u -a -m` on the node are ground truth; the app's own counters are the
   least trustworthy signal in the stack.
2. **Baseline with a different tool before profiling your own code.** iperf3
   doing 75,633 pkt/s over the same link collapsed an open-ended "why is this
   slow" into "the fault is ours" in one measurement.
3. **ICMP proves almost nothing.** `ping` runs at ~1 packet/sec. A clean ping
   next to 95% UDP loss at 50k pkt/s is expected, not contradictory.
4. **Zero loss is not success.** A run reported 0% loss while sitting at 2.9 s
   RTT. Read loss and latency together, always.
5. **Beware exonerating evidence that is really a fingerprint.** GC looked clean
   *because* the buffers were off-heap; CPU was 97% user *because* `memset` is
   userspace work. Both "ruled out" signals were symptoms of the actual bug.

**On buffers**

6. **Verify the buffer you were granted, not the one you asked for.**
   `net.core.rmem_max` clamps `SO_RCVBUF` silently — no error, just a smaller
   buffer.
7. **A bigger buffer is not a safer default.** It cost 15× throughput here via
   Netty's read-buffer coupling, and separately converts overshoot into
   unbounded queueing delay. Buffers absorb bursts; they cannot fix a consumer
   slower than its producer.

**On the tooling**

8. **Check what is actually in your runtime image.** `jcmd` ships in
   `ubi8/openjdk-17-runtime` despite it being a JRE — assuming otherwise nearly
   cost a rebuild-and-redeploy cycle that would have perturbed the system under
   test.
9. **Try `Thread.print` in a loop before setting up a profiler.** Twelve samples
   found the culprit outright; JFR was never needed.
10. **A benchmark must emit the rate you configured.** `rate/1000 + 1` per tick
    turned 1500 msg/s into 2000 pps — a 33% overshoot that masqueraded as a
    network problem.
11. **`oc set env` to an unchanged value does not trigger a rollout.** Two sweep
    runs silently measured the previous configuration before this was noticed.
12. **Check unit conversions in metrics code.** `ValueAtPercentile.value()`
    returns nanoseconds for a Timer; multiplying instead of dividing produced
    RTTs of 3×10¹⁵ µs.
13. **Cross-check the same quantity from independent sources.** Application
    rate, LAN A and LAN B counters should agree. When they did not, it exposed
    an API that reported a new send rate while still transmitting the old one.

**On PRP specifically**

14. **Redundancy failure is silent.** PRP hides a dead LAN by design, so a
    perfect-looking benchmark can be running with no protection left. Monitor
    the slave interfaces, not just the PRP device.

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

| Profile | Path | CPU req/limit | Memory | Use for |
|---|---|---|---|---|
| base (default) | `k8s/base` | 250m / 2 | 256Mi / 512Mi | Constrained or virtualised nodes |
| baremetal | `k8s/overlays/baremetal` | 4 / 8 | 2Gi / 4Gi | Real hardware |

The base profile is deliberately small so the benchmark runs on a modest VM lab.
The baremetal overlay only raises CPU and memory headroom — it does not change
application behaviour.

The receiver's CPU limit is 2 rather than 1 on purpose: the receive loop alone
can consume a full core, and a 1-CPU limit leaves nothing for JIT, GC or the HTTP
endpoint, making throttling a confound in every measurement.

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

Prefer the runtime API — it changes rate, payload and duration with no restart,
which is what makes a sweep practical:

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"messagesPerSecond":20000,"payloadBytes":1024}' \
  http://<sender>:8080/api/stats/start
```

Everything is also an environment variable if you want it persisted:

```bash
oc --kubeconfig=$KC_A -n prp-bench set env deployment/prp-sender \
  PRP_BENCH_MESSAGES_PER_SECOND=20000 PRP_BENCH_PAYLOAD_BYTES=1024
```

> `oc set env` to a value a Deployment **already has** does not trigger a
> rollout. When scripting a sweep, follow it with an explicit
> `oc rollout restart deployment/prp-sender`, or the run will silently measure
> the previous configuration. This silently invalidated two runs during
> development.

Find the usable rate by starting low and increasing until **either** loss or RTT
degrades. Both matter: a buffer large enough to absorb an overshoot converts loss
into latency, so watching only the loss counter will tell you everything is fine
while queueing delay climbs into seconds.

## REST API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/stats` | GET | Current statistics, plus a `config` block with the live settings |
| `/api/stats/start` | POST | Reset counters and start; optionally override settings |
| `/api/stats/stop` | POST | Stop the sender and/or receiver |
| `/api/stats/peer` | GET | Proxies the receiver's stats (sender only) |
| `/q/metrics` | GET | Prometheus metrics |

`/api/stats/start` accepts an optional JSON body; omitted fields keep their
current value. This changes the run **without** a pod restart, which is what
makes a rate sweep practical:

```bash
curl -X POST -H 'Content-Type: application/json' \
  -d '{"messagesPerSecond":20000,"payloadBytes":512,"durationSeconds":30}' \
  http://<sender>:8080/api/stats/start
```

Starting from the sender also resets the receiver, so both ends count the same
run.

## Wire Format

Each UDP packet is `16 + payloadBytes` bytes:

| Offset | Size | Field |
|--------|------|-------|
| 0 | 8 bytes | Sequence number (long) |
| 8 | 8 bytes | Sender timestamp in nanoseconds (long) |
| 16 | N bytes | Zero-filled payload |
