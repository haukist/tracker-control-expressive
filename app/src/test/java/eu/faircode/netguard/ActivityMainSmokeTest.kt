package eu.faircode.netguard

import android.view.View
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityMainSmokeTest {
    @Test
    fun launchAndRecreateAttachTimelineFragmentContainer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt("onboarding_version", Int.MAX_VALUE)
            .commit()

        ActivityScenario.launch(ActivityMain::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val container = activity.findViewById<View>(R.id.timelineContainer)
                assertNotNull(container)
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.timelineContainer)
                assertNotNull(fragment)
                assertTrue(fragment!!.isAdded)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val container = activity.findViewById<View>(R.id.timelineContainer)
                assertNotNull(container)
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.timelineContainer)
                assertNotNull(fragment)
                assertTrue(fragment!!.isAdded)
            }
        }
    }
}
