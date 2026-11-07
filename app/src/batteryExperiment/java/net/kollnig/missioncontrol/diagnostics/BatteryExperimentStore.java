package net.kollnig.missioncontrol.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

final class BatteryExperimentStore {
    private final SharedPreferences prefs;
    BatteryExperimentStore(Context context) { prefs = context.getSharedPreferences("battery_experiments", Context.MODE_PRIVATE); }

    List<BatteryExperimentRun> history() {
        List<BatteryExperimentRun> runs = new ArrayList<>();
        try {
            JSONArray json = new JSONArray(prefs.getString("runs", "[]"));
            for (int i = 0; i < json.length(); i++) runs.add(BatteryExperimentRun.fromJson(json.getJSONObject(i)));
        } catch (JSONException | IllegalArgumentException ex) {
            throw new IllegalStateException("Unreadable battery experiment history", ex);
        }
        return runs;
    }

    void save(BatteryExperimentRun run) {
        List<BatteryExperimentRun> runs = history();
        runs.removeIf(item -> item.id.equals(run.id));
        runs.add(0, run);
        JSONArray json = new JSONArray();
        for (int i = 0; i < Math.min(10, runs.size()); i++) json.put(runs.get(i).json());
        if (!prefs.edit().putString("runs", json.toString()).commit())
            throw new IllegalStateException("Could not save battery experiment");
    }
}
