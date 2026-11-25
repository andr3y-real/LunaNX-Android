package org.lunanx.android;

import java.util.Locale;

/** Aggregates the player's existing status samples on the playback worker. */
final class PlaybackReport {
    private static final int STREAMING = 3;
    private static final int EVENT_LIMIT = 12;
    private final long startedAt;
    private final long[][] events = new long[EVENT_LIMIT][5];
    private int eventIndex;
    private int eventCount;
    private long previousSample;
    private long previousBytes;
    private long previousReceived;
    private long previousDecoded;
    private long received;
    private long decoded;
    private long drops;
    private long resets;
    private long codecErrors;
    private long queueFull;
    private long maximumQueueMs;
    private long maximumJitterMs;
    private long maximumCodecAgeMs;
    private long measuredMs;
    private long measuredBytes;
    private long measuredReceived;
    private long measuredDecoded;
    private int zeroDecodedWindows;
    private long minimumDecodedFps = Long.MAX_VALUE;
    private int samples;
    private boolean reachedStreaming;

    PlaybackReport(long startedAt) {
        this.startedAt = startedAt;
    }

    void sample(long now, int phase, long bytes, long received, long decoded, String[] status) {
        long newDrops = Long.parseLong(status[6]);
        long newResets = Long.parseLong(status[7]);
        long newCodecErrors = Long.parseLong(status[10]);
        long newQueueFull = Long.parseLong(status[11]);
        if (newDrops > drops || newResets > resets || newCodecErrors > codecErrors || newQueueFull > queueFull) {
            long[] event = events[eventIndex];
            event[0] = (now - startedAt) / 1000;
            event[1] = newDrops - drops;
            event[2] = newResets - resets;
            event[3] = newCodecErrors - codecErrors;
            event[4] = newQueueFull - queueFull;
            eventIndex = (eventIndex + 1) % EVENT_LIMIT;
            eventCount = Math.min(eventCount + 1, EVENT_LIMIT);
        }
        drops = newDrops;
        resets = newResets;
        codecErrors = newCodecErrors;
        queueFull = newQueueFull;
        this.received = received;
        this.decoded = decoded;
        maximumQueueMs = Math.max(maximumQueueMs, Long.parseLong(status[8]));
        maximumJitterMs = Math.max(maximumJitterMs, Long.parseLong(status[9]));
        maximumCodecAgeMs = Math.max(maximumCodecAgeMs, Long.parseLong(status[13]));
        ++samples;
        if (phase != STREAMING) {
            previousSample = 0;
            return;
        }
        reachedStreaming = true;
        if (previousSample != 0 && now - previousSample >= 1000) {
            long elapsed = now - previousSample;
            long decodedDelta = decoded - previousDecoded;
            measuredMs += elapsed;
            measuredBytes += bytes - previousBytes;
            measuredReceived += received - previousReceived;
            measuredDecoded += decodedDelta;
            minimumDecodedFps = Math.min(minimumDecodedFps, Math.round(decodedDelta * 1000.0 / elapsed));
            if (decodedDelta == 0) ++zeroDecodedWindows;
        } else if (previousSample != 0) {
            return;
        }
        previousSample = now;
        previousBytes = bytes;
        previousReceived = received;
        previousDecoded = decoded;
    }

    String format(long now) {
        StringBuilder out = new StringBuilder(512);
        out.append("LunaNX · Relatório da partida\n");
        out.append("Duração: ").append(Math.max(0, now - startedAt) / 1000).append(" s\n");
        out.append("Chegou a transmitir: ").append(reachedStreaming ? "sim" : "não").append('\n');
        if (samples == 0) return out.append("Sem amostras de vídeo nesta partida.").toString();
        out.append("Quadros recebidos / decodificados: ").append(received).append(" / ").append(decoded).append('\n');
        if (measuredMs > 0) {
            out.append(String.format(Locale.ROOT, "FPS médio recebido / decodificado: %.1f / %.1f%n",
                measuredReceived * 1000.0 / measuredMs, measuredDecoded * 1000.0 / measuredMs));
            out.append(String.format(Locale.ROOT, "Bitrate médio recebido: %.1f Mb/s%n",
                measuredBytes * 0.008 / measuredMs));
            out.append("Menor FPS decodificado por janela: ").append(minimumDecodedFps).append('\n');
            out.append("Janelas de ~1 s sem decodificação: ").append(zeroDecodedWindows).append('\n');
        }
        out.append("Descartes RTP: ").append(drops).append('\n');
        out.append("Resets do decoder: ").append(resets)
            .append(" (").append(codecErrors).append(" por erro de codec)\n");
        out.append("Recuperações por fila cheia: ").append(queueFull).append('\n');
        out.append("Picos amostrados — fila: ").append(maximumQueueMs)
            .append(" ms · variação RTP: ").append(maximumJitterMs)
            .append(" ms · idade no codec: ").append(maximumCodecAgeMs).append(" ms\n");
        if (eventCount > 0) {
            out.append("\nÚltimos intervalos com ocorrências\n");
            for (int i = 0; i < eventCount; ++i) {
                long[] event = events[(eventIndex + EVENT_LIMIT - eventCount + i) % EVENT_LIMIT];
                out.append(event[0]).append(" s · RTP +").append(event[1])
                    .append(" · resets +").append(event[2])
                    .append(" · erros +").append(event[3])
                    .append(" · fila cheia +").append(event[4]).append('\n');
            }
        }
        return out.toString();
    }
}
