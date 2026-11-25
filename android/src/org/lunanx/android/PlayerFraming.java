package org.lunanx.android;

enum PlayerFraming {
    FIT("Ajustar", "Imagem inteira, com barras se precisar"),
    FILL("Preencher", "Mantém a proporção e corta as bordas"),
    STRETCH("Esticar", "Ocupa a tela toda, ajustando a proporção");

    static final int VIDEO_WIDTH = 1280;
    static final int VIDEO_HEIGHT = 720;

    final String label;
    final String description;

    PlayerFraming(String label, String description) {
        this.label = label;
        this.description = description;
    }

    static PlayerFraming fromPreference(String preference) {
        for (PlayerFraming option : values()) {
            if (option.name().equals(preference)) return option;
        }
        return FIT;
    }

    float scaleX(int width, int height) {
        if (width <= 0 || height <= 0 || this == STRETCH) return 1f;
        float ratio = (float) height * VIDEO_WIDTH / (width * VIDEO_HEIGHT);
        return this == FIT ? Math.min(1f, ratio) : Math.max(1f, ratio);
    }

    float scaleY(int width, int height) {
        if (width <= 0 || height <= 0 || this == STRETCH) return 1f;
        float ratio = (float) width * VIDEO_HEIGHT / (height * VIDEO_WIDTH);
        return this == FIT ? Math.min(1f, ratio) : Math.max(1f, ratio);
    }
}
