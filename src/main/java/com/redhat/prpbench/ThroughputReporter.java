package com.redhat.prpbench;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ThroughputReporter {

    private static final Logger LOG = Logger.getLogger(ThroughputReporter.class);

    private final BenchMetrics metrics;
    private long lastBytes = 0;
    private long lastTime = System.nanoTime();

    ThroughputReporter(BenchMetrics metrics) {
        this.metrics = metrics;
    }

    @Scheduled(every = "1s")
    void report() {
        long nowBytes = metrics.getBytesTransferred();
        long nowTime = System.nanoTime();

        long deltaBytes = nowBytes - lastBytes;
        double deltaSec = (nowTime - lastTime) / 1_000_000_000.0;

        if (deltaBytes > 0 && deltaSec > 0) {
            double mbps = (deltaBytes * 8.0) / (deltaSec * 1_000_000.0);
            metrics.recordThroughputSample(mbps);
            LOG.infof("Throughput: %.1f Mbit/s | Sent: %d | Recv: %d | Lost: %d",
                    mbps, metrics.getSent(), metrics.getReceived(), metrics.getLost());
        }

        lastBytes = nowBytes;
        lastTime = nowTime;
    }
}
