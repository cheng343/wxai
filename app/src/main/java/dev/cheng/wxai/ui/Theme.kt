package dev.cheng.wxai.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 绿色 Material 3 色板，数值取自 WeKit
 * (dev.ujhhgtg.wekit.ui.utils.theme.Color / Theme)。
 *
 * 说明：这里只保留模块实际用到的角色，并固定使用这套品牌色。
 * Android 12+ 的动态取色会让本模块看起来和 WeKit 不一致，因此不做动态取色。
 */

private val PrimaryLight = Color(0xFF336940)
private val OnPrimaryLight = Color(0xFFFFFFFF)
private val PrimaryContainerLight = Color(0xFFB5F1BD)
private val OnPrimaryContainerLight = Color(0xFF19512A)
private val SecondaryLight = Color(0xFF506352)
private val OnSecondaryLight = Color(0xFFFFFFFF)
private val SecondaryContainerLight = Color(0xFFD3E8D2)
private val OnSecondaryContainerLight = Color(0xFF394B3B)
private val TertiaryLight = Color(0xFF39656E)
private val OnTertiaryLight = Color(0xFFFFFFFF)
private val TertiaryContainerLight = Color(0xFFBDEAF4)
private val OnTertiaryContainerLight = Color(0xFF204D55)
private val ErrorLight = Color(0xFFBA1A1A)
private val OnErrorLight = Color(0xFFFFFFFF)
private val ErrorContainerLight = Color(0xFFFFDAD6)
private val OnErrorContainerLight = Color(0xFF93000A)
private val BackgroundLight = Color(0xFFF6FBF2)
private val OnBackgroundLight = Color(0xFF181D18)
private val SurfaceLight = Color(0xFFF6FBF2)
private val OnSurfaceLight = Color(0xFF181D18)
private val SurfaceVariantLight = Color(0xFFDDE5DA)
private val OnSurfaceVariantLight = Color(0xFF414941)
private val OutlineLight = Color(0xFF717970)
private val OutlineVariantLight = Color(0xFFC1C9BE)
private val ScrimLight = Color(0xFF000000)
private val InverseSurfaceLight = Color(0xFF2D322C)
private val InverseOnSurfaceLight = Color(0xFFEEF2EA)
private val InversePrimaryLight = Color(0xFF99D4A2)
private val SurfaceDimLight = Color(0xFFD7DBD3)
private val SurfaceBrightLight = Color(0xFFF6FBF2)
private val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
private val SurfaceContainerLowLight = Color(0xFFF1F5ED)
private val SurfaceContainerLight = Color(0xFFEBEFE7)
private val SurfaceContainerHighLight = Color(0xFFE5EAE1)
private val SurfaceContainerHighestLight = Color(0xFFDFE4DC)

private val PrimaryDark = Color(0xFF99D4A2)
private val OnPrimaryDark = Color(0xFF003917)
private val PrimaryContainerDark = Color(0xFF19512A)
private val OnPrimaryContainerDark = Color(0xFFB5F1BD)
private val SecondaryDark = Color(0xFFB7CCB7)
private val OnSecondaryDark = Color(0xFF233426)
private val SecondaryContainerDark = Color(0xFF394B3B)
private val OnSecondaryContainerDark = Color(0xFFD3E8D2)
private val TertiaryDark = Color(0xFFA2CED8)
private val OnTertiaryDark = Color(0xFF00363E)
private val TertiaryContainerDark = Color(0xFF204D55)
private val OnTertiaryContainerDark = Color(0xFFBDEAF4)
private val ErrorDark = Color(0xFFFFB4AB)
private val OnErrorDark = Color(0xFF690005)
private val ErrorContainerDark = Color(0xFF93000A)
private val OnErrorContainerDark = Color(0xFFFFDAD6)
private val BackgroundDark = Color(0xFF101510)
private val OnBackgroundDark = Color(0xFFDFE4DC)
private val SurfaceDark = Color(0xFF101510)
private val OnSurfaceDark = Color(0xFFDFE4DC)
private val SurfaceVariantDark = Color(0xFF414941)
private val OnSurfaceVariantDark = Color(0xFFC1C9BE)
private val OutlineDark = Color(0xFF8B9389)
private val OutlineVariantDark = Color(0xFF414941)
private val ScrimDark = Color(0xFF000000)
private val InverseSurfaceDark = Color(0xFFDFE4DC)
private val InverseOnSurfaceDark = Color(0xFF2D322C)
private val InversePrimaryDark = Color(0xFF336940)
private val SurfaceDimDark = Color(0xFF101510)
private val SurfaceBrightDark = Color(0xFF353A35)
private val SurfaceContainerLowestDark = Color(0xFF0B0F0B)
private val SurfaceContainerLowDark = Color(0xFF181D18)
private val SurfaceContainerDark = Color(0xFF1C211C)
private val SurfaceContainerHighDark = Color(0xFF262B26)
private val SurfaceContainerHighestDark = Color(0xFF313631)

private val WxAiLightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    scrim = ScrimLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    inversePrimary = InversePrimaryLight,
    surfaceDim = SurfaceDimLight,
    surfaceBright = SurfaceBrightLight,
    surfaceContainerLowest = SurfaceContainerLowestLight,
    surfaceContainerLow = SurfaceContainerLowLight,
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceContainerHighestLight,
)

private val WxAiDarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    scrim = ScrimDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    inversePrimary = InversePrimaryDark,
    surfaceDim = SurfaceDimDark,
    surfaceBright = SurfaceBrightDark,
    surfaceContainerLowest = SurfaceContainerLowestDark,
    surfaceContainerLow = SurfaceContainerLowDark,
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceContainerHighestDark,
)

private val WxAiTypography = Typography()

@Composable
fun WxAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) WxAiDarkColors else WxAiLightColors,
        typography = WxAiTypography,
        content = content,
    )
}
