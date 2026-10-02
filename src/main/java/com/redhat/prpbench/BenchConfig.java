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

    /** ACK every Nth packet for RTT sampling. 1 = ACK every packet. */
    @WithDefault("100")
    int ackSampleRate();

    /**
     * 0 (default) leaves the socket buffer to the kernel's net.core.rmem_default,
     * which k8s/node-tuning.yaml raises. Prefer that over setting a value here.
     * <p>
     * Vert.x applies this figure to Netty's receive-buffer allocator as well as to
     * SO_RCVBUF, so a large value makes Netty allocate and zero a buffer of this
     * size on every read. Measured on the PRP lab: 8 MB gave 1,966 pkt/s against
     * 9,986 pkt/s at 256 KB. Anything beyond a few tens of KB costs throughput.
     */
    @WithDefault("0")
    int receiveBufferBytes();
}
