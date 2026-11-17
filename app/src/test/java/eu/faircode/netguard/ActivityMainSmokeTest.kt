package eu.faircode.netguard

import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityMainSmokeTest {
    @Test
    fun launchAndRecreateAttachViewPagerFragments() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt("onboarding_version", Int.MAX_VALUE)
            .commit()

        ActivityScenario.launch(ActivityMain::class.java).use { scenario ->
            scenario.onActivity { activity ->
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val pager = activity.findViewById<ViewPager2>(R.id.main_fragment_pager)
                assertNotNull(pager)
                assertEquals(2, pager.adapter?.itemCount)
                val timeline = activity.supportFragmentManager.findFragmentByTag("f0")
                assertNotNull(timeline)
                assertTrue(timeline!!.isAdded)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val pager = activity.findViewById<ViewPager2>(R.id.main_fragment_pager)
                assertNotNull(pager)
                val timeline = activity.supportFragmentManager.findFragmentByTag("f0")
                assertNotNull(timeline)
                assertTrue(timeline!!.isAdded)
            }
        }
    }
}
