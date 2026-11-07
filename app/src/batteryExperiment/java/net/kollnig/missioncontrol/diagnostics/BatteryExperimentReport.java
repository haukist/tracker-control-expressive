package net.kollnig.missioncontrol.diagnostics;

import java.util.Locale;

final class BatteryExperimentReport {
    private BatteryExperimentReport() { }

    static String csv(BatteryExperimentRun run) {
        StringBuilder text = new StringBuilder("run_id,status,reason,restoration,condition,requested_hours,elapsed_hours,start_wall_ms,end_wall_ms,start_elapsed_ms,end_elapsed_ms,start_level_percent,end_level_percent,start_charge_uah,end_charge_uah,start_temperature_c,end_temperature_c,mah_per_hour,percentage_points_per_hour,comparison_valid\n");
        for (BatteryExperimentRun.Interval interval : run.intervals) {
            BatteryExperimentRun.Sample a = interval.start, b = interval.end;
            Object[] fields = {run.id, run.stage.name(), run.reason, run.restoration, interval.tailscale ? "B" : "A",
                    run.hours, interval.elapsedHours(), a == null ? null : a.wall, b == null ? null : b.wall,
                    a == null ? null : a.elapsed, b == null ? null : b.elapsed,
                    a == null ? null : a.level, b == null ? null : b.level,
                    a == null ? null : a.charge, b == null ? null : b.charge,
                    a == null ? null : a.temperature, b == null ? null : b.temperature,
                    interval.rate(true), interval.rate(false), run.stage == BatteryExperimentRun.Stage.FINISHED};
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) text.append(',');
                String value = fields[i] == null ? "" : fields[i].toString();
                text.append('"').append(value.replace("\"", "\"\"")).append('"');
            }
            text.append('\n');
        }
        return text.toString();
    }

    static String number(Double value) { return value == null ? "—" : String.format(Locale.getDefault(), "%.2f", value); }
    static Double difference(BatteryExperimentRun run, boolean charge) {
        if (run.stage != BatteryExperimentRun.Stage.FINISHED) return null;
        BatteryExperimentRun.Interval a = run.intervals.get(run.reverse ? 1 : 0);
        BatteryExperimentRun.Interval b = run.intervals.get(run.reverse ? 0 : 1);
        Double av = a.rate(charge), bv = b.rate(charge);
        return av == null || bv == null ? null : bv - av;
    }
}
