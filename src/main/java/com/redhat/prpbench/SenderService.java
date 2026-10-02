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

    private static final int MAX_PAYLOAD_BYTES = 65_000;
    private static final int MAX_MESSAGES_PER_SECOND = 10_000_000;

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

    // Seeded from BenchConfig, then adjustable at runtime so a rate sweep does
    // not need an env change and a pod restart per step.
    private volatile int messagesPerSecond;
    private volatile int payloadBytes;
    private volatile int durationSeconds;
    private volatile long startedAtMillis = 0;

    SenderService(BenchConfig config, BenchMetrics metrics, Vertx vertx) {
        this.config = config;
        this.metrics = metrics;
        this.vertx = vertx;
    }

    void onStart(@Observes StartupEvent ev) {
        messagesPerSecond = config.messagesPerSecond();
        payloadBytes = config.payloadBytes();
        durationSeconds = config.durationSeconds();

        if (config.mode() != BenchConfig.Mode.SENDER) {
            return;
        }
        LOG.infof("Starting SENDER -> %s:%d @ %d msg/s, %dB payload",
                config.targetHost(), config.dataPort(), messagesPerSecond, payloadBytes);

        socket = vertx.createDatagramSocket(new DatagramSocketOptions());
        payload = new byte[payloadBytes];

        socket.listen(config.ackPort(), config.bindAddress())
                .onSuccess(s -> {
                    s.handler(packet -> {
                        long senderNanos = PrpMessage.senderNanos(packet.data());
                        metrics.recordRtt(System.nanoTime() - senderNanos);
                    });
                    LOG.infof("ACK listener bound to %s:%d", config.bindAddress(), config.ackPort());
                    initialized = true;
                    start();
                })
                .onFailure(t -> LOG.errorf(t, "Failed to bind ACK listener on %s:%d",
                        config.bindAddress(), config.ackPort()));
    }

    /** Applies overrides for the next run. Null fields keep their current value. */
    public void configure(Integer newRate, Integer newPayloadBytes, Integer newDurationSeconds) {
        if (newRate != null) {
            messagesPerSecond = Math.min(Math.max(newRate, 0), MAX_MESSAGES_PER_SECOND);
        }
        if (newPayloadBytes != null) {
            payloadBytes = Math.min(Math.max(newPayloadBytes, 0), MAX_PAYLOAD_BYTES);
            payload = new byte[payloadBytes];
        }
        if (newDurationSeconds != null) {
            durationSeconds = Math.max(newDurationSeconds, 0);
        }
    }

    public void start() {
        if (running || !initialized) return;
        if (messagesPerSecond <= 0) {
            LOG.warn("messagesPerSecond is 0, sender will not start");
            return;
        }

        sequence.set(0);
        running = true;
        startedAtMillis = System.currentTimeMillis();

        // Carry the fractional remainder between ticks. Rounding per tick instead
        // (rate/1000 + 1) overshoots badly at low rates -- 1500 msg/s became 2000 --
        // which silently pushes the link past capacity and shows up as latency
        // rather than as an obviously wrong send rate.
        final double perTick = messagesPerSecond / 1000.0;
        final double[] credit = {0.0};
        final byte[] activePayload = payload;

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
                var buf = PrpMessage.encode(seq, System.nanoTime(), activePayload);
                socket.send(buf, config.dataPort(), config.targetHost())
                        .onSuccess(v -> metrics.recordSent());
            }
        });

        if (durationSeconds > 0) {
            durationTimerId = vertx.setTimer((long) durationSeconds * 1000, id -> stop());
        }

        LOG.infof("Sender started @ %d msg/s, %dB payload, duration %ds",
                messagesPerSecond, payloadBytes, durationSeconds);
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

    public int getMessagesPerSecond() { return messagesPerSecond; }
    public int getPayloadBytes() { return payloadBytes; }
    public int getDurationSeconds() { return durationSeconds; }

    /** Seconds left in the current run, or -1 when unbounded or not running. */
    public long getRemainingSeconds() {
        if (!running || durationSeconds <= 0) return -1;
        long elapsed = (System.currentTimeMillis() - startedAtMillis) / 1000;
        return Math.max(0, durationSeconds - elapsed);
    }
}
