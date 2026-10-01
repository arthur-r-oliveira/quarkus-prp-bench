package com.redhat.prpbench;

import io.vertx.core.buffer.Buffer;

/**
 * Wire format: [8B sequence][8B senderTimestampNanos][NB payload]
 */
public final class PrpMessage {

    static final int HEADER_SIZE = 16;

    private PrpMessage() {}

    public static Buffer encode(long sequence, long senderNanos, int payloadBytes) {
        return encode(sequence, senderNanos, new byte[Math.max(payloadBytes, 0)]);
    }

    public static Buffer encode(long sequence, long senderNanos, byte[] payload) {
        Buffer buf = Buffer.buffer(HEADER_SIZE + payload.length);
        buf.appendLong(sequence);
        buf.appendLong(senderNanos);
        if (payload.length > 0) {
            buf.appendBytes(payload);
        }
        return buf;
    }

    public static long sequence(Buffer buf) {
        return buf.getLong(0);
    }

    public static long senderNanos(Buffer buf) {
        return buf.getLong(8);
    }
}
