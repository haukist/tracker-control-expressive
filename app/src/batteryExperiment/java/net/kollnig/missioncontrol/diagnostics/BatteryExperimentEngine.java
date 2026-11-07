package net.kollnig.missioncontrol.diagnostics;

/** Main-thread state machine; Android work is supplied by Host and faked in tests. */
final class BatteryExperimentEngine {
    static final long MINUTE = 60_000L, HOUR = 60 * MINUTE;
    // Let the watchdog run before the 60-second wake lock expires.
    private static final long SWITCH_TIMEOUT = MINUTE - 1_000L;
    interface Host {
        long elapsed();
        long wall();
        boolean ready(boolean tailscale);
        boolean quiet();
        boolean failed();
        BatteryExperimentRun.Sample sample();
        void save(BatteryExperimentRun run);
        void schedule(BatteryExperimentRun run);
        void cancelAlarm();
        void transitionWake(boolean acquire);
        boolean setTailscale(boolean enabled);
        void restore(BatteryExperimentRun run);
    }
    private final Host host;
    volatile BatteryExperimentRun run;

    BatteryExperimentEngine(Host host) { this.host = host; }

    void arm(boolean reverse, int hours) {
        if (run != null && run.active()) throw new IllegalStateException("already_running");
        if (hours < 1 || hours > 4 || !host.ready(true)) throw new IllegalStateException("not_ready");
        run = new BatteryExperimentRun(reverse, hours, true);
        event("armed", 0);
        deadline(30 * MINUTE);
    }

    void environmentReady() {
        if (run != null && run.stage == BatteryExperimentRun.Stage.ARMED && host.quiet()) switchCondition();
    }

    private void switchCondition() {
        run.stage = BatteryExperimentRun.Stage.SWITCHING;
        run.lastWrittenTailscale = run.expectedTailscale();
        run.restorePending = true;
        host.transitionWake(true);
        event("switching", 0);
        deadline(SWITCH_TIMEOUT);
        if (!host.setTailscale(run.expectedTailscale())) { abort("switch_failed", false); return; }
        statusChanged();
    }

    void statusChanged() {
        if (run == null || !run.active()) return;
        if (run.stage == BatteryExperimentRun.Stage.ARMED) {
            if (!host.ready(true)) abort("connection_lost", false);
        } else if (run.stage == BatteryExperimentRun.Stage.SWITCHING) {
            if (host.elapsed() >= run.due) { abort("switch_timeout", false); return; }
            if (run.expectedTailscale() && host.failed()) { abort("connection_failed", false); return; }
            if (host.ready(run.expectedTailscale())) {
                if (!host.quiet()) { abort("environment_changed", false); return; }
                run.stage = BatteryExperimentRun.Stage.WARMUP;
                event("warmup", run.due);
                host.transitionWake(false);
                deadline(15 * MINUTE);
            }
        } else if (!host.ready(run.expectedTailscale())) {
            if (run.stage == BatteryExperimentRun.Stage.WARMUP && !host.failed()) protectionInterrupted();
            else abort("connection_lost", false);
        }
    }

    void protectionInterrupted() {
        if (run == null || !run.active()) return;
        if (run.stage == BatteryExperimentRun.Stage.WARMUP) {
            // Native reconfiguration while settling restarts the full settling period.
            run.stage = BatteryExperimentRun.Stage.SWITCHING;
            host.transitionWake(true);
            event("settling_restarted", 0);
            deadline(SWITCH_TIMEOUT);
            statusChanged();
        } else if (run.stage != BatteryExperimentRun.Stage.SWITCHING) abort("protection_interrupted", false);
    }

    void alarm(String id, int token) {
        if (run == null || !run.active() || !run.id.equals(id) || run.token != token) return;
        if (host.elapsed() < run.due) { host.schedule(run); return; }
        event("alarm", run.due);
        switch (run.stage) {
            case ARMED: abort("reservation_expired", false); break;
            case SWITCHING: abort("switch_timeout", false); break;
            case WARMUP:
                if (!host.quiet() || !host.ready(run.expectedTailscale())) {
                    abort("environment_changed", false); break;
                }
                BatteryExperimentRun.Sample start = host.sample();
                if (!valid(start)) { abort("environment_changed", false); break; }
                run.intervals.get(run.index).start = start;
                run.stage = BatteryExperimentRun.Stage.MEASURING;
                event("measurement_started", run.due);
                deadline(run.hours * HOUR);
                break;
            case MEASURING:
                if (!host.quiet() || !host.ready(run.expectedTailscale())) {
                    abort("environment_changed", false); break;
                }
                BatteryExperimentRun.Sample end = host.sample();
                if (!valid(end)) { abort("environment_changed", false); break; }
                run.intervals.get(run.index).end = end;
                event("measurement_finished", run.due);
                if (run.index == 0) { run.index = 1; switchCondition(); }
                else finish(BatteryExperimentRun.Stage.FINISHED, "");
                break;
            default: break;
        }
    }

    void abort(String reason, boolean userChangedTailscale) {
        if (run == null || !run.active()) return;
        run.userChangedTailscale |= userChangedTailscale;
        finish(BatteryExperimentRun.Stage.ABORTED, reason);
    }

    void recover(BatteryExperimentRun saved) {
        run = saved;
        if (run.active()) abort("process_restarted", false);
        else if (run.restorePending) { host.restore(run); host.save(run); }
    }

    private void finish(BatteryExperimentRun.Stage stage, String reason) {
        run.stage = stage; run.reason = reason;
        event(stage == BatteryExperimentRun.Stage.FINISHED ? "finished" : reason, 0);
        host.cancelAlarm(); host.transitionWake(false);
        host.save(run); // Persist the recovery obligation before attempting side effects.
        if (run.restorePending) host.restore(run);
        host.save(run);
    }

    private void deadline(long delay) {
        run.due = host.elapsed() + delay;
        run.token++;
        host.save(run);
        host.schedule(run);
    }

    private void event(String type, long planned) { run.event(type, host.elapsed(), host.wall(), planned); }

    private boolean valid(BatteryExperimentRun.Sample sample) {
        return sample.protection && !sample.charging && sample.tailscale == run.expectedTailscale()
                && sample.bridge == run.expectedTailscale();
    }
}
