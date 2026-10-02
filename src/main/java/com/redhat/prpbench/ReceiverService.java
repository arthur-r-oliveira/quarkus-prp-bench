package com.redhat.prpbench;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.datagram.DatagramSocketOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ReceiverService {

    private static final Logger LOG = Logger.getLogger(ReceiverService.class);

    private final BenchConfig config;
    private final BenchMetrics metrics;
    private final Vertx vertx;

    private volatile boolean running = false;
    private DatagramSocket socket;

    ReceiverService(BenchConfig config, BenchMetrics metrics, Vertx vertx) {
        this.config = config;
        this.metrics = metrics;
        this.vertx = vertx;
    }

    void onStart(@Observes StartupEvent ev) {
        if (config.mode() != BenchConfig.Mode.RECEIVER) {
            return;
        }
        LOG.infof("Starting RECEIVER on %s:%d", config.bindAddress(), config.dataPort());

        socket = vertx.createDatagramSocket(new DatagramSocketOptions()
                .setReceiveBufferSize(config.receiveBufferBytes()));

        int ackEvery = Math.max(1, config.ackSampleRate());

        socket.listen(config.dataPort(), config.bindAddress())
                .onSuccess(s -> {
                    running = true;
                    s.handler(packet -> {
                        if (!running) return;

                        var data = packet.data();
                        long seq = PrpMessage.sequence(data);
                        metrics.recordReceived(seq, data.length());

                        if (seq % ackEvery == 0) {
                            var ack = PrpMessage.encode(seq, PrpMessage.senderNanos(data), 0);
                            socket.send(ack, config.ackPort(), packet.sender().host());
                        }
                    });
                    LOG.infof("Receiver bound to %s:%d (rcvbuf requested %d B, ACK every %d pkt)",
                            config.bindAddress(), config.dataPort(),
                            config.receiveBufferBytes(), ackEvery);
                })
                .onFailure(t -> LOG.errorf(t, "Failed to bind receiver on %s:%d",
                        config.bindAddress(), config.dataPort()));
    }

    public void start() {
        running = true;
        LOG.info("Receiver started");
    }

    public void stop() {
        running = false;
        LOG.info("Receiver stopped");
    }

    public boolean isRunning() {
        return running;
    }
}
