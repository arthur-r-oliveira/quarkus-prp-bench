package com.redhat.prpbench;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class SenderService {

    private static final Logger LOG = Logger.getLogger(SenderService.class);

    private final BenchConfig config;
    private final BenchMetrics metrics;
    private final Vertx vertx;
    private final AtomicLong sequence = new AtomicLong(0);

    private volatile boolean running = false;
    private boolean initialized = false;
    private DatagramSocket socket;
    private byte[] payload;
    private long sendTimerId = -1;
    private long durationTimerId = -1;

    SenderService(BenchConfig config, BenchMetrics metrics, Vertx vertx) {
        this.config = config;
        this.metrics = metrics;
        this.vertx = vertx;
    }

    void onStart(@Observes StartupEvent ev) {
        if (config.mode() != BenchConfig.Mode.SENDER) {
            return;
        }
        if (config.messagesPerSecond() <= 0) {
            LOG.warn("messagesPerSecond is 0, sender will not start");
            return;
        }
        LOG.infof("Starting SENDER -> %s:%d @ %d msg/s, %dB payload",
                config.targetHost(), config.dataPort(),
                config.messagesPerSecond(), config.payloadBytes());

        socket = vertx.createDatagramSocket(new DatagramSocketOptions());
        payload = new byte[config.payloadBytes()];

        socket.listen(config.ackPort(), config.bindAddress())
                .onSuccess(s -> {
                    s.handler(packet -> {
                        long senderNanos = PrpMessage.senderNanos(packet.data());
                        long rtt = System.nanoTime() - senderNanos;
                        metrics.recordRtt(rtt);
                    });
                    LOG.infof("ACK listener bound to %s:%d", config.bindAddress(), config.ackPort());
                    initialized = true;
                    start();
                })
                .onFailure(t -> LOG.errorf(t, "Failed to bind ACK listener on %s:%d",
                        config.bindAddress(), config.ackPort()));
    }

    public void start() {
        if (running || !initialized) return;

        sequence.set(0);
        running = true;

        // Carry the fractional remainder between ticks. Rounding per tick instead
        // (rate/1000 + 1) overshoots badly at low rates -- 1500 msg/s became 2000 --
        // which silently pushes the link past capacity and shows up as latency
        // rather than as an obviously wrong send rate.
        final double perTick = config.messagesPerSecond() / 1000.0;
        final double[] credit = {0.0};

        sendTimerId = vertx.setPeriodic(1, id -> {
            if (!running) {
                vertx.cancelTimer(id);
                return;
            }
            credit[0] += perTick;
            int batch = (int) credit[0];
            credit[0] -= batch;

            for (int i = 0; i < batch; i++) {
                long seq = sequence.getAndIncrement();
                var buf = PrpMessage.encode(seq, System.nanoTime(), payload);
                socket.send(buf, config.dataPort(), config.targetHost())
                        .onSuccess(v -> metrics.recordSent());
            }
        });

        if (config.durationSeconds() > 0) {
            durationTimerId = vertx.setTimer((long) config.durationSeconds() * 1000, id -> stop());
        }

        LOG.info("Sender started");
    }

    public void stop() {
        running = false;
        if (sendTimerId >= 0) {
            vertx.cancelTimer(sendTimerId);
            sendTimerId = -1;
        }
        if (durationTimerId >= 0) {
            vertx.cancelTimer(durationTimerId);
            durationTimerId = -1;
        }
        LOG.info("Sender stopped");
    }

    public boolean isRunning() {
        return running;
    }
}
