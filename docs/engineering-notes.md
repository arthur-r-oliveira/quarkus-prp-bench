# Engineering notes

Deep measurement and debugging notes that used to live in the README. The README
keeps the OT demo and the Hardened Images / Lightwell proof point lean; this
file keeps the detail that cost debugging time.

## Host and PRP link metrics (detail)

Both dashboards carry a **Host & PRP link** panel fed by kernel counters. The
pods run with `hostNetwork: true`, so `/proc/net/snmp` and `/sys/class/net`
already refer to the host namespace.

| Reported | Source | Why |
|---|---|---|
| UDP rcvbuf errors (total and /sec) | `/proc/net/snmp` | Authoritative loss signal |
| UDP in errors | `/proc/net/snmp` | Malformed or undeliverable datagrams |
| Per-LAN rx packets and rate | `/sys/class/net/<slave>/statistics` | PRP redundancy health |
| `REDUNDANCY OK` / `LAN DEGRADED` | derived | One-glance verdict |

**PRP masks a single LAN failure by design.** Application metrics stay perfect
while redundancy is gone; only slave rx rates reveal it. LANs are discovered
from `lower_*` links on `PRP_BENCH_PRP_INTERFACE` (default `prp0`).

IEC 62439-3 supervision / node tables are a richer signal
(`/sys/kernel/debug/hsr/prp0/node_table`) but need debugfs and privileges this
pod does not request. Slave rx rates are the cheap proxy.

Prometheus:

```
prp_host_udp_rcvbuf_errors
prp_host_udp_rcvbuf_errors_per_sec
prp_host_udp_in_errors
prp_host_lan_rx_packets{iface="enp6s0"}
prp_host_lan_rx_packets_per_sec{iface="enp6s0"}
```

Correlate with node-exporter if useful; it cannot emit the PRP pair verdict.

## Interpreting packet loss

Loss is `highestSeq - firstSeq + 1 - received`, anchored on the first sequence
actually observed. Kernel `UdpRcvbufErrors` is the authoritative signal when
the app and kernel disagree.

### Receive buffer — leave it at 0 in the application

`PRP_BENCH_RECEIVE_BUFFER_BYTES` defaults to `0`. Vert.x
`DatagramSocketOptions.setReceiveBufferSize()` also sizes Netty's per-read
allocator; a large value made every read allocate and zero-fill that big —
measured **15× throughput loss** at 8 MB. Socket buffer sizing belongs on the
node (`k8s/node-tuning.yaml`), not in the app.

### ACK sampling is not the throughput lever

Disabling ACKs entirely did not move sustained receive rate (~1,838 vs
~1,842 pkt/s). The knobs remain because they are cheap, not because they unlock
throughput.

## Case study: 15× throughput regression

Receiver saturated at ~1,800 pkt/s. Network, socket size, ACK cost, GC, and CPU
throttling were each ruled out by measurement; all were misleading.

- **iperf3** on the same link: 75,633 pkt/s at 0% loss → fault was ours.
- **`jcmd Thread.print`** loop: 12/12 samples on `Unsafe.setMemory0` beneath
  `PoolArena.allocateHuge` (the Vert.x receive-buffer coupling).

After decoupling the read buffer:

| Offered | Before | After |
|---|---|---|
| 10k msg/s | 1,800 pkt/s | **11,452 pkt/s** |
| 50k msg/s | 1,800 pkt/s | **26,957 pkt/s** |

> Numbers from KVM guests on one host. Re-measure on baremetal before treating
> ~27k pkt/s as a hard figure.

## Lessons learned (short form)

1. Ask the kernel before you believe the application.
2. Baseline with iperf3 (or similar) before profiling your own code.
3. ICMP proves almost nothing under UDP load.
4. Zero loss is not success — read loss and latency together.
5. Exonerating evidence can be a fingerprint of the bug (clean GC + 97% user CPU).
6. Verify the buffer you were granted (`rmem_max` clamps silently).
7. Bigger buffers are not safer defaults (15× regression here).
8. Check what is actually in the runtime image (`jcmd` on UBI runtime).
9. `Thread.print` in a loop before a profiler.
10. Sender pacing must carry fractional credit — do not “simplify” to `rate/1000+1`.
11. `oc set env` to an unchanged value does not roll out.
12. `ValueAtPercentile.value()` on a Timer is nanoseconds — divide.
13. App rate, LAN A, and LAN B should agree.
14. Disable Quarkus static `immutable, max-age=24h` for the dashboard.
15. Redundancy failure is silent — monitor PRP slaves.

RTT samples above 10 s are discarded (`System.nanoTime()` per-process origin).
