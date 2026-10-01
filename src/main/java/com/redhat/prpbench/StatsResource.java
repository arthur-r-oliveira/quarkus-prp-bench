package com.redhat.prpbench;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
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
        return Map.of(
                "mode", config.mode().name(),
                "running", config.mode() == BenchConfig.Mode.SENDER
                        ? sender.isRunning() : receiver.isRunning(),
                "sent", metrics.getSent(),
                "received", metrics.getReceived(),
                "lost", metrics.getLost(),
                "bytesTransferred", metrics.getBytesTransferred()
        );
    }

    @POST
    @Path("/stop")
    public Map<String, String> stop() {
        sender.stop();
        receiver.stop();
        return Map.of("status", "stopped");
    }
}
