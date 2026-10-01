package com.redhat.prpbench;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class BenchMetrics {

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong bytesTransferred = new AtomicLong();

    private final Timer rttTimer;
    private final DistributionSummary jitterSummary;
    private final DistributionSummary throughputSummary;

    private final AtomicLong lastArrivalNanos = new AtomicLong();
    private final AtomicLong highestSeqSeen = new AtomicLong(-1);

    public BenchMetrics(MeterRegistry registry) {
        registry.gauge("prp.sent.total", sent);
        registry.gauge("prp.received.total", received);
        registry.gauge("prp.lost.total", this, m -> (double) m.getLost());
        registry.gauge("prp.bytes.total", bytesTransferred);

        rttTimer = Timer.builder("prp.rtt")
                .description("Round-trip time")
                .publishPercentiles(0.5, 0.95, 0.99, 0.999)
                .maximumExpectedValue(Duration.ofSeconds(1))
                .register(registry);

        jitterSummary = DistributionSummary.builder("prp.jitter.us")
                .description("Inter-arrival jitter in microseconds")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        throughputSummary = DistributionSummary.builder("prp.throughput.mbps")
                .description("Instantaneous throughput in Mbit/s")
                .register(registry);
    }

    public void recordSent() {
        sent.incrementAndGet();
    }

    public void recordReceived(long seq, int messageBytes) {
        received.incrementAndGet();
        bytesTransferred.addAndGet(messageBytes);

        long now = System.nanoTime();
        long prev = lastArrivalNanos.getAndSet(now);
        if (prev > 0) {
            double jitterUs = Math.abs(now - prev) / 1_000.0;
            jitterSummary.record(jitterUs);
        }

        highestSeqSeen.updateAndGet(current -> Math.max(current, seq));
    }

    public void recordRtt(long rttNanos) {
        rttTimer.record(Duration.ofNanos(rttNanos));
    }

    public void recordThroughputSample(double mbps) {
        throughputSummary.record(mbps);
    }

    public long getSent() { return sent.get(); }
    public long getReceived() { return received.get(); }
    public long getLost() { return Math.max(0, highestSeqSeen.get() + 1 - received.get()); }
    public long getBytesTransferred() { return bytesTransferred.get(); }
}
