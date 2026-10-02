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
    private static final int LARGE_RECEIVE_BUFFER_WARN_BYTES = 256 * 1024;

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

        var options = new DatagramSocketOptions();
        int rcvbuf = config.receiveBufferBytes();
        if (rcvbuf > 0) {
            // Vert.x applies this to Netty's receive-buffer allocator too, so every
            // read allocates and zeroes a buffer this large. Left unset, the socket
            // inherits net.core.rmem_default and Netty keeps its small read buffer.
            if (rcvbuf > LARGE_RECEIVE_BUFFER_WARN_BYTES) {
                LOG.warnf("receiveBufferBytes=%d also sizes Netty's per-read buffer; "
                        + "expect severely reduced throughput. Prefer 0 and tune "
                        + "net.core.rmem_default on the node.", rcvbuf);
            }
            options.setReceiveBufferSize(rcvbuf);
        }
        socket = vertx.createDatagramSocket(options);

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
                    LOG.infof("Receiver bound to %s:%d (rcvbuf %s, ACK every %d pkt)",
                            config.bindAddress(), config.dataPort(),
                            rcvbuf > 0 ? rcvbuf + " B" : "kernel default", ackEvery);
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
