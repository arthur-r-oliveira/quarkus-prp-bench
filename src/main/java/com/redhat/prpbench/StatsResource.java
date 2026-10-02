package com.redhat.prpbench;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
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
        return result;
    }

    @POST
    @Path("/start")
    public Map<String, String> start() {
        metrics.reset();
        throughputReporter.reset();
        sender.start();
        receiver.start();

        if (config.mode() == BenchConfig.Mode.SENDER) {
            resetPeer(config.targetHost());
        }

        return Map.of("status", "started");
    }

    @POST
    @Path("/stop")
    public Map<String, String> stop() {
        sender.stop();
        receiver.stop();
        return Map.of("status", "stopped");
    }

    private void resetPeer(String peerHost) {
        vertx.createHttpClient()
                .request(HttpMethod.POST, 8080, peerHost, "/api/stats/start")
                .compose(req -> req.send())
                .onSuccess(resp -> LOG.infof("Peer receiver reset at %s (status %d)", peerHost, resp.statusCode()))
                .onFailure(t -> LOG.warnf("Could not reset peer at %s: %s", peerHost, t.getMessage()));
    }
}
