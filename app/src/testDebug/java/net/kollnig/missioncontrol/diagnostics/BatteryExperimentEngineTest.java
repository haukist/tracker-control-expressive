package net.kollnig.missioncontrol.diagnostics;

import android.app.Application;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, application = Application.class)
public class BatteryExperimentEngineTest {
    private static class Fake implements BatteryExperimentEngine.Host {
        long now = 1_000;
        boolean quiet, ready = true, protection = true, autoSwitch = true, failed, wake, sampleCharging;
        int switches, saves, restorations;
        BatteryExperimentRun scheduled;
        BatteryExperimentEngine engine = new BatteryExperimentEngine(this);
        @Override public long elapsed() { return now; }
        @Override public long wall() { return now + 10_000_000; }
        @Override public boolean quiet() { return quiet; }
        @Override public boolean ready(boolean desired) { return protection && ready == desired; }
        @Override public boolean failed() { return failed; }
        @Override public BatteryExperimentRun.Sample sample() {
            BatteryExperimentRun.Sample s = new BatteryExperimentRun.Sample();
            s.elapsed = now; s.wall = wall(); s.level = 90 - now / 3600000d;
            s.charge = 4_000_000L - now / 36; s.protection = true; s.tailscale = ready; s.bridge = ready;
            s.charging = sampleCharging;
            return s;
        }
        @Override public void save(BatteryExperimentRun run) { saves++; }
        @Override public void schedule(BatteryExperimentRun run) { scheduled = run; }
        @Override public void cancelAlarm() { scheduled = null; }
        @Override public void transitionWake(boolean acquire) { wake = acquire; }
        @Override public boolean setTailscale(boolean enabled) { switches++; if (autoSwitch) ready = enabled; return true; }
        @Override public void restore(BatteryExperimentRun run) {
            restorations++;
            if (!run.userChangedTailscale && protection) ready = run.originalTailscale;
            run.restorePending = false;
        }
        void start(boolean reverse) { engine.arm(reverse, 3); quiet = true; engine.environmentReady(); }
        void fire() { now = engine.run.due; engine.alarm(engine.run.id, engine.run.token); }
        void complete() { fire(); fire(); fire(); fire(); }
    }

    @Test public void bothOrdersMeasureOnlyAfterSettlingAndRestore() {
        for (boolean reverse : new boolean[]{false, true}) {
            Fake host = new Fake(); host.start(reverse);
            BatteryExperimentRun run = host.engine.run;
            assertEquals(BatteryExperimentRun.Stage.WARMUP, run.stage);
            assertEquals(reverse, run.expectedTailscale()); assertFalse(host.wake);
            assertNull(run.intervals.get(0).start);
            host.fire();
            assertEquals(15 * BatteryExperimentEngine.MINUTE + 1_000, run.intervals.get(0).start.elapsed);
            assertEquals(BatteryExperimentRun.Stage.MEASURING, run.stage);
            host.fire(); host.fire(); host.fire();
            assertEquals(BatteryExperimentRun.Stage.FINISHED, run.stage);
            assertEquals(3, run.intervals.get(0).elapsedHours(), 0);
            assertEquals(3, run.intervals.get(1).elapsedHours(), 0);
            assertTrue(host.ready); assertEquals(1, host.restorations); assertNull(host.scheduled);
            assertNotNull(BatteryExperimentReport.difference(run, true));
        }
    }

    @Test public void staleDuplicateAndEarlyAlarmsDoNotAdvance() {
        Fake host = new Fake(); host.start(false);
        BatteryExperimentRun run = host.engine.run; int token = run.token;
        host.engine.alarm("other", token); host.engine.alarm(run.id, token - 1);
        host.engine.alarm(run.id, token);
        assertEquals(BatteryExperimentRun.Stage.WARMUP, run.stage);
        host.fire();
        host.engine.alarm(run.id, token);
        assertEquals(BatteryExperimentRun.Stage.MEASURING, run.stage);
    }

    @Test public void recordsRealElapsedDurationWhenAlarmIsLate() {
        Fake host = new Fake(); host.start(false); host.fire();
        host.now = host.engine.run.due + 10 * BatteryExperimentEngine.MINUTE;
        host.engine.alarm(host.engine.run.id, host.engine.run.token);
        assertEquals(3 + 1d / 6, host.engine.run.intervals.get(0).elapsedHours(), 0.0001);
    }

    @Test public void reservationExpiresWithoutChangingTailscale() {
        Fake host = new Fake(); host.engine.arm(false, 3); host.fire();
        assertEquals("reservation_expired", host.engine.run.reason);
        assertEquals(0, host.switches); assertEquals(0, host.restorations);
    }

    @Test public void switchingTimesOutAndReleasesWakeLock() {
        Fake host = new Fake(); host.autoSwitch = false; host.start(false);
        assertTrue(host.wake); assertEquals(BatteryExperimentRun.Stage.SWITCHING, host.engine.run.stage);
        host.fire(); assertEquals("switch_timeout", host.engine.run.reason);
        assertFalse(host.wake); assertEquals(1, host.restorations);
    }

    @Test public void lateReadyNotificationCannotBeatTheTransitionWatchdog() {
        Fake host = new Fake(); host.autoSwitch = false; host.start(false);
        host.now = host.engine.run.due + 1; host.ready = false;
        host.engine.statusChanged();
        assertEquals("switch_timeout", host.engine.run.reason);
        assertFalse(host.wake);
        assertNull(host.engine.run.intervals.get(0).start);
    }

    @Test public void authenticationFailureAbortsBeforeMeasurement() {
        Fake host = new Fake(); host.start(false); host.fire();
        host.autoSwitch = false; host.failed = true; host.fire();
        assertEquals("connection_failed", host.engine.run.reason);
        assertNull(host.engine.run.intervals.get(1).start);
    }

    @Test public void interruptionsNeverProduceAComparison() {
        for (String reason : new String[]{"screen_on", "charging", "settings_changed", "network_changed", "protection_interrupted"}) {
            Fake host = new Fake(); host.start(false); host.fire();
            host.engine.abort(reason, false);
            assertEquals(BatteryExperimentRun.Stage.ABORTED, host.engine.run.stage);
            assertEquals(reason, host.engine.run.reason);
            assertNull(BatteryExperimentReport.difference(host.engine.run, true));
            assertTrue(BatteryExperimentReport.csv(host.engine.run).contains("\"false\""));
        }
    }

    @Test public void boundaryChecksRejectChangedEnvironmentEvenWithoutBroadcast() {
        Fake host = new Fake(); host.start(false); host.quiet = false; host.fire();
        assertEquals("environment_changed", host.engine.run.reason);
        assertNull(host.engine.run.intervals.get(0).start);
    }

    @Test public void nativeReloadRestartsSettlingButInvalidatesActiveMeasurement() {
        Fake host = new Fake(); host.start(false);
        long originalDue = host.engine.run.due;
        host.now += BatteryExperimentEngine.MINUTE;
        host.engine.protectionInterrupted();
        assertEquals(BatteryExperimentRun.Stage.WARMUP, host.engine.run.stage);
        assertEquals(originalDue + BatteryExperimentEngine.MINUTE, host.engine.run.due);
        host.fire(); host.engine.protectionInterrupted();
        assertEquals(BatteryExperimentRun.Stage.ABORTED, host.engine.run.stage);
    }

    @Test public void sampleRechecksChargingAtBoundary() {
        Fake host = new Fake(); host.start(false); host.sampleCharging = true; host.fire();
        assertEquals("environment_changed", host.engine.run.reason);
        assertNull(host.engine.run.intervals.get(0).start);
    }

    @Test public void userChangeIsNotRestoredAndProtectionIsNotRestarted() {
        Fake host = new Fake(); host.start(false);
        host.engine.abort("settings_changed", true);
        assertFalse(host.ready);
        host = new Fake(); host.start(false); host.protection = false;
        host.engine.abort("protection_interrupted", false);
        assertFalse(host.protection); assertFalse(host.ready);
    }

    @Test public void processRecoveryAbortsRatherThanResumesAndRestores() throws Exception {
        Fake host = new Fake(); host.start(false); host.fire();
        BatteryExperimentRun persisted = BatteryExperimentRun.fromJson(new JSONObject(host.engine.run.json().toString()));
        Fake restarted = new Fake(); restarted.ready = false;
        restarted.engine.recover(persisted);
        assertEquals("process_restarted", persisted.reason); assertTrue(restarted.ready);
        assertNull(restarted.scheduled); assertEquals(0, restarted.switches);
    }

    @Test public void unfinishedRestorationIsRetriedAfterRestart() {
        Fake host = new Fake();
        BatteryExperimentRun run = new BatteryExperimentRun(false, 3, true);
        run.stage = BatteryExperimentRun.Stage.ABORTED; run.restorePending = true;
        host.engine.recover(run); assertEquals(1, host.restorations); assertFalse(run.restorePending);
    }

    @Test public void ratesUseMicroampHoursAndRejectMissingOrReversedCounters() {
        BatteryExperimentRun.Interval interval = new BatteryExperimentRun.Interval(false);
        interval.start = new BatteryExperimentRun.Sample(); interval.end = new BatteryExperimentRun.Sample();
        interval.start.elapsed = 0; interval.end.elapsed = BatteryExperimentEngine.HOUR * 2;
        interval.start.charge = 4_000_000L; interval.end.charge = 3_800_000L;
        interval.start.level = 80d; interval.end.level = 78d;
        assertEquals(100, interval.rate(true), 0); assertEquals(1, interval.rate(false), 0);
        interval.end.charge = null; assertNull(interval.rate(true));
        interval.end.charge = 4_100_000L; assertNull(interval.rate(true));
        interval.end.level = 81d; assertNull(interval.rate(false));
        interval.end.elapsed = 0; assertNull(interval.rate(false));
    }

    @Test public void jsonRoundTripAndCsvKeepRawSampleValues() throws Exception {
        Fake host = new Fake(); host.start(true); host.complete();
        BatteryExperimentRun run = BatteryExperimentRun.fromJson(new JSONObject(host.engine.run.json().toString()));
        assertEquals(BatteryExperimentRun.Stage.FINISHED, run.stage);
        assertEquals(host.engine.run.intervals.get(0).start.charge, run.intervals.get(0).start.charge);
        assertEquals(host.engine.run.events.size(), run.events.size());
        assertTrue(BatteryExperimentReport.csv(run).contains("start_charge_uah"));
        assertTrue(BatteryExperimentReport.csv(run).contains("\"true\""));
    }
}
