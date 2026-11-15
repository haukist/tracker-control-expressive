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
 */
package net.kollnig.missioncontrol.ui.compose

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import net.kollnig.missioncontrol.R

/** Preference key for opt-in Android 12+ Material You dynamic color. */
const val PREF_DYNAMIC_COLOR = "dynamic_color"

/**
 * Material 3 Expressive theme for TrackerControl.
 *
 * Uses [MaterialExpressiveTheme] with [MotionScheme.expressive], expanded tonal
 * surface containers, and emphasized typography/shapes. Defaults to
 * TrackerControl's red/teal brand palette and supports opt-in Dynamic Color
 * on Android 12+ via [PREF_DYNAMIC_COLOR].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TrackerControlTheme(
    useDynamicColor: Boolean = rememberDynamicColorPreference(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isDarkTheme = (configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val colourScheme = remember(context, isDarkTheme, useDynamicColor) {
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (isDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            isDarkTheme -> trackerControlDarkColours(context)
            else -> trackerControlLightColours(context)
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colourScheme,
        motionScheme = MotionScheme.expressive(),
        typography = TrackerControlExpressiveTypography,
        shapes = TrackerControlExpressiveShapes,
        content = content
    )
}

@Composable
private fun rememberDynamicColorPreference(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) {
        runCatching { PreferenceManager.getDefaultSharedPreferences(context) }.getOrNull()
    }
    var enabled by remember(prefs) {
        mutableStateOf(prefs?.getBoolean(PREF_DYNAMIC_COLOR, false) ?: false)
    }
    DisposableEffect(prefs) {
        if (prefs == null) return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == PREF_DYNAMIC_COLOR) {
                enabled = sp.getBoolean(PREF_DYNAMIC_COLOR, false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

private val BaseTypography = Typography()

internal val TrackerControlExpressiveTypography = BaseTypography.copy(
    displayMedium = BaseTypography.displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = BaseTypography.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
)

internal val TrackerControlExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

internal fun trackerControlLightColours(context: Context) = lightColorScheme(
    primary = context.colour(R.color.colorRedOn),
    onPrimary = Color.White,
    primaryContainer = context.colour(R.color.colorPrimaryLight),
    onPrimaryContainer = Color(0xFF410002),
    secondary = context.colour(R.color.colorAccent),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00251A),
    tertiary = Color(0xFF7D5700),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF271900),
    background = context.themeColour(android.R.attr.colorBackground),
    onBackground = context.themeColour(com.google.android.material.R.attr.colorOnBackground),
    surface = context.themeColour(com.google.android.material.R.attr.colorSurface),
    onSurface = context.themeColour(com.google.android.material.R.attr.colorOnSurface),
    surfaceDim = context.themeColour(com.google.android.material.R.attr.colorSurfaceDim),
    surfaceBright = context.themeColour(com.google.android.material.R.attr.colorSurfaceBright),
    surfaceVariant = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainer),
    onSurfaceVariant = context.themeColour(com.google.android.material.R.attr.colorOnSurfaceVariant),
    surfaceContainerLowest = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainerLowest),
    surfaceContainerLow = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainerLow),
    surfaceContainer = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainer),
    surfaceContainerHigh = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainerHigh),
    surfaceContainerHighest = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainerHighest),
    inverseSurface = context.themeColour(com.google.android.material.R.attr.colorSurfaceInverse),
    inverseOnSurface = context.themeColour(com.google.android.material.R.attr.colorOnSurfaceInverse),
    inversePrimary = context.themeColour(com.google.android.material.R.attr.colorPrimaryInverse),
    outline = context.themeColour(com.google.android.material.R.attr.colorOutline),
    outlineVariant = context.themeColour(com.google.android.material.R.attr.colorOutlineVariant),
    error = context.colour(R.color.colorRedOn),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

internal fun trackerControlDarkColours(context: Context) = darkColorScheme(
    // colorRedOn is deliberately brighter in values-night so small actions and
    // status labels retain contrast on the theme-derived dark surface.
    primary = context.colour(R.color.colorRedOn),
    onPrimary = Color.Black,
    primaryContainer = context.colour(R.color.colorPrimaryDark),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = context.colour(R.color.colorAccent),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF004D40),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = Color(0xFFF5BF48),
    onTertiary = Color(0xFF422C00),
    tertiaryContainer = Color(0xFF5F4100),
    onTertiaryContainer = Color(0xFFFFDEA6),
    background = context.themeColour(android.R.attr.colorBackground),
    onBackground = context.themeColour(com.google.android.material.R.attr.colorOnBackground),
    surface = context.themeColour(com.google.android.material.R.attr.colorSurface),
    onSurface = context.themeColour(com.google.android.material.R.attr.colorOnSurface),
    surfaceDim = context.themeColour(com.google.android.material.R.attr.colorSurfaceDim),
    surfaceBright = context.themeColour(com.google.android.material.R.attr.colorSurfaceBright),
    surfaceVariant = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainer),
    onSurfaceVariant = context.themeColour(com.google.android.material.R.attr.colorOnSurfaceVariant),
    surfaceContainerLowest = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainerLowest),
    surfaceContainerLow = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainerLow),
    surfaceContainer = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainer),
    surfaceContainerHigh = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainerHigh),
    surfaceContainerHighest = context.themeColour(com.google.android.material.R.attr.colorSurfaceContainerHighest),
    inverseSurface = context.themeColour(com.google.android.material.R.attr.colorSurfaceInverse),
    inverseOnSurface = context.themeColour(com.google.android.material.R.attr.colorOnSurfaceInverse),
    inversePrimary = context.themeColour(com.google.android.material.R.attr.colorPrimaryInverse),
    outline = context.themeColour(com.google.android.material.R.attr.colorOutline),
    outlineVariant = context.themeColour(com.google.android.material.R.attr.colorOutlineVariant),
    error = context.colour(R.color.colorRedOn),
    onError = Color.Black,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private fun Context.themeColour(@AttrRes attribute: Int): Color {
    val value = TypedValue()
    check(theme.resolveAttribute(attribute, value, true)) {
        "Required theme colour attribute $attribute is missing"
    }

    return when {
        value.resourceId != 0 -> Color(ContextCompat.getColor(this, value.resourceId))
        value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT ->
            Color(value.data)
        else -> error("Required theme colour attribute $attribute is not a colour")
    }
}

private fun Context.colour(@ColorRes resource: Int): Color =
    Color(ContextCompat.getColor(this, resource))
