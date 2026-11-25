package org.lunanx.android;

public final class PlayerFramingTest {
    private static void check(PlayerFraming mode, int width, int height, float x, float y) {
        if (Math.abs(mode.scaleX(width, height) - x) > 0.001f ||
            Math.abs(mode.scaleY(width, height) - y) > 0.001f) {
            throw new AssertionError(mode + " computed the wrong scale for " + width + "×" + height);
        }
    }

    public static void main(String[] args) {
        check(PlayerFraming.FIT, 1600, 720, .8f, 1f);
        check(PlayerFraming.FILL, 1600, 720, 1f, 1.25f);
        check(PlayerFraming.STRETCH, 1600, 720, 1f, 1f);
        check(PlayerFraming.FIT, 1024, 768, 1f, .75f);
        check(PlayerFraming.FILL, 1024, 768, 4f / 3f, 1f);
        for (PlayerFraming mode : PlayerFraming.values()) {
            check(mode, 1280, 720, 1f, 1f);
            check(mode, 0, 0, 1f, 1f);
            if (PlayerFraming.fromPreference(mode.name()) != mode)
                throw new AssertionError("Failed to restore " + mode);
        }
        if (PlayerFraming.fromPreference("invalid") != PlayerFraming.FIT)
            throw new AssertionError("Invalid preferences must fall back to Fit");
        System.out.println("Framing geometry and preferences passed");
    }
}
