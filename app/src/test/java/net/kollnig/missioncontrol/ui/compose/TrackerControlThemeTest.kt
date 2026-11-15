package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import net.kollnig.missioncontrol.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class TrackerControlThemeTest {
    @Test
    @Config(qualifiers = "notnight")
    fun lightColourSchemeMatchesMaterialThemeAttributes() {
        val context = themedContext()
        val scheme = trackerControlLightColours(context)

        assertSchemeMatchesTheme(context, scheme)
        val lumBg = ColorUtils.calculateLuminance(scheme.background.toArgb())
        val lumLow = ColorUtils.calculateLuminance(scheme.surfaceContainerLow.toArgb())
        val lumContainer = ColorUtils.calculateLuminance(scheme.surfaceContainer.toArgb())
        val lumHigh = ColorUtils.calculateLuminance(scheme.surfaceContainerHigh.toArgb())
        val lumHighest = ColorUtils.calculateLuminance(scheme.surfaceContainerHighest.toArgb())
        assertTrue(lumBg > lumLow && lumLow > lumContainer && lumContainer > lumHigh && lumHigh > lumHighest)
    }

    @Test
    @Config(qualifiers = "night")
    fun darkColourSchemeMatchesMaterialThemeAttributes() {
        val context = themedContext()
        val scheme = trackerControlDarkColours(context)

        assertSchemeMatchesTheme(context, scheme)
        val lumBg = ColorUtils.calculateLuminance(scheme.background.toArgb())
        val lumLow = ColorUtils.calculateLuminance(scheme.surfaceContainerLow.toArgb())
        val lumContainer = ColorUtils.calculateLuminance(scheme.surfaceContainer.toArgb())
        val lumHigh = ColorUtils.calculateLuminance(scheme.surfaceContainerHigh.toArgb())
        val lumHighest = ColorUtils.calculateLuminance(scheme.surfaceContainerHighest.toArgb())
        assertTrue(lumBg < lumLow && lumLow < lumContainer && lumContainer < lumHigh && lumHigh < lumHighest)
    }

    private fun themedContext(): Context {
        val application = RuntimeEnvironment.getApplication()
        return ContextThemeWrapper(application, R.style.AppThemeRed)
    }

    private fun assertSchemeMatchesTheme(
        context: Context,
        scheme: androidx.compose.material3.ColorScheme
    ) {
        assertEquals(themeColour(context, android.R.attr.colorBackground), scheme.background.toArgb())
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOnBackground),
            scheme.onBackground.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorSurface),
            scheme.surface.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOnSurface),
            scheme.onSurface.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorSurfaceContainer),
            scheme.surfaceVariant.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOnSurfaceVariant),
            scheme.onSurfaceVariant.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOutline),
            scheme.outline.toArgb()
        )
        assertEquals(
            themeColour(context, com.google.android.material.R.attr.colorOutlineVariant),
            scheme.outlineVariant.toArgb()
        )
        assertTrue(scheme.surfaceContainerLow != androidx.compose.ui.graphics.Color.Unspecified)
        assertTrue(scheme.surfaceContainerHigh != androidx.compose.ui.graphics.Color.Unspecified)
        assertTrue(scheme.tertiaryContainer != androidx.compose.ui.graphics.Color.Unspecified)
    }

    private fun themeColour(context: Context, attribute: Int): Int {
        val value = android.util.TypedValue()
        assertTrue("Theme attribute $attribute must be resource-backed", context.theme.resolveAttribute(attribute, value, true))
        assertTrue("Theme attribute $attribute must resolve to a colour resource", value.resourceId != 0)
        return androidx.core.content.ContextCompat.getColor(context, value.resourceId)
    }
}
