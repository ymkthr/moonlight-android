package com.limelight.binding.audio;

public final class RtpPacketizer {
    public static final int HEADER_SIZE = 12;
    public static final int PAYLOAD_TYPE = 96;

    private final int ssrc;
    private int sequence;
    private int timestamp;

    public RtpPacketizer(int ssrc, int initialSequence, int initialTimestamp) {
        this.ssrc = ssrc;
        this.sequence = initialSequence & 0xffff;
        this.timestamp = initialTimestamp;
    }

    public int packetize(short[] samples, int count, byte[] out) {
        out[0] = (byte) 0x80;
        out[1] = (byte) PAYLOAD_TYPE;
        out[2] = (byte) (sequence >>> 8);
        out[3] = (byte) sequence;
        writeInt(out, 4, timestamp);
        writeInt(out, 8, ssrc);

        // RTP fields and the receiver payload use network byte order.
        int offset = HEADER_SIZE;
        for (int i = 0; i < count; i++) {
            short sample = samples[i];
            out[offset++] = (byte) (sample >>> 8);
            out[offset++] = (byte) sample;
        }

        sequence = (sequence + 1) & 0xffff;
        timestamp += count;
        return offset;
    }

    private static void writeInt(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }
}
