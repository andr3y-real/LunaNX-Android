package org.lunanx.android;

import java.util.Locale;

/** Aggregates the player's existing status samples on the playback worker. */
final class PlaybackReport {
    private static final int STREAMING = 3;
    private static final int EVENT_LIMIT = 20;
    private static final String[] PHASE_NAMES = {
        "Preparando", "Na fila", "Conectando", "Jogando", "Encerrada", "Falhou"
    };
    private final long startedAt;
    private final String profile;
    private final String[] eventLines = new String[EVENT_LIMIT];
    private int eventIndex;
    private int eventCount;
    private long previousSample;
    private long previousBytes;
    private long previousReceived;
    private long previousDecoded;
    private long received;
    private long decoded;
    private long scheduled;
    private long drops;
    private long nonPictureUnits;
    private long waitingForKeyframeDrops;
    private long rtpGapsRecovered;
    private long rtpGapsExpired;
    private long resets;
    private long codecErrors;
    private long queueFull;
    private long maximumQueueMs;
    private long maximumJitterMs;
    private long maximumCodecDelayMs;
    private long codecPendingLast;
    private int lastPhase;
    private int renderedWidth;
    private int renderedHeight;
    private long rttCurrentMs = -1;
    private long rttMinMs = -1;
    private long rttP95Ms = -1;
    private long rttJitterMs = -1;
    private long rtpPacketsReceived;
    private long rtpSequenceGaps;
    private long rtpReorderedPackets;
    private long rtpLatePacketsDropped;
    private long rtpForcedSkips;
    private long rtpBurstFastForwards;
    private long nackRequests;
    private long nackPacketsRequested;
    private long rtxPacketsReceived;
    private long rtxPacketsRecovered;
    private long codecSubmitRetries;
    private long codecStaleDrops;
    private long keyframeRequests;
    private long measuredMs;
    private long measuredBytes;
    private long measuredReceived;
    private long measuredDecoded;
    private int zeroDecodedWindows;
    private int lowFpsWindows;
    private long minimumDecodedFps = Long.MAX_VALUE;
    private long maximumDecodedFps;
    private int samples;
    private boolean reachedStreaming;

    PlaybackReport(long startedAt) {
        this(startedAt, "");
    }

    PlaybackReport(long startedAt, String profile) {
        this.startedAt = startedAt;
        this.profile = profile;
    }

    private static long parseLongSafe(String[] status, int index, long fallback) {
        if (status == null || index < 0 || index >= status.length || status[index] == null)
            return fallback;
        try {
            return Long.parseLong(status[index]);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    void sample(long now, int phase, long bytes, long received, long decoded, String[] status) {
        lastPhase = phase;
        long newDrops = parseLongSafe(status, 6, drops);
        long newResets = parseLongSafe(status, 7, resets);
        long queueMs = parseLongSafe(status, 8, 0);
        long jitterMs = parseLongSafe(status, 9, 0);
        long newCodecErrors = parseLongSafe(status, 10, codecErrors);
        long newQueueFull = parseLongSafe(status, 11, queueFull);
        codecPendingLast = parseLongSafe(status, 12, codecPendingLast);
        long codecDelayMs = parseLongSafe(status, 13, 0);
        long newWaitingDrops = parseLongSafe(status, 15, waitingForKeyframeDrops);

        if (status.length >= 23) {
            int rw = (int) parseLongSafe(status, 21, renderedWidth);
            int rh = (int) parseLongSafe(status, 22, renderedHeight);
            if (rw > 0 && rh > 0) {
                renderedWidth = rw;
                renderedHeight = rh;
            }
        }
        long newRtpGaps = rtpSequenceGaps;
        long newNack = nackRequests;
        long newRtx = rtxPacketsRecovered;
        long newRetries = codecSubmitRetries;
        long newStaleDrops = codecStaleDrops;
        long newKfReq = keyframeRequests;
        if (status.length >= 40) {
            rttCurrentMs = parseLongSafe(status, 23, rttCurrentMs);
            long minRtt = parseLongSafe(status, 24, -1);
            if (minRtt >= 0) rttMinMs = (rttMinMs < 0) ? minRtt : Math.min(rttMinMs, minRtt);
            long p95Rtt = parseLongSafe(status, 25, -1);
            if (p95Rtt >= 0) rttP95Ms = Math.max(rttP95Ms, p95Rtt);
            rttJitterMs = parseLongSafe(status, 26, rttJitterMs);
            rtpPacketsReceived = parseLongSafe(status, 27, rtpPacketsReceived);
            newRtpGaps = parseLongSafe(status, 28, rtpSequenceGaps);
            rtpReorderedPackets = parseLongSafe(status, 29, rtpReorderedPackets);
            rtpLatePacketsDropped = parseLongSafe(status, 30, rtpLatePacketsDropped);
            rtpForcedSkips = parseLongSafe(status, 31, rtpForcedSkips);
            rtpBurstFastForwards = parseLongSafe(status, 32, rtpBurstFastForwards);
            newNack = parseLongSafe(status, 33, nackRequests);
            nackPacketsRequested = parseLongSafe(status, 34, nackPacketsRequested);
            rtxPacketsReceived = parseLongSafe(status, 35, rtxPacketsReceived);
            newRtx = parseLongSafe(status, 36, rtxPacketsRecovered);
            newRetries = parseLongSafe(status, 37, codecSubmitRetries);
            newStaleDrops = parseLongSafe(status, 38, codecStaleDrops);
            newKfReq = parseLongSafe(status, 39, keyframeRequests);
        }

        nonPictureUnits = parseLongSafe(status, 14, nonPictureUnits);
        rtpGapsRecovered = parseLongSafe(status, 16, rtpGapsRecovered);
        rtpGapsExpired = parseLongSafe(status, 17, rtpGapsExpired);
        this.received = received;
        this.decoded = decoded;
        scheduled = parseLongSafe(status, 3, scheduled);
        maximumQueueMs = Math.max(maximumQueueMs, Math.max(queueMs, parseLongSafe(status, 40, 0)));
        maximumJitterMs = Math.max(maximumJitterMs, jitterMs);
        maximumCodecDelayMs = Math.max(maximumCodecDelayMs, codecDelayMs);
        ++samples;

        long decodedWindowFps = -1;
        long receivedWindowFps = -1;
        if (phase != STREAMING) {
            previousSample = 0;
        } else {
            reachedStreaming = true;
            if (previousSample != 0 && now - previousSample >= 1000) {
                long elapsed = now - previousSample;
                long decodedDelta = decoded - previousDecoded;
                long receivedDelta = received - previousReceived;
                measuredMs += elapsed;
                measuredBytes += bytes - previousBytes;
                measuredReceived += receivedDelta;
                measuredDecoded += decodedDelta;
                decodedWindowFps = Math.round(decodedDelta * 1000.0 / elapsed);
                receivedWindowFps = Math.round(receivedDelta * 1000.0 / elapsed);
                minimumDecodedFps = Math.min(minimumDecodedFps, decodedWindowFps);
                maximumDecodedFps = Math.max(maximumDecodedFps, decodedWindowFps);
                if (decodedDelta == 0) ++zeroDecodedWindows;
                int targetMinFps = profile.contains("30 FPS") ? 24 : 48;
                if (decodedWindowFps < targetMinFps) ++lowFpsWindows;
            }
            if (previousSample == 0 || now - previousSample >= 1000) {
                previousSample = now;
                previousBytes = bytes;
                previousReceived = received;
                previousDecoded = decoded;
            }
        }

        boolean rttSpike = rttMinMs >= 0 && rttCurrentMs - rttMinMs >= 25;
        boolean lowFps = decodedWindowFps >= 0 &&
            decodedWindowFps < (profile.contains("30 FPS") ? 24 : 48);
        boolean hasIncident = newDrops > drops || newResets > resets || newCodecErrors > codecErrors ||
            newQueueFull > queueFull || newWaitingDrops > waitingForKeyframeDrops ||
            newRtpGaps > rtpSequenceGaps || newNack > nackRequests ||
            newRetries > codecSubmitRetries || newStaleDrops > codecStaleDrops ||
            newKfReq > keyframeRequests || queueMs >= 45 || rttSpike || lowFps;
        if (hasIncident) {
            StringBuilder ev = new StringBuilder(128);
            ev.append((now - startedAt) / 1000).append("s");
            if (decodedWindowFps >= 0) {
                ev.append(" · FPS ").append(receivedWindowFps).append('/').append(decodedWindowFps);
            }
            if (rttCurrentMs >= 0) ev.append(" · RTT ").append(rttCurrentMs).append("ms");
            if (queueMs > 0) ev.append(" · fila ").append(queueMs).append("ms");
            if (newRtpGaps > rtpSequenceGaps) ev.append(" · gaps+").append(newRtpGaps - rtpSequenceGaps);
            if (newNack > nackRequests) ev.append(" · nack+").append(newNack - nackRequests);
            if (newRtx > rtxPacketsRecovered) ev.append(" · rtx+").append(newRtx - rtxPacketsRecovered);
            if (newDrops > drops) ev.append(" · auDrop+").append(newDrops - drops);
            if (newWaitingDrops > waitingForKeyframeDrops) ev.append(" · waitKey+").append(newWaitingDrops - waitingForKeyframeDrops);
            if (newKfReq > keyframeRequests) ev.append(" · pli+").append(newKfReq - keyframeRequests);
            if (newRetries > codecSubmitRetries) ev.append(" · retry+").append(newRetries - codecSubmitRetries);
            if (newStaleDrops > codecStaleDrops) ev.append(" · stale+").append(newStaleDrops - codecStaleDrops);
            if (newQueueFull > queueFull) ev.append(" · filaCheia+").append(newQueueFull - queueFull);
            if (newResets > resets) ev.append(" · reset+").append(newResets - resets);
            if (newCodecErrors > codecErrors) ev.append(" · codecErr+").append(newCodecErrors - codecErrors);
            eventLines[eventIndex] = ev.toString();
            eventIndex = (eventIndex + 1) % EVENT_LIMIT;
            eventCount = Math.min(eventCount + 1, EVENT_LIMIT);
        }

        drops = newDrops;
        resets = newResets;
        codecErrors = newCodecErrors;
        queueFull = newQueueFull;
        waitingForKeyframeDrops = newWaitingDrops;
        rtpSequenceGaps = newRtpGaps;
        nackRequests = newNack;
        rtxPacketsRecovered = newRtx;
        codecSubmitRetries = newRetries;
        codecStaleDrops = newStaleDrops;
        keyframeRequests = newKfReq;
    }

    String[] highlights(long now) {
        long seconds = Math.max(0, now - startedAt) / 1000;
        String duration = String.format(Locale.ROOT, "%d min %02d s", seconds / 60, seconds % 60);
        if (!reachedStreaming) duration += " · transmissão não iniciada";
        return new String[]{
            duration,
            measuredMs > 0 ? String.format(Locale.ROOT, "%.1f FPS", measuredDecoded * 1000.0 / measuredMs) : "—",
            measuredMs > 0 ? String.format(Locale.ROOT, "%.1f Mb/s", measuredBytes * 0.008 / measuredMs) : "—",
            decoded > 0 ? String.format(Locale.ROOT, "%.1f%%", Math.min(scheduled, decoded) * 100.0 / decoded) : "—",
            measuredMs > 0 ? (zeroDecodedWindows + " / " + lowFpsWindows) : "—"
        };
    }

    String format(long now) {
        StringBuilder out = new StringBuilder(1024);
        out.append("LunaNX · Raio-X da partida\n");
        out.append("Duração: ").append(Math.max(0, now - startedAt) / 1000).append(" s · Última fase: ")
            .append(lastPhase);
        if (lastPhase >= 0 && lastPhase < PHASE_NAMES.length)
            out.append(" (").append(PHASE_NAMES[lastPhase]).append(')');
        out.append('\n');
        out.append("Chegou a transmitir: ").append(reachedStreaming ? "sim" : "não").append('\n');
        if (!profile.isEmpty()) out.append("Perfil solicitado: ").append(profile).append('\n');
        if (renderedWidth > 0 && renderedHeight > 0) {
            out.append("Resolução decodificada real: ").append(renderedWidth).append('x').append(renderedHeight).append('\n');
        }
        if (samples == 0) return out.append("Sem amostras de vídeo nesta partida.").toString();

        out.append("\n[Vídeo & FPS]\n");
        out.append("Quadros recebidos / decodificados / enviados à tela: ").append(received)
            .append(" / ").append(decoded).append(" / ").append(scheduled).append('\n');
        out.append("Descartados por backlog (stale) / cadência: ").append(codecStaleDrops)
            .append(" / ").append(Math.max(0, decoded - scheduled - codecStaleDrops)).append('\n');
        if (measuredMs > 0) {
            out.append(String.format(Locale.ROOT, "FPS médio recebido / decodificado: %.1f / %.1f%n",
                measuredReceived * 1000.0 / measuredMs, measuredDecoded * 1000.0 / measuredMs));
            out.append("FPS mínimo / máximo por janela: ")
                .append(minimumDecodedFps == Long.MAX_VALUE ? 0 : minimumDecodedFps)
                .append(" / ").append(maximumDecodedFps).append('\n');
            out.append("Janelas de 1s zeradas / abaixo do alvo: ").append(zeroDecodedWindows)
                .append(" / ").append(lowFpsWindows).append('\n');
            out.append(String.format(Locale.ROOT, "Bitrate médio recebido: %.2f Mb/s%n",
                measuredBytes * 0.008 / measuredMs));
        }

        out.append("\n[Rede, RTT & Recuperação RTP]\n");
        if (rttCurrentMs >= 0 || rttMinMs >= 0 || rttP95Ms >= 0) {
            out.append("RTT atual / mín / p95 / jitter rede: ")
                .append(rttCurrentMs).append(" / ").append(rttMinMs).append(" / ")
                .append(rttP95Ms).append(" / ").append(rttJitterMs).append(" ms\n");
        }
        out.append("Pico jitter de chegada RTP: ").append(maximumJitterMs).append(" ms\n");
        out.append("Pacotes RTP de vídeo processados: ").append(rtpPacketsReceived)
            .append(" · gaps: ").append(rtpSequenceGaps)
            .append(" · reordenados: ").append(rtpReorderedPackets)
            .append(" · atrasados: ").append(rtpLatePacketsDropped).append('\n');
        out.append("Lacunas recuperadas / expiradas / fast-forward / skips: ")
            .append(rtpGapsRecovered).append(" / ").append(rtpGapsExpired)
            .append(" / ").append(rtpBurstFastForwards).append(" / ").append(rtpForcedSkips).append('\n');
        out.append("NACK enviados / pacotes pedidos / RTX recebidos / aproveitados: ")
            .append(nackRequests).append(" / ").append(nackPacketsRequested)
            .append(" / ").append(rtxPacketsReceived).append(" / ").append(rtxPacketsRecovered).append('\n');
        out.append("Unidades AU danificadas / sem imagem: ").append(drops)
            .append(" / ").append(nonPictureUnits).append('\n');

        out.append("\n[Decodificador Android & Fila]\n");
        out.append("Tentativas de PLI / descartes aguardando IDR: ").append(keyframeRequests)
            .append(" / ").append(waitingForKeyframeDrops).append('\n');
        out.append("Retries de input no MediaCodec / pendentes no fim: ").append(codecSubmitRetries)
            .append(" / ").append(codecPendingLast).append('\n');
        out.append("Resets do decoder (erros de codec) / fila cheia: ").append(resets)
            .append(" (").append(codecErrors).append(") / ").append(queueFull).append('\n');
        out.append("Picos de espera na fila / tempo até a saída do codec: ").append(maximumQueueMs)
            .append(" ms / ").append(maximumCodecDelayMs).append(" ms\n");

        if (eventCount > 0) {
            out.append("\n[Ocorrências · FPS recebido/decodificado · até ").append(EVENT_LIMIT).append("]\n");
            for (int i = 0; i < eventCount; ++i) {
                String line = eventLines[(eventIndex + EVENT_LIMIT - eventCount + i) % EVENT_LIMIT];
                if (line != null) out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
