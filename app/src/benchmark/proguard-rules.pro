# BatteryExperimentHooks loads this controller and these entry points by reflection.
-keep class net.kollnig.missioncontrol.diagnostics.BatteryExperimentController {
    public static void initialize(android.content.Context);
    public static void configurePreference(androidx.appcompat.app.AppCompatActivity, androidx.preference.PreferenceScreen);
}
