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
