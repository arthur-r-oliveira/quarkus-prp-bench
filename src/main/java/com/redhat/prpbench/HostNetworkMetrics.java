package com.redhat.prpbench;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Kernel-level network counters, read straight from the host network namespace
 * (the pods run with hostNetwork, so /proc/net and /sys/class/net already refer
 * to the host).
 * <p>
 * Two things here are not visible from application counters. First, UDP
 * receive-buffer errors are the authoritative loss signal — the application only
 * ever sees packets that survived. Second, and specific to PRP: the protocol
 * delivers every frame over two independent LANs and discards the duplicate, so
 * if one LAN fails the application notices nothing at all. Redundancy is gone and
 * no latency or loss metric moves. Comparing the two slave interfaces is the only
 * way to catch that.
 */
@ApplicationScoped
public class HostNetworkMetrics {

    private static final Logger LOG = Logger.getLogger(HostNetworkMetrics.class);

    private static final Path PROC_SNMP = Path.of("/proc/net/snmp");
    private static final Path SYS_NET = Path.of("/sys/class/net");
    /** Below this share of the busier LAN, treat the quieter one as degraded. */
    private static final double LAN_DEGRADED_RATIO = 0.5;

    private final BenchConfig config;
    private final MeterRegistry registry;

    private final List<String> lanInterfaces = new ArrayList<>();
    private final Map<String, AtomicLong> lanRxPackets = new LinkedHashMap<>();
    private final Map<String, AtomicLong> lanRxRate = new LinkedHashMap<>();
    private final Map<String, Long> lastRxPackets = new LinkedHashMap<>();

    private final AtomicLong udpRcvbufErrors = new AtomicLong();
    private final AtomicLong udpInErrors = new AtomicLong();
    private final AtomicLong udpRcvbufErrorRate = new AtomicLong();
    private long lastRcvbufErrors = -1;
    private long lastSampleNanos = 0;

    HostNetworkMetrics(BenchConfig config, MeterRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    void onStart(@Observes StartupEvent ev) {
        discoverLanInterfaces();

        registry.gauge("prp.host.udp.rcvbuf_errors", udpRcvbufErrors);
        registry.gauge("prp.host.udp.in_errors", udpInErrors);
        registry.gauge("prp.host.udp.rcvbuf_errors_per_sec", udpRcvbufErrorRate);

        for (String lan : lanInterfaces) {
            registry.gauge("prp.host.lan.rx_packets", Tags.of("iface", lan), lanRxPackets.get(lan));
            registry.gauge("prp.host.lan.rx_packets_per_sec", Tags.of("iface", lan), lanRxRate.get(lan));
        }
        sample();
    }

    /** PRP slaves appear as lower_* symlinks on the PRP device. */
    private void discoverLanInterfaces() {
        Path prpDir = SYS_NET.resolve(config.prpInterface());
        if (!Files.isDirectory(prpDir)) {
            LOG.infof("PRP interface %s not present; per-LAN metrics disabled", config.prpInterface());
            return;
        }
        try (Stream<Path> entries = Files.list(prpDir)) {
            entries.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("lower_"))
                    .map(n -> n.substring("lower_".length()))
                    .sorted()
                    .forEach(lanInterfaces::add);
        } catch (IOException e) {
            LOG.warnf("Could not enumerate %s slaves: %s", config.prpInterface(), e.getMessage());
            return;
        }
        for (String lan : lanInterfaces) {
            lanRxPackets.put(lan, new AtomicLong());
            lanRxRate.put(lan, new AtomicLong());
        }
        LOG.infof("PRP %s LANs: %s", config.prpInterface(), lanInterfaces);
    }

    @Scheduled(every = "1s")
    void sample() {
        long now = System.nanoTime();
        double elapsedSec = lastSampleNanos > 0 ? (now - lastSampleNanos) / 1e9 : 0;
        lastSampleNanos = now;

        long[] udp = readUdpCounters();
        if (udp != null) {
            udpInErrors.set(udp[0]);
            udpRcvbufErrors.set(udp[1]);
            if (lastRcvbufErrors >= 0 && elapsedSec > 0) {
                udpRcvbufErrorRate.set(Math.round(Math.max(0, udp[1] - lastRcvbufErrors) / elapsedSec));
            }
            lastRcvbufErrors = udp[1];
        }

        for (String lan : lanInterfaces) {
            long rx = readLongOrNegative(SYS_NET.resolve(lan).resolve("statistics/rx_packets"));
            if (rx < 0) continue;
            lanRxPackets.get(lan).set(rx);
            Long prev = lastRxPackets.get(lan);
            if (prev != null && elapsedSec > 0) {
                lanRxRate.get(lan).set(Math.round(Math.max(0, rx - prev) / elapsedSec));
            }
            lastRxPackets.put(lan, rx);
        }
    }

    /** @return {InErrors, RcvbufErrors} or null if unavailable. */
    private long[] readUdpCounters() {
        try {
            List<String> lines = Files.readAllLines(PROC_SNMP);
            for (int i = 0; i < lines.size() - 1; i++) {
                if (!lines.get(i).startsWith("Udp:")) continue;
                String[] headers = lines.get(i).trim().split("\\s+");
                String[] values = lines.get(i + 1).trim().split("\\s+");
                long in = 0, rcvbuf = 0;
                for (int c = 1; c < headers.length && c < values.length; c++) {
                    if ("InErrors".equals(headers[c])) in = Long.parseLong(values[c]);
                    if ("RcvbufErrors".equals(headers[c])) rcvbuf = Long.parseLong(values[c]);
                }
                return new long[]{in, rcvbuf};
            }
        } catch (IOException | NumberFormatException e) {
            LOG.debugf("Could not read %s: %s", PROC_SNMP, e.getMessage());
        }
        return null;
    }

    private static long readLongOrNegative(Path p) {
        try {
            return Long.parseLong(Files.readString(p).trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }

    public Map<String, Object> snapshot() {
        List<Map<String, Object>> lans = new ArrayList<>();
        long busiest = 0;
        for (String lan : lanInterfaces) {
            busiest = Math.max(busiest, lanRxRate.get(lan).get());
        }
        for (String lan : lanInterfaces) {
            long rate = lanRxRate.get(lan).get();
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("iface", lan);
            e.put("rxPackets", lanRxPackets.get(lan).get());
            e.put("rxPerSec", rate);
            // Only meaningful once there is traffic to compare against.
            e.put("degraded", busiest > 10 && rate < busiest * LAN_DEGRADED_RATIO);
            lans.add(e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("udpRcvbufErrors", udpRcvbufErrors.get());
        out.put("udpInErrors", udpInErrors.get());
        out.put("udpRcvbufErrorsPerSec", udpRcvbufErrorRate.get());
        out.put("prpInterface", config.prpInterface());
        out.put("lans", lans);
        out.put("redundancyOk", lans.size() == 2 && lans.stream().noneMatch(l -> (Boolean) l.get("degraded")));
        return out;
    }
}
