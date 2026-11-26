package org.lunanx.android;

public final class PlaybackReportTest {
    public static void main(String[] args) {
        PlaybackReport report = new PlaybackReport(100, "Teste A · 720p · 30 FPS · até 5 Mb/s");
        require(report.format(500), "Sem amostras");
        require(report.highlights(500)[0], "transmissão não iniciada");
        require(report.highlights(500)[1], "—");
        String[] status = {"3", "texto privado", "0", "0", "0", "0", "0",
            "0", "3", "4", "0", "0", "0", "6", "0", "0", "0", "0"};
        report.sample(100, 3, 0, 0, 0, status);
        status[3] = "56";
        status[6] = "2";
        status[7] = "1";
        status[13] = "90";
        status[14] = "5";
        status[15] = "3";
        status[16] = "4";
        status[17] = "1";
        report.sample(1100, 3, 1_000_000, 60, 60, status);
        report.sample(2100, 3, 2_000_000, 120, 60, status);
        String[] highlights = report.highlights(2300);
        require(highlights[0], "0 min 02 s");
        require(highlights[1], "30.0 FPS");
        require(highlights[2], "8.0 Mb/s");
        require(highlights[3], "93.3%");
        require(highlights[4], "1");
        String summary = report.format(2300);
        require(summary, "Perfil solicitado: Teste A · 720p · 30 FPS · até 5 Mb/s");
        require(summary, "60.0 / 30.0");
        require(summary, "8.00 Mb/s");
        require(summary, "Última fase: 3 (Jogando)");
        require(summary, "Quadros recebidos / decodificados / enviados à tela: 120 / 60 / 56");
        require(summary, "backlog (stale) / cadência: 0 / 4");
        require(summary, "Janelas de 1s zeradas / abaixo do alvo: 1 / 1");
        require(summary, "Resets do decoder (erros de codec) / fila cheia: 1 (0) / 0");
        require(summary, "auDrop+2");
        require(summary, "waitKey+3");
        require(summary, "Unidades AU danificadas / sem imagem: 2 / 5");
        require(summary, "Lacunas recuperadas / expiradas / fast-forward / skips: 4 / 1 / 0 / 0");
        require(summary, "Picos de espera na fila / tempo até a saída do codec: 3 ms / 90 ms");
        if (summary.contains("texto privado")) throw new AssertionError("Status text leaked");
        for (int i = 0; i < 24; ++i) {
            status[7] = String.valueOf(i + 2);
            report.sample(3300 + i * 1000L, 3, 2_000_000, 120, 60, status);
        }
        String wrapped = report.format(28000);
        if (wrapped.contains("auDrop+2")) throw new AssertionError("Event ring did not wrap");
        if (wrapped.split(" · reset\\+", -1).length != 21) throw new AssertionError("Unexpected event count");

        PlaybackReport live = new PlaybackReport(100, "60 FPS");
        String[] extended = java.util.Arrays.copyOf(status, 45);
        extended[6] = "0";
        extended[7] = "0";
        extended[15] = "0";
        extended[23] = "92";
        extended[24] = "90";
        extended[40] = "117";
        live.sample(100, 3, 0, 0, 0, extended);
        live.sample(1100, 3, 500_000, 60, 60, extended);
        if (live.format(1100).contains("Ocorrências"))
            throw new AssertionError("Steady RTT created an incident");
        extended[3] = "96";
        extended[23] = "123";
        extended[38] = "3";
        extended[41] = "2";
        extended[42] = "6";
        extended[43] = "17";
        extended[44] = "29";
        live.sample(2100, 3, 900_000, 110, 100, extended);
        String incident = live.format(2100);
        require(incident, "FPS 50/40");
        require(incident, "backlog (stale) / cadência: 3 / 1");
        require(incident, "117 ms / 90 ms");
        require(incident, "Reinícios por falta de saída do codec: 2");
        require(incident, "Quadros com 250 ms ou mais no codec: 6");
        require(incident, "Pico da reserva adaptativa de apresentação: 17 ms");
        require(incident, "Quadros reaproveitados ao esperar vaga na tela: 29");
    }

    private static void require(String value, String expected) {
        if (!value.contains(expected)) throw new AssertionError("Missing: " + expected + "\n" + value);
    }
}
