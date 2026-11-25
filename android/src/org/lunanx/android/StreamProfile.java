package org.lunanx.android;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class StreamProfile {
    private static final String SAVED = "custom_stream_profiles";
    private static final String ACTIVE = "active_stream_profile";
    private static final StreamProfile DEFAULT = new StreamProfile(
        "balanced", "Equilibrado", 720, 60, 10, null, false);
    static final int MIN_BITRATE = 3;
    static final int MAX_BITRATE = 20;

    final String id;
    final String name;
    final int height;
    final int fps;
    final int bitrateMbps;
    final PlayerFraming framing;
    final boolean metrics;

    StreamProfile(String id, String name, int height, int fps, int bitrateMbps,
                  PlayerFraming framing, boolean metrics) {
        this.id = id;
        this.name = name;
        this.height = height == 1080 ? 1080 : 720;
        this.fps = fps == 30 ? 30 : 60;
        this.bitrateMbps = Math.max(MIN_BITRATE, Math.min(MAX_BITRATE, bitrateMbps));
        this.framing = framing;
        this.metrics = metrics;
    }

    int width() {
        return height == 1080 ? 1920 : 1280;
    }

    String videoSummary() {
        return height + "p · " + fps + " FPS · até " + bitrateMbps + " Mb/s";
    }

    boolean custom() {
        return framing != null;
    }

    static ArrayList<StreamProfile> all(SharedPreferences prefs) {
        ArrayList<StreamProfile> profiles = new ArrayList<>();
        profiles.add(new StreamProfile("economy", "Economia", 720, 30, 5, null, false));
        profiles.add(DEFAULT);
        profiles.add(new StreamProfile("quality", "Qualidade", 1080, 60, 15, null, false));
        try {
            JSONArray saved = new JSONArray(prefs.getString(SAVED, "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject entry = saved.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id");
                String name = entry.optString("name").trim();
                if (name.isEmpty() || !id.startsWith("custom-") || find(profiles, id) != null) continue;
                profiles.add(new StreamProfile(id, name, entry.optInt("height"), entry.optInt("fps"),
                    entry.optInt("bitrate", 10), PlayerFraming.fromPreference(entry.optString("framing")),
                    entry.optBoolean("metrics")));
            }
        } catch (JSONException ignored) {
            // Built-in presets are available even if the stored list is corrupted.
        }
        return profiles;
    }

    static StreamProfile active(SharedPreferences prefs) {
        StreamProfile profile = find(all(prefs), prefs.getString(ACTIVE, DEFAULT.id));
        return profile == null ? DEFAULT : profile;
    }

    private static StreamProfile find(ArrayList<StreamProfile> profiles, String id) {
        for (StreamProfile profile : profiles) if (profile.id.equals(id)) return profile;
        return null;
    }

    void activate(SharedPreferences prefs) {
        prefs.edit().putString(ACTIVE, id).apply();
    }

    static String newId() {
        return "custom-" + UUID.randomUUID();
    }

    void saveAndActivate(SharedPreferences prefs) {
        ArrayList<StreamProfile> profiles = all(prefs);
        StreamProfile old = find(profiles, id);
        if (old == null) profiles.add(this);
        else profiles.set(profiles.indexOf(old), this);
        write(prefs, profiles, id);
    }

    void remove(SharedPreferences prefs) {
        ArrayList<StreamProfile> profiles = all(prefs);
        profiles.removeIf(profile -> profile.id.equals(id) && profile.custom());
        String active = prefs.getString(ACTIVE, DEFAULT.id);
        write(prefs, profiles, id.equals(active) ? DEFAULT.id : active);
    }

    private static void write(SharedPreferences prefs, ArrayList<StreamProfile> profiles, String active) {
        JSONArray saved = new JSONArray();
        for (StreamProfile profile : profiles) {
            if (!profile.custom()) continue;
            try {
                saved.put(new JSONObject().put("id", profile.id).put("name", profile.name)
                    .put("height", profile.height).put("fps", profile.fps).put("bitrate", profile.bitrateMbps)
                    .put("framing", profile.framing.name()).put("metrics", profile.metrics));
            } catch (JSONException error) {
                throw new IllegalStateException(error);
            }
        }
        prefs.edit().putString(SAVED, saved.toString()).putString(ACTIVE, active).apply();
    }
}
