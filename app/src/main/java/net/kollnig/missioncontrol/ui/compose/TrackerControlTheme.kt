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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.kollnig.missioncontrol.R

/** Preference key for opt-in Android 12+ Material You dynamic color. */
const val PREF_DYNAMIC_COLOR = "dynamic_color"

internal fun isDynamicColorEnabled(prefs: SharedPreferences?): Boolean =
    prefs?.getBoolean(PREF_DYNAMIC_COLOR, false) ?: false

/**
 * Wraps [context] with Material You dynamic colors when [PREF_DYNAMIC_COLOR] is
 * enabled on Android 12+, so View-based dialogs match the Compose theme.
 *
 * @return A `ContextThemeWrapper` with the dynamic color overlay applied when
 * enabled on Android 12+, or the original [context] otherwise (do not cast to `Activity`).
 */
fun wrapDynamicColorContext(context: Context): Context {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return context
    val prefs = runCatching { PreferenceManager.getDefaultSharedPreferences(context) }.getOrNull()
    if (!isDynamicColorEnabled(prefs)) return context
    return DynamicColors.wrapContextIfAvailable(context)
}

/**
 * Creates a [MaterialAlertDialogBuilder] themed with [wrapDynamicColorContext].
 */
fun themedDialogBuilder(context: Context): MaterialAlertDialogBuilder =
    MaterialAlertDialogBuilder(wrapDynamicColorContext(context))

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
        mutableStateOf(isDynamicColorEnabled(prefs))
    }
    DisposableEffect(prefs) {
        if (prefs == null) return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            if (key == PREF_DYNAMIC_COLOR) {
                enabled = isDynamicColorEnabled(sp)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

@OptIn(ExperimentalTextApi::class)
private fun googleSansFlexFontFamily(roundness: Float? = null): FontFamily {
    val weights = listOf(
        FontWeight.Light to 300,
        FontWeight.Normal to 400,
        FontWeight.Medium to 500,
        FontWeight.SemiBold to 600,
        FontWeight.Bold to 700
    )
    return FontFamily(
        weights.map { (fontWeight, weightValue) ->
            val settings = if (roundness != null) {
                FontVariation.Settings(
                    FontVariation.weight(weightValue),
                    FontVariation.Setting("ROND", roundness)
                )
            } else {
                FontVariation.Settings(FontVariation.weight(weightValue))
            }
            Font(
                resId = R.font.google_sans_flex,
                weight = fontWeight,
                variationSettings = settings
            )
        }
    )
}

internal val GoogleSansFlexFontFamily = googleSansFlexFontFamily()
internal val GoogleSansFlexRoundedFontFamily = googleSansFlexFontFamily(roundness = 100f)

private val BaseTypography = Typography()

internal val TrackerControlExpressiveTypography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = GoogleSansFlexFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(
        fontFamily = GoogleSansFlexFontFamily,
        fontWeight = FontWeight.Bold
    ),
    displaySmall = BaseTypography.displaySmall.copy(
        fontFamily = GoogleSansFlexFontFamily,
        fontWeight = FontWeight.Bold
    ),
    headlineLarge = BaseTypography.headlineLarge.copy(
        fontFamily = GoogleSansFlexFontFamily,
        fontWeight = FontWeight.Bold
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontFamily = GoogleSansFlexFontFamily,
        fontWeight = FontWeight.SemiBold
    ),
    headlineSmall = BaseTypography.headlineSmall.copy(
        fontFamily = GoogleSansFlexFontFamily,
        fontWeight = FontWeight.SemiBold
    ),
    titleLarge = BaseTypography.titleLarge.copy(
        fontFamily = GoogleSansFlexFontFamily,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = BaseTypography.titleMedium.copy(
        fontFamily = GoogleSansFlexFontFamily,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = GoogleSansFlexFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = GoogleSansFlexFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = GoogleSansFlexFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = GoogleSansFlexFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = GoogleSansFlexFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = GoogleSansFlexFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = GoogleSansFlexFontFamily)
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
    // Material 3 uses secondaryContainer for NavigationBar/Rail pills, FilterChips,
    // and tonal surfaces; keep secondary in the red tonal family so navigation stays red.
    secondary = Color(0xFF775654),
    onSecondary = Color.White,
    secondaryContainer = context.colour(R.color.bottom_nav_active_indicator),
    onSecondaryContainer = Color(0xFF410002),
    tertiary = context.colour(R.color.colorAccent),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB2DFDB),
    onTertiaryContainer = Color(0xFF00251A),
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
    secondary = Color(0xFFE7BDBB),
    onSecondary = Color(0xFF442928),
    secondaryContainer = context.colour(R.color.bottom_nav_active_indicator),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = context.colour(R.color.colorAccent),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF004D40),
    onTertiaryContainer = Color(0xFFB2DFDB),
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
