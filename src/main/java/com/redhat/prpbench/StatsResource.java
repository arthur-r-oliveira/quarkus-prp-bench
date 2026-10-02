package com.redhat.prpbench;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/stats")
@Produces(MediaType.APPLICATION_JSON)
public class StatsResource {

    private static final Logger LOG = Logger.getLogger(StatsResource.class);

    /** Overrides for the next run; any null field keeps its current value. */
    public record StartRequest(Integer messagesPerSecond,
                               Integer payloadBytes,
                               Integer durationSeconds) {}

    private final BenchConfig config;
    private final BenchMetrics metrics;
    private final SenderService sender;
    private final ReceiverService receiver;
    private final ThroughputReporter throughputReporter;
    private final Vertx vertx;

    StatsResource(BenchConfig config, BenchMetrics metrics,
                  SenderService sender, ReceiverService receiver,
                  ThroughputReporter throughputReporter, Vertx vertx) {
        this.config = config;
        this.metrics = metrics;
        this.sender = sender;
        this.receiver = receiver;
        this.throughputReporter = throughputReporter;
        this.vertx = vertx;
    }

    @GET
    public Map<String, Object> stats() {
        long received = metrics.getReceived();
        long lost = metrics.getLost();
        long total = received + lost;
        double lossPercent = total > 0 ? (lost * 100.0 / total) : 0;

        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("messagesPerSecond", sender.getMessagesPerSecond());
        cfg.put("payloadBytes", sender.getPayloadBytes());
        cfg.put("durationSeconds", sender.getDurationSeconds());
        cfg.put("remainingSeconds", sender.getRemainingSeconds());
        cfg.put("targetHost", config.targetHost());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", config.mode().name());
        result.put("running", config.mode() == BenchConfig.Mode.SENDER
                ? sender.isRunning() : receiver.isRunning());
        result.put("sent", metrics.getSent());
        result.put("received", received);
        result.put("lost", lost);
        result.put("bytesTransferred", metrics.getBytesTransferred());
        result.put("lossPercent", Math.round(lossPercent * 100.0) / 100.0);
        result.put("throughputMbps", Math.round(metrics.getLastThroughputMbps() * 100.0) / 100.0);
        result.put("rtt", metrics.getRttPercentilesUs());
        result.put("jitter", metrics.getJitterPercentilesUs());
        result.put("config", cfg);
        return result;
    }

    @POST
    @Path("/start")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> start(StartRequest req) {
        if (req != null) {
            sender.configure(req.messagesPerSecond(), req.payloadBytes(), req.durationSeconds());
        }

        metrics.reset();
        throughputReporter.reset();
        sender.start();
        receiver.start();

        if (config.mode() == BenchConfig.Mode.SENDER) {
            resetPeer(config.targetHost());
        }

        return Map.of(
                "status", "started",
                "messagesPerSecond", sender.getMessagesPerSecond(),
                "payloadBytes", sender.getPayloadBytes(),
                "durationSeconds", sender.getDurationSeconds());
    }

    @POST
    @Path("/stop")
    public Map<String, String> stop() {
        sender.stop();
        receiver.stop();
        return Map.of("status", "stopped");
    }

    /**
     * Proxies the receiver's stats so the sender dashboard can show latency and
     * loss on one page. Read together they tell a story neither tells alone: a
     * run can report zero loss while sitting at seconds of queueing delay.
     */
    @GET
    @Path("/peer")
    public java.util.concurrent.CompletionStage<String> peer() {
        return vertx.createHttpClient()
                .request(HttpMethod.GET, 8080, config.targetHost(), "/api/stats")
                .compose(req -> req.send().compose(resp -> resp.body()))
                .map(io.vertx.core.buffer.Buffer::toString)
                .otherwise(t -> "{\"error\":\"" + t.getClass().getSimpleName() + "\"}")
                .toCompletionStage();
    }

    private void resetPeer(String peerHost) {
        vertx.createHttpClient()
                .request(HttpMethod.POST, 8080, peerHost, "/api/stats/start")
                // /start now declares @Consumes(APPLICATION_JSON); a body-less
                // POST would be rejected with 415.
                .compose(req -> req.putHeader("Content-Type", "application/json").send("{}"))
                .onSuccess(resp -> LOG.infof("Peer receiver reset at %s (status %d)", peerHost, resp.statusCode()))
                .onFailure(t -> LOG.warnf("Could not reset peer at %s: %s", peerHost, t.getMessage()));
    }
}
