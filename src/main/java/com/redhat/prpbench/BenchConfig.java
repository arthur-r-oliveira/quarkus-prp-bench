package com.redhat.prpbench;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "prp-bench")
public interface BenchConfig {

    enum Mode { SENDER, RECEIVER }

    @WithDefault("SENDER")
    Mode mode();

    @WithDefault("10.10.10.2")
    String targetHost();

    @WithDefault("9200")
    int dataPort();

    @WithDefault("9201")
    int ackPort();

    @WithDefault("256")
    int payloadBytes();

    @WithDefault("50000")
    int messagesPerSecond();

    @WithDefault("0")
    int durationSeconds();

    @WithDefault("10.10.10.1")
    String bindAddress();
}
