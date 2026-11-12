package net.kollnig.missioncontrol.diagnostics;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import net.kollnig.missioncontrol.R;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public final class BatteryExperimentActivity extends AppCompatActivity {
    private BatteryExperimentController controller;
    private Spinner order, hours, history;
    private TextView status, result;
    private Button start, stop;
    private List<BatteryExperimentRun> runs;
    private String selectedId, exportText;
    private final Runnable refresh = this::render;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.battery_experiment_title);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        controller = BatteryExperimentController.get(this);
        if (state != null) { selectedId = state.getString("selected_id"); exportText = state.getString("export_text"); }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll);
        label(content, getString(R.string.battery_experiment_instructions));
        label(content, getString(R.string.battery_experiment_order));
        order = spinner(content, new String[]{"A → B", "B → A"});
        label(content, getString(R.string.battery_experiment_duration));
        hours = spinner(content, new String[]{"1 h", "2 h", "3 h", "4 h"}); hours.setSelection(2);
        if (state != null) { order.setSelection(state.getInt("order", 0)); hours.setSelection(state.getInt("hours", 2)); }
        start = button(content, R.string.battery_experiment_arm, () -> {
            if (!controller.canSchedule()) {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException ex) { message(getString(R.string.battery_experiment_alarm_permission)); }
                return;
            }
            try { controller.arm(order.getSelectedItemPosition() == 1, hours.getSelectedItemPosition() + 1); }
            catch (RuntimeException ex) {
                controller.stop();
                message(getString(R.string.battery_experiment_not_ready));
            }
            render();
        });
        stop = button(content, R.string.battery_experiment_stop, controller::stop);
        status = label(content, "");
        label(content, getString(R.string.battery_experiment_history));
        history = spinner(content, new String[0]);
        history.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (runs != null && position < runs.size()) { selectedId = runs.get(position).id; renderResult(runs.get(position)); }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        result = label(content, "");
        button(content, R.string.battery_experiment_export_json, () -> export(false));
        button(content, R.string.battery_experiment_export_csv, () -> export(true));
        label(content, getString(R.string.battery_experiment_caveat));
    }

    @Override protected void onResume() { super.onResume(); controller.addListener(refresh); render(); }
    @Override protected void onPause() { controller.removeListener(refresh); super.onPause(); }
    @Override public boolean onSupportNavigateUp() { finish(); return true; }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("selected_id", selectedId); state.putString("export_text", exportText);
        state.putInt("order", order.getSelectedItemPosition()); state.putInt("hours", hours.getSelectedItemPosition());
    }

    private void render() {
        boolean active = controller.active();
        start.setEnabled(!active); stop.setEnabled(active); order.setEnabled(!active); hours.setEnabled(!active);
        BatteryExperimentRun run = controller.engine.run;
        String text = run == null ? getString(R.string.battery_experiment_idle) : stage(run.stage);
        if (active) {
            long remaining = Math.max(0, run.due - SystemClock.elapsedRealtime());
            if (run.stage == BatteryExperimentRun.Stage.ARMED)
                text += "\n" + getString(R.string.battery_experiment_waiting);
            else {
                long future = remaining;
                if (run.stage == BatteryExperimentRun.Stage.SWITCHING) future += 15 * BatteryExperimentEngine.MINUTE + run.hours * BatteryExperimentEngine.HOUR;
                if (run.stage == BatteryExperimentRun.Stage.WARMUP) future += run.hours * BatteryExperimentEngine.HOUR;
                if (run.index == 0) future += (15 * BatteryExperimentEngine.MINUTE + run.hours * BatteryExperimentEngine.HOUR);
                text += "\n" + getString(R.string.battery_experiment_estimate, date(System.currentTimeMillis() + future));
            }
        }
        status.setText(text);
        runs = controller.history();
        String[] entries = new String[runs.size()]; int selection = 0;
        for (int i = 0; i < runs.size(); i++) {
            BatteryExperimentRun item = runs.get(i);
            long wall = item.events.isEmpty() ? 0 : item.events.get(0).optLong("wall_ms");
            entries[i] = date(wall) + " · " + stage(item.stage);
            if (item.id.equals(selectedId)) selection = i;
        }
        history.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, entries));
        history.setSelection(selection);
        if (runs.isEmpty()) result.setText(R.string.battery_experiment_no_results);
        else { selectedId = runs.get(selection).id; renderResult(runs.get(selection)); }
    }

    private void renderResult(BatteryExperimentRun run) {
        StringBuilder text = new StringBuilder(stage(run.stage));
        if (!run.reason.isEmpty()) text.append("\n").append(reason(run.reason));
        for (BatteryExperimentRun.Interval interval : run.intervals) {
            text.append("\n\n").append(interval.tailscale ? "B (Tailscale ON)" : "A (Tailscale OFF)");
            text.append("\n").append(getString(R.string.battery_experiment_rates,
                    BatteryExperimentReport.number(interval.elapsedHours()), BatteryExperimentReport.number(interval.rate(true)),
                    BatteryExperimentReport.number(interval.rate(false))));
            if (interval.start != null && interval.end != null) {
                text.append("\n").append(getString(R.string.battery_experiment_temperature,
                        BatteryExperimentReport.number(interval.start.temperature), BatteryExperimentReport.number(interval.end.temperature)));
            }
        }
        if (run.stage == BatteryExperimentRun.Stage.FINISHED)
            text.append("\n\n").append(getString(R.string.battery_experiment_difference,
                    BatteryExperimentReport.number(BatteryExperimentReport.difference(run, true)),
                    BatteryExperimentReport.number(BatteryExperimentReport.difference(run, false))));
        text.append("\n\n").append(getString(R.string.battery_experiment_restoration)).append(' ').append(reason(run.restoration));
        result.setText(text);
    }

    private void export(boolean csv) {
        if (runs == null || runs.isEmpty()) return;
        int position = history.getSelectedItemPosition();
        if (position < 0 || position >= runs.size()) return;
        BatteryExperimentRun run = runs.get(position);
        exportText = csv ? BatteryExperimentReport.csv(run) : run.json().toString();
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(csv ? "text/csv" : "application/json")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,
                        "battery-ab-" + run.id + (csv ? ".csv" : ".json")), 1);
    }
    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (request != 1 || resultCode != Activity.RESULT_OK || data == null || data.getData() == null || exportText == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (out == null) throw new java.io.IOException("No output stream");
            out.write(exportText.getBytes(StandardCharsets.UTF_8));
            message(getString(R.string.battery_experiment_saved));
        } catch (Exception ex) { message(getString(R.string.battery_experiment_export_failed)); }
        exportText = null;
    }

    private String stage(BatteryExperimentRun.Stage stage) { return reason("stage_" + stage.name().toLowerCase(java.util.Locale.ROOT)); }
    private String reason(String code) {
        int resource = reasonResource(code);
        return resource == 0 ? code : getString(resource);
    }
    private static int reasonResource(String code) {
        switch (code) {
            case "stage_armed": return R.string.battery_experiment_stage_armed;
            case "stage_switching": return R.string.battery_experiment_stage_switching;
            case "stage_warmup": return R.string.battery_experiment_stage_warmup;
            case "stage_measuring": return R.string.battery_experiment_stage_measuring;
            case "stage_finished": return R.string.battery_experiment_stage_finished;
            case "stage_aborted": return R.string.battery_experiment_stage_aborted;
            case "reservation_expired": return R.string.battery_experiment_reservation_expired;
            case "switch_failed": return R.string.battery_experiment_switch_failed;
            case "switch_timeout": return R.string.battery_experiment_switch_timeout;
            case "connection_failed": return R.string.battery_experiment_connection_failed;
            case "connection_lost": return R.string.battery_experiment_connection_lost;
            case "environment_changed": return R.string.battery_experiment_environment_changed;
            case "screen_on": return R.string.battery_experiment_screen_on;
            case "charging": return R.string.battery_experiment_charging;
            case "settings_changed": return R.string.battery_experiment_settings_changed;
            case "protection_interrupted": return R.string.battery_experiment_protection_interrupted;
            case "network_changed": return R.string.battery_experiment_network_changed;
            case "process_restarted": return R.string.battery_experiment_process_restarted;
            case "cancelled": return R.string.battery_experiment_cancelled;
            case "internal_error": return R.string.battery_experiment_internal_error;
            case "not_needed": return R.string.battery_experiment_not_needed;
            case "skipped_user_change": return R.string.battery_experiment_skipped_user_change;
            case "requested": return R.string.battery_experiment_requested;
            case "pending": return R.string.battery_experiment_pending;
            case "alarm_permission": return R.string.battery_experiment_alarm_permission;
            default: return 0;
        }
    }
    private static String date(long wall) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(wall)); }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private TextView label(LinearLayout content, String text) {
        TextView view = new TextView(this); view.setText(text); view.setTextIsSelectable(true);
        content.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }
    private Spinner spinner(LinearLayout content, String[] entries) {
        Spinner view = new Spinner(this);
        view.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, entries));
        content.addView(view); return view;
    }
    private Button button(LinearLayout content, int title, Runnable action) {
        Button view = new Button(this); view.setText(title); view.setOnClickListener(v -> action.run()); content.addView(view); return view;
    }
}
