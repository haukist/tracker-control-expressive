package net.kollnig.missioncontrol;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.data.InsightsData;
import net.kollnig.missioncontrol.data.InsightsDataProvider;
import net.kollnig.missioncontrol.data.TimelineEntry;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerContact;
import net.kollnig.missioncontrol.data.TrackerList;
import net.kollnig.missioncontrol.ui.compose.TimelineEmptyState;
import net.kollnig.missioncontrol.ui.compose.TimelineInsightsPeriods;
import net.kollnig.missioncontrol.ui.compose.TimelineScreen;
import net.kollnig.missioncontrol.ui.compose.TimelineScreenCallbacks;
import net.kollnig.missioncontrol.ui.compose.TimelineScreenController;
import net.kollnig.missioncontrol.ui.compose.TimelineScreenModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.faircode.netguard.DatabaseHelper;
import eu.faircode.netguard.Util;

public class TimelineFragment extends Fragment {

    private static final String TAG = "TimelineFragment";
    private static final long REFRESH_DEBOUNCE_MS = 500L;
    // Catch stale relative timestamps and any missed AccessChangedListener
    // callbacks by re-querying on a slow tick while the screen is open.
    private static final long PERIODIC_REFRESH_MS = 30_000L;

    @Nullable
    private ComposeView composeView;
    @Nullable
    private TimelineScreenController screenController;
    private int viewGeneration;
    // Monotonically increasing per loader, so a request started earlier (but
    // finishing later, e.g. after a subsequent pull-to-refresh) never
    // overwrites a result from a request that was started after it.
    private int timelineRequestId;
    private int insightsRequestId;
    private static final String PREF_INSIGHTS_DAYS = "timeline_insights_days";
    // Trailing window of the summary at the top; 1 means the last 24 hours.
    private int insightsDays = TimelineInsightsPeriods.DEFAULT_DAYS;

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = this::refreshAll;
    private final Runnable periodicRunnable = new Runnable() {
        @Override
        public void run() {
            refreshAll();
            refreshHandler.postDelayed(this, PERIODIC_REFRESH_MS);
        }
    };
    private final DatabaseHelper.AccessChangedListener accessListener =
            () -> {
                refreshHandler.removeCallbacks(refreshRunnable);
                refreshHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS);
            };

    @Nullable
    private ExecutorService lifecycleExecutor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        ComposeView view = new ComposeView(requireContext());
        // A stable ID gives Compose's saveable state registry a key to persist
        // under, so the LazyColumn scroll position survives recreation.
        view.setId(R.id.compose_timeline);
        view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        composeView = view;
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final int generation = ++viewGeneration;
        composeView = (ComposeView) view;
        insightsDays = TimelineInsightsPeriods.sanitize(
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                        .getInt(PREF_INSIGHTS_DAYS, TimelineInsightsPeriods.DEFAULT_DAYS));
        screenController = TimelineScreen.install(
                composeView,
                TimelineScreenModel.initial(insightsDays),
                new TimelineScreenCallbacks() {
                    @Override
                    public void onEntryClick(int uid, String appName, String packageName) {
                        openEntry(uid, appName, packageName);
                    }

                    @Override
                    public void onOpenApp() {
                        Intent home = new Intent(Intent.ACTION_MAIN);
                        home.addCategory(Intent.CATEGORY_HOME);
                        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(home);
                    }

                    @Override
                    public void onOpenSettings() {
                        startActivity(new Intent(requireContext(),
                                eu.faircode.netguard.ActivitySettings.class));
                    }

                    @Override
                    public void onOpenInsights() {
                        startActivity(new Intent(requireContext(), InsightsActivity.class));
                    }

                    @Override
                    public void onInsightsDaysSelected(int days) {
                        days = TimelineInsightsPeriods.sanitize(days);
                        if (!isCurrentView(generation) || days == insightsDays)
                            return;
                        insightsDays = days;
                        PreferenceManager.getDefaultSharedPreferences(requireContext())
                                .edit().putInt(PREF_INSIGHTS_DAYS, days).apply();
                        screenController.setInsightsDays(days);
                        loadInsights(generation);
                    }

                    @Override
                    public void onDismissHint() {
                        SharedPreferences prefs = PreferenceManager
                                .getDefaultSharedPreferences(requireContext());
                        prefs.edit().putBoolean("hint_timeline_tap_entry", false).apply();
                        if (isCurrentView(generation) && screenController != null) {
                            screenController.dismissHint();
                        }
                    }

                    @Override
                    public void onRefresh() {
                        if (!isCurrentView(generation) || screenController == null)
                            return;
                        // loadTimeline() clears the indicator once the rebuilt
                        // rows arrive, as the SwipeRefreshLayout used to.
                        screenController.setRefreshing(true);
                        refreshAll();
                    }
                });
        lifecycleExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onResume() {
        super.onResume();
        DatabaseHelper.getInstance(requireContext()).addAccessChangedListener(accessListener);
        refreshAll();
        refreshHandler.removeCallbacks(periodicRunnable);
        refreshHandler.postDelayed(periodicRunnable, PERIODIC_REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        DatabaseHelper.getInstance(requireContext()).removeAccessChangedListener(accessListener);
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.removeCallbacks(periodicRunnable);
    }

    @Override
    public void onDestroyView() {
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.removeCallbacks(periodicRunnable);
        if (screenController != null) {
            screenController.invalidate();
            screenController = null;
        }
        if (lifecycleExecutor != null) {
            lifecycleExecutor.shutdownNow();
            lifecycleExecutor = null;
        }
        composeView = null;
        viewGeneration++;
        super.onDestroyView();
    }

    private boolean isCurrentView(int generation) {
        return generation == viewGeneration
                && isAdded()
                && getView() != null
                && screenController != null;
    }

    private void openEntry(int uid, String appName, String packageName) {
        if (packageName == null || !isAdded())
            return;

        Intent intent = new Intent(requireContext(), DetailsActivity.class);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_NAME, appName);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_PACKAGENAME, packageName);
        intent.putExtra(DetailsActivity.INTENT_EXTRA_APP_UID, uid);
        startActivity(intent);
    }

    private void refreshAll() {
        if (screenController == null || getView() == null)
            return;
        int generation = viewGeneration;
        loadTimeline(generation);
        loadInsights(generation);
    }

    // The query uses the application context and rejects stale view generations
    // before touching UI; migrating this bounded legacy AsyncTask is out of scope.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private void loadTimeline(final int generation) {
        if (!isCurrentView(generation))
            return;
        final int requestId = ++timelineRequestId;
        final Context context = requireContext().getApplicationContext();
        new AsyncTask<Void, Void, List<TimelineEntry>>() {
            @Override
            protected List<TimelineEntry> doInBackground(Void... voids) {
                return buildTimeline(context);
            }

            @Override
            protected void onPostExecute(List<TimelineEntry> entries) {
                if (!isCurrentView(generation) || screenController == null)
                    return;
                if (requestId != timelineRequestId)
                    return; // superseded by a request started after this one

                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(requireContext());
                boolean trackerControlEnabled = prefs.getBoolean("enabled", false);
                boolean trackerRecordingEnabled = prefs.getBoolean("log_app", true);
                boolean trackerRecordingAvailable = !Util.isPlayStoreInstall(requireContext());
                TimelineEmptyAdapter.EmptyState emptyState = TimelineEmptyAdapter.stateFor(
                        trackerControlEnabled,
                        trackerRecordingEnabled,
                        trackerRecordingAvailable);
                TimelineEmptyState composeEmptyState = TimelineEmptyState.valueOf(emptyState.name());
                boolean showHint = TimelineHintAdapter.shouldShow(
                        !entries.isEmpty(),
                        prefs.getBoolean("hint_timeline_tap_entry", true));
                screenController.updateTimeline(
                        entries,
                        requireContext(),
                        composeEmptyState,
                        showHint);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void loadInsights(final int generation) {
        final ExecutorService executor = lifecycleExecutor;
        if (executor == null || executor.isShutdown() || !isCurrentView(generation))
            return;
        final int requestId = ++insightsRequestId;
        final int days = insightsDays;
        final Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            InsightsData data = new InsightsDataProvider(context).computeInsights(days);
            refreshHandler.post(() -> {
                if (!isCurrentView(generation) || screenController == null)
                    return;
                if (requestId != insightsRequestId)
                    return; // superseded by a request started after this one
                screenController.updateInsights(data);
            });
        });
    }

    private List<TimelineEntry> buildTimeline(Context context) {
        DatabaseHelper dh = DatabaseHelper.getInstance(context);
        PackageManager pm = context.getPackageManager();
        // findTracker() reads from a static map populated lazily by
        // TrackerList.getInstance(). Without this call, opening the
        // app on the Timeline tab races with insights initialization
        // and every entry is silently dropped — appearing as an empty
        // "Watching for trackers…" screen even when there is data.
        TrackerList.getInstance(context);

        Map<Integer, Map<String, TrackerContact>> uidTrackers = new LinkedHashMap<>();
        Map<Integer, Long> uidLatestTime = new LinkedHashMap<>();
        Map<Integer, String[]> uidAppInfo = new LinkedHashMap<>();

        try (Cursor cursor = dh.getRecentTrackerActivity()) {
            if (cursor == null)
                return Collections.emptyList();

            int colUid = cursor.getColumnIndexOrThrow("uid");
            int colDaddr = cursor.getColumnIndexOrThrow("daddr");
            int colAllowed = cursor.getColumnIndexOrThrow("allowed");
            int colLastTime = cursor.getColumnIndexOrThrow("last_time");

            while (cursor.moveToNext()) {
                int uid = cursor.getInt(colUid);
                String daddr = cursor.getString(colDaddr);
                int allowed = cursor.getInt(colAllowed);
                long lastTime = cursor.getLong(colLastTime);

                Tracker tracker = TrackerList.findTracker(daddr);
                if (tracker == null)
                    continue;

                String companyName = tracker.getName();
                if (companyName == null)
                    continue;

                boolean blocked = allowed == 0;
                String category = tracker.getCategory();
                Map<String, TrackerContact> companyMap = uidTrackers.get(uid);
                if (companyMap == null) {
                    companyMap = new LinkedHashMap<>();
                    uidTrackers.put(uid, companyMap);
                }

                String key = companyName + "|" + blocked;
                TrackerContact existing = companyMap.get(key);
                if (existing == null || lastTime > existing.lastTime) {
                    companyMap.put(key, new TrackerContact(companyName, category, blocked, lastTime));
                }

                Long currentLatest = uidLatestTime.get(uid);
                if (currentLatest == null || lastTime > currentLatest)
                    uidLatestTime.put(uid, lastTime);

                if (!uidAppInfo.containsKey(uid)) {
                    // Keep activity for UIDs that belong to another profile,
                    // a clone, private space, or an app uninstalled since contact.
                    String appName = context.getString(R.string.unidentified_app_uid, uid);
                    String packageName = null;
                    String[] packages = Util.getPackagesForUid(pm, uid);
                    if (packages != null && packages.length > 0) {
                        packageName = packages[0];
                        appName = packageName;
                        try {
                            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                            appName = pm.getApplicationLabel(ai).toString();
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                    }
                    uidAppInfo.put(uid, new String[] { appName, packageName });
                }
            }
        }

        List<TimelineEntry> entries = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, TrackerContact>> e : uidTrackers.entrySet()) {
            int uid = e.getKey();
            String[] appInfo = uidAppInfo.get(uid);
            if (appInfo == null)
                continue;

            List<TrackerContact> trackers = new ArrayList<>(e.getValue().values());
            trackers.sort((a, b) -> {
                if (a.blocked != b.blocked)
                    return a.blocked ? -1 : 1;
                return Long.compare(b.lastTime, a.lastTime);
            });

            Long latestTime = uidLatestTime.get(uid);
            entries.add(new TimelineEntry(uid, appInfo[0], appInfo[1],
                    latestTime != null ? latestTime : 0, trackers));
        }

        entries.sort((a, b) -> Long.compare(b.mostRecentTime, a.mostRecentTime));
        return entries;
    }
}
