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

Two knobs matter when the receiver is the bottleneck:

- `PRP_BENCH_ACK_SAMPLE_RATE` — the receiver ACKs every Nth packet instead of
  every one. At the default of 100 this removes an outbound syscall per inbound
  packet; the whole receive path runs on a single Vert.x event-loop thread, so
  that work is directly in the critical path. Set to `1` to restore exact
  per-packet RTT at a large throughput cost.
- Jitter is sampled into the histogram 1-in-100, though the inter-arrival delta is
  still computed on every packet, so it remains a true consecutive-packet figure.

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

The application uses `hostNetwork: true` to access the PRP interface directly. The default service account needs the `hostnetwork` SCC.

```bash
# Set kubeconfigs
export KC_A=/path/to/sno-a/auth/kubeconfig
export KC_B=/path/to/sno-b/auth/kubeconfig

# Create namespace on both clusters
oc --kubeconfig=$KC_A apply -f k8s/namespace.yaml
oc --kubeconfig=$KC_B apply -f k8s/namespace.yaml

# Grant hostnetwork SCC
oc --kubeconfig=$KC_A adm policy add-scc-to-user hostnetwork -z default -n prp-bench
oc --kubeconfig=$KC_B adm policy add-scc-to-user hostnetwork -z default -n prp-bench

# Deploy
oc --kubeconfig=$KC_A apply -f k8s/sender-deployment.yaml
oc --kubeconfig=$KC_B apply -f k8s/receiver-deployment.yaml
```

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
