package net.kollnig.missioncontrol.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persisted data only; contains no accounts, network addresses, names, or raw backend errors. */
final class BatteryExperimentRun {
    enum Stage { ARMED, SWITCHING, WARMUP, MEASURING, FINISHED, ABORTED }
    final String id;
    final boolean reverse;
    final int hours;
    final boolean originalTailscale;
    final List<Interval> intervals = new ArrayList<>();
    final List<JSONObject> events = new ArrayList<>();
    volatile Stage stage = Stage.ARMED;
    int index, token;
    long due;
    boolean lastWrittenTailscale, userChangedTailscale, restorePending;
    String reason = "", restoration = "not_needed";
    JSONObject settings = new JSONObject();

    BatteryExperimentRun(boolean reverse, int hours, boolean original) {
        this(UUID.randomUUID().toString(), reverse, hours, original);
    }

    private BatteryExperimentRun(String id, boolean reverse, int hours, boolean original) {
        this.id = id;
        this.reverse = reverse;
        this.hours = hours;
        this.originalTailscale = original;
        this.lastWrittenTailscale = original;
        intervals.add(new Interval(reverse));
        intervals.add(new Interval(!reverse));
    }

    boolean active() { return stage != Stage.FINISHED && stage != Stage.ABORTED; }
    boolean expectedTailscale() { return intervals.get(index).tailscale; }

    void event(String type, long elapsed, long wall, long planned) {
        try {
            events.add(new JSONObject().put("type", type).put("elapsed_ms", elapsed)
                    .put("wall_ms", wall).put("planned_elapsed_ms", planned));
        } catch (JSONException ex) { throw new IllegalStateException(ex); }
    }

    JSONObject json() {
        try {
            JSONArray samples = new JSONArray();
            for (Interval interval : intervals) samples.put(interval.json());
            return new JSONObject().put("version", 1).put("id", id).put("reverse", reverse)
                    .put("comparison_valid", stage == Stage.FINISHED)
                    .put("hours", hours).put("original_tailscale", originalTailscale)
                    .put("stage", stage.name()).put("index", index).put("token", token).put("due", due)
                    .put("last_written_tailscale", lastWrittenTailscale)
                    .put("user_changed_tailscale", userChangedTailscale).put("restore_pending", restorePending)
                    .put("restoration", restoration).put("reason", reason).put("settings", settings)
                    .put("intervals", samples).put("events", new JSONArray(events));
        } catch (JSONException ex) { throw new IllegalStateException(ex); }
    }

    static BatteryExperimentRun fromJson(JSONObject json) throws JSONException {
        if (json.getInt("version") != 1) throw new JSONException("Unsupported experiment version");
        BatteryExperimentRun run = new BatteryExperimentRun(json.getString("id"), json.getBoolean("reverse"),
                json.getInt("hours"), json.getBoolean("original_tailscale"));
        run.stage = Stage.valueOf(json.getString("stage"));
        run.index = json.getInt("index");
        if (run.index < 0 || run.index > 1 || run.hours < 1 || run.hours > 4)
            throw new JSONException("Invalid experiment state");
        run.token = json.getInt("token"); run.due = json.getLong("due");
        run.lastWrittenTailscale = json.getBoolean("last_written_tailscale");
        run.userChangedTailscale = json.getBoolean("user_changed_tailscale");
        run.restorePending = json.getBoolean("restore_pending");
        run.restoration = json.getString("restoration"); run.reason = json.getString("reason");
        run.settings = json.getJSONObject("settings");
        JSONArray intervals = json.getJSONArray("intervals");
        for (int i = 0; i < 2; i++) {
            JSONObject item = intervals.getJSONObject(i);
            run.intervals.get(i).start = Sample.read(item.optJSONObject("start"));
            run.intervals.get(i).end = Sample.read(item.optJSONObject("end"));
        }
        JSONArray events = json.getJSONArray("events");
        for (int i = 0; i < events.length(); i++) run.events.add(events.getJSONObject(i));
        return run;
    }

    static final class Sample {
        long wall, elapsed;
        Double level, temperature;
        Long charge;
        boolean charging, protection, tailscale, bridge;
        String state = "";

        JSONObject json() throws JSONException {
            return new JSONObject().put("wall_ms", wall).put("elapsed_ms", elapsed)
                    .put("level_percent", level == null ? JSONObject.NULL : level)
                    .put("charge_uah", charge == null ? JSONObject.NULL : charge)
                    .put("temperature_c", temperature == null ? JSONObject.NULL : temperature)
                    .put("charging", charging).put("protection", protection).put("tailscale", tailscale)
                    .put("bridge", bridge).put("state", state);
        }

        static Sample read(JSONObject json) throws JSONException {
            if (json == null) return null;
            Sample s = new Sample();
            s.wall = json.getLong("wall_ms"); s.elapsed = json.getLong("elapsed_ms");
            s.level = json.isNull("level_percent") ? null : json.getDouble("level_percent");
            s.charge = json.isNull("charge_uah") ? null : json.getLong("charge_uah");
            s.temperature = json.isNull("temperature_c") ? null : json.getDouble("temperature_c");
            s.charging = json.getBoolean("charging"); s.protection = json.getBoolean("protection");
            s.tailscale = json.getBoolean("tailscale"); s.bridge = json.getBoolean("bridge");
            s.state = json.getString("state");
            return s;
        }
    }

    static final class Interval {
        final boolean tailscale;
        Sample start, end;
        Interval(boolean tailscale) { this.tailscale = tailscale; }
        double elapsedHours() { return start == null || end == null ? 0 : (end.elapsed - start.elapsed) / 3600000d; }
        Double rate(boolean charge) {
            double hours = elapsedHours();
            if (hours <= 0 || start.charging || end.charging) return null;
            if (charge) {
                if (start.charge == null || end.charge == null || end.charge > start.charge) return null;
                return (start.charge - end.charge) / 1000d / hours;
            }
            if (start.level == null || end.level == null || end.level > start.level) return null;
            return (start.level - end.level) / hours;
        }
        JSONObject json() throws JSONException {
            Double chargeRate = rate(true), levelRate = rate(false);
            return new JSONObject().put("condition", tailscale ? "B" : "A")
                    .put("start", start == null ? JSONObject.NULL : start.json())
                    .put("end", end == null ? JSONObject.NULL : end.json())
                    .put("hours", elapsedHours()).put("mah_per_hour", chargeRate == null ? JSONObject.NULL : chargeRate)
                    .put("percentage_points_per_hour", levelRate == null ? JSONObject.NULL : levelRate);
        }
    }
}
