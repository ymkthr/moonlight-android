package com.limelight.binding.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

import com.limelight.LimeLog;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ThreadLocalRandom;

public class MicStreamer {
    public static final int SAMPLE_RATE = 48000;
    public static final int FRAME_SAMPLES = 480;
    public static final int DEFAULT_PORT = 46000;

    private final String host;
    private final int port;
    private volatile boolean stopRequested;
    private volatile Thread workerThread;
    private boolean running;

    public MicStreamer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public synchronized void start() {
        if (running) {
            return;
        }

        stopRequested = false;
        running = true;
        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runWorker();
            }
        }, "MicStreamer");
        workerThread.start();
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }

        stopRequested = true;
        Thread thread = workerThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        running = false;
        workerThread = null;
    }

    private void runWorker() {
        Thread worker = Thread.currentThread();
        AudioRecord record = null;
        AcousticEchoCanceler echoCanceler = null;
        NoiseSuppressor noiseSuppressor = null;
        DatagramSocket socket = null;
        boolean recording = false;

        try {
            InetAddress address = InetAddress.getByName(host);
            socket = new DatagramSocket();
            socket.connect(address, port);

            int minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBufferSize, FRAME_SAMPLES * 2 * 4);
            // VOICE_COMMUNICATION selects the platform's communication capture path.
            record = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                LimeLog.warning("Microphone AudioRecord failed to initialize");
                return;
            }

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(record.getAudioSessionId());
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(record.getAudioSessionId());
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                }
            }

            record.startRecording();
            recording = true;

            short[] samples = new short[FRAME_SAMPLES];
            byte[] output = new byte[RtpPacketizer.HEADER_SIZE + FRAME_SAMPLES * 2];
            DatagramPacket packet = new DatagramPacket(output, 0, output.length, address, port);
            RtpPacketizer packetizer = new RtpPacketizer(
                    ThreadLocalRandom.current().nextInt(),
                    ThreadLocalRandom.current().nextInt(),
                    ThreadLocalRandom.current().nextInt());

            while (!stopRequested && worker == workerThread) {
                int captured = 0;
                while (captured < FRAME_SAMPLES && !stopRequested && worker == workerThread) {
                    int read = record.read(samples, captured, FRAME_SAMPLES - captured);
                    if (read <= 0) {
                        LimeLog.warning("Microphone AudioRecord read failed: " + read);
                        break;
                    }
                    captured += read;
                }

                if (captured != FRAME_SAMPLES) {
                    break;
                }

                int packetLength = packetizer.packetize(samples, captured, output);
                packet.setLength(packetLength);
                try {
                    socket.send(packet);
                }
                catch (IOException e) {
                    LimeLog.warning("Microphone RTP send failed: " + e);
                }
            }
        }
        catch (Exception e) {
            LimeLog.warning("Microphone streamer failed: " + e);
        }
        finally {
            if (recording) {
                try {
                    record.stop();
                }
                catch (Exception ignored) {
                }
            }
            if (record != null) {
                record.release();
            }
            if (echoCanceler != null) {
                echoCanceler.release();
            }
            if (noiseSuppressor != null) {
                noiseSuppressor.release();
            }
            if (socket != null) {
                socket.close();
            }

            synchronized (this) {
                if (Thread.currentThread() == workerThread) {
                    running = false;
                    workerThread = null;
                }
            }
        }
    }
}
