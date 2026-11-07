package net.kollnig.missioncontrol.diagnostics;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BatteryExperimentAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        BatteryExperimentController.get(context).alarm(intent.getStringExtra("id"), intent.getIntExtra("token", -1));
    }
}
