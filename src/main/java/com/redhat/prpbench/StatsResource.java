package com.redhat.prpbench;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/stats")
@Produces(MediaType.APPLICATION_JSON)
public class StatsResource {

    private final BenchConfig config;
    private final BenchMetrics metrics;
    private final SenderService sender;
    private final ReceiverService receiver;

    StatsResource(BenchConfig config, BenchMetrics metrics,
                  SenderService sender, ReceiverService receiver) {
        this.config = config;
        this.metrics = metrics;
        this.sender = sender;
        this.receiver = receiver;
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
    @Path("/stop")
    public Map<String, String> stop() {
        sender.stop();
        receiver.stop();
        return Map.of("status", "stopped");
    }
}
