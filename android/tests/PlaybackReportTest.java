package org.lunanx.android;

public final class PlaybackReportTest {
    public static void main(String[] args) {
        PlaybackReport report = new PlaybackReport(100);
        require(report.format(500), "Sem amostras");
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
        String summary = report.format(2300);
        require(summary, "60.0 / 30.0");
        require(summary, "8.0 Mb/s");
        require(summary, "Quadros programados para a tela: 56");
        require(summary, "cadência na saída: 4");
        require(summary, "Janelas de ~1 s sem decodificação: 1");
        require(summary, "Resets do decoder: 1");
        require(summary, "RTP +2");
        require(summary, "sem imagem: 5");
        require(summary, "aguardando imagem-chave: 3");
        require(summary, "aguardando chave +3");
        require(summary, "Lacunas RTP recuperadas / expiradas: 4 / 1");
        require(summary, "entrada até saída do codec: 90 ms");
        if (summary.contains("texto privado")) throw new AssertionError("Status text leaked");
        for (int i = 0; i < 16; ++i) {
            status[7] = String.valueOf(i + 2);
            report.sample(3300 + i * 1000L, 3, 2_000_000, 120, 60, status);
        }
        String wrapped = report.format(20000);
        if (wrapped.contains("RTP +2")) throw new AssertionError("Event ring did not wrap");
        if (wrapped.split(" · resets ", -1).length != 13) throw new AssertionError("Unexpected event count");
    }

    private static void require(String value, String expected) {
        if (!value.contains(expected)) throw new AssertionError("Missing: " + expected + "\n" + value);
    }
}
