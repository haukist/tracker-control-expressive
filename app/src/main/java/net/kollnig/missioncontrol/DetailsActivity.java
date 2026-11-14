/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright © 2019–2026 Konrad Kollnig
 */

package net.kollnig.missioncontrol;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NavUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.opencsv.CSVWriter;

import net.kollnig.missioncontrol.data.InternetBlocklist;
import net.kollnig.missioncontrol.data.PlayStore;
import net.kollnig.missioncontrol.data.Tracker;
import net.kollnig.missioncontrol.data.TrackerBlocklist;
import net.kollnig.missioncontrol.data.TrackerList;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import eu.faircode.netguard.DatabaseHelper;
import eu.faircode.netguard.Util;

public class DetailsActivity extends AppCompatActivity {
    public static final String INTENT_EXTRA_APP_PACKAGENAME = "INTENT_APP_PACKAGENAME";
    public static final String INTENT_EXTRA_APP_UID = "INTENT_APP_UID";
    public static final String INTENT_EXTRA_APP_NAME = "INTENT_APP_NAME";
    private static final int REQUEST_EXPORT = 10;
    public static PlayStore.AppInfo app = null;
    private final String TAG = DetailsActivity.class.getSimpleName();
    private boolean running = false;
    private Integer appUid;
    private String appPackageName;
    private DetailsStateAdapter detailsStateAdapter;
    private ExportDatabaseCSVTask exportTask;
    private ViewPager2 viewPager;

    /**
     * Saves the changed tracker settings
     *
     * @param c The context
     */
    public static void savePrefs(Context c) {
        // Tracker settings
        TrackerBlocklist b = TrackerBlocklist.getInstance(c);
        b.saveSettings(c);

        // Internet settings. Routed through InternetBlocklist itself so that
        // package names pending resolution (apps blocked while not yet
        // installed) are persisted alongside the resolved UIDs, rather than
        // being dropped by a numeric-only rewrite of the preference.
        InternetBlocklist.getInstance(c).saveSettings(c);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        // Export to CSV?
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null) {
                exportTask = new ExportDatabaseCSVTask(data);
                exportTask.execute(); // export to CSV
            }
        } else {
            Log.w(TAG, "Unknown activity result request=" + requestCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Receive about details
        Intent intent = getIntent();
        appPackageName = intent.getStringExtra(INTENT_EXTRA_APP_PACKAGENAME);

        // The whole screen is about one app, and every tab needs its package
        // name. This activity is exported, so an intent without one can arrive
        // from anywhere; without this the Trackers tab crashes on a null
        // WorkManager tag. There is nothing to show, so close instead.
        if (TextUtils.isEmpty(appPackageName)) {
            finish();
            return;
        }

        running = true;

        appUid = intent.getIntExtra(INTENT_EXTRA_APP_UID, -1);
        String appName = intent.getStringExtra(INTENT_EXTRA_APP_NAME);
        if (TextUtils.isEmpty(appName))
            appName = appPackageName;
        // Opening Details means the user has found where to adjust protection.
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("hint_timeline_tap_entry", false)
                .apply();

        detailsStateAdapter = new DetailsStateAdapter(
                this,
                appPackageName,
                appName,
                appUid);
        viewPager = new ViewPager2(this);
        viewPager.setId(R.id.details_pager);
        viewPager.setAdapter(detailsStateAdapter);

        List<String> tabTitles = new ArrayList<>();
        for (int i = 0; i < detailsStateAdapter.getItemCount(); i++) {
            tabTitles.add(getString(detailsStateAdapter.getPageTitle(i)));
        }

        androidx.compose.ui.platform.ComposeView composeView =
                new androidx.compose.ui.platform.ComposeView(this);
        composeView.setId(R.id.compose_details);
        setContentView(composeView);

        net.kollnig.missioncontrol.ui.compose.DetailsScreen.installWithPager(
                composeView,
                appName,
                tabTitles,
                viewPager,
                new net.kollnig.missioncontrol.ui.compose.DetailsScreenCallbacks() {
                    @Override
                    public void onNavigateUp() {
                        NavUtils.navigateUpFromSameTask(DetailsActivity.this);
                    }

                    @Override
                    public void onLaunchApp() {
                        Intent launch = Common.getLaunchIntent(DetailsActivity.this, appPackageName);
                        if (launch != null)
                            startActivity(launch);
                        else
                            Toast.makeText(DetailsActivity.this, R.string.launch_app_failed, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onExportCsv() {
                        startActivityForResult(getIntentCreateExport(), REQUEST_EXPORT);
                    }

                    @Override
                    public void onUninstallApp() {
                        Intent uninstallIntent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        uninstallIntent.setData(Uri.parse("package:" + appPackageName));
                        startActivity(uninstallIntent);
                    }

                    @Override
                    public void onClearActivity() {
                        Util.areYouSure(DetailsActivity.this, R.string.msg_clear_activity, () -> {
                            if (running) {
                                DatabaseHelper dh = DatabaseHelper.getInstance(DetailsActivity.this);
                                dh.clearAccess(appUid, false);
                                detailsStateAdapter.updateTrackerLists();
                            }
                        });
                    }
                });
    }

    /**
     * Creates an intent to save a file. Necessary to determine destination of CSV
     * export.
     *
     * @return File intent
     */
    private Intent getIntentCreateExport() {
        Intent intent;
        intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, appPackageName + "_"
                + new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date().getTime()) + ".csv");
        return intent;
    }

    @Override
    public void onPause() {
        super.onPause();
        savePrefs(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Leaving mid-export must not leak this activity via the dialog, nor
        // via the task itself (WindowLeaked otherwise, and the task keeps
        // running until it finishes). The export continues writing to a
        // partial file at the user-chosen URI, which is left as-is.
        if (exportTask != null) {
            if (exportTask.dialog.isShowing())
                exportTask.dialog.dismiss();
            exportTask.cancel(false);
        }
        app = null;
        running = false;
    }

    /**
     * Export tracking findings to CSV
     */
    // onDestroy() cancels this task and dismisses its dialog; its UI callback is
    // also gated by running, so the export is deliberately lifecycle-bounded.
    @SuppressLint("StaticFieldLeak")
    class ExportDatabaseCSVTask extends AsyncTask<String, Void, Boolean> {
        private final ProgressDialog dialog = new ProgressDialog(DetailsActivity.this);
        TrackerList trackerList;
        Intent data;

        public ExportDatabaseCSVTask(Intent data) {
            this.data = data;
        }

        @Override
        protected void onPreExecute() {
            this.dialog.setMessage(getString(R.string.exporting));
            this.dialog.show();
            trackerList = TrackerList.getInstance(DetailsActivity.this);
        }

        protected Boolean doInBackground(final String... args) {
            Uri target = data.getData();
            if (data.hasExtra("org.openintents.extra.DIR_PATH"))
                target = Uri.parse(target + "/" + appPackageName + "_"
                        + new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date().getTime()) + ".csv");
            Log.i(TAG, "Writing URI=" + target);

            try (OutputStream out = getContentResolver().openOutputStream(target)) {
                csvExport(out);
                return true;
            } catch (Throwable ex) {
                Log.e(TAG, ex + "\n" + Log.getStackTraceString(ex));
                return false;
            }
        }

        /**
         * Retrieve information to be exported from database and save to CSV file.
         *
         * @param out The destination for the exported information
         * @throws IOException If saving the CSV fails
         */
        private void csvExport(OutputStream out) throws IOException {
            try (CSVWriter csv = new CSVWriter(new OutputStreamWriter(out),
                    CSVWriter.DEFAULT_SEPARATOR,
                    CSVWriter.DEFAULT_QUOTE_CHARACTER,
                    CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                    CSVWriter.RFC4180_LINE_END)) {

                try (Cursor data = trackerList.getAppInfo(appUid)) {
                    if (data == null)
                        throw new IOException("Could not read hosts.");

                    List<String> columnNames = new ArrayList<>();
                    Collections.addAll(columnNames, data.getColumnNames());
                    columnNames.add("Tracker Name");
                    columnNames.add("Tracker Category");

                    csv.writeNext(columnNames.toArray(new String[0]));
                    while (data.moveToNext()) {
                        if (isCancelled())
                            break;

                        String[] row = new String[data.getColumnNames().length + 2];
                        for (int i = 0; i < data.getColumnNames().length; i++) {
                            row[i] = data.getString(i);
                        }

                        String hostname = data.getString(data.getColumnIndexOrThrow("daddr"));
                        Tracker tracker = TrackerList.findTracker(hostname);
                        if (tracker != null) {
                            row[data.getColumnNames().length] = tracker.getName();
                            row[data.getColumnNames().length + 1] = tracker.getCategory();
                        } else {
                            row[data.getColumnNames().length] = "";
                            row[data.getColumnNames().length + 1] = "";
                        }

                        csv.writeNext(row);
                    }
                }
            }
        }

        protected void onPostExecute(final Boolean success) {
            exportTask = null;
            if (this.dialog.isShowing())
                this.dialog.dismiss();

            if (running) {
                if (success) {
                    if (viewPager != null) {
                        Snackbar.make(viewPager, R.string.exported, Snackbar.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(DetailsActivity.this, R.string.exported, Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Toast.makeText(DetailsActivity.this, R.string.export_failed, Toast.LENGTH_SHORT).show();
                }
            }
        }

        @Override
        protected void onCancelled() {
            // Reached when onDestroy() cancelled us: nothing left to show, and
            // the dialog was already dismissed there if it was still showing.
            exportTask = null;
            if (this.dialog.isShowing())
                this.dialog.dismiss();
        }
    }
}
