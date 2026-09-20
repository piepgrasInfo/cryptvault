package info.piepgras.cryptvault.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = BrandDark,
    secondary = BrandGreyDark,
    tertiary = AccentDark,
)

private val LightColorScheme = lightColorScheme(
    primary = Brand,
    secondary = BrandGrey,
    tertiary = Accent,
)

/**
 * Dynamic colour is off. The palette in `Color.kt` is a house rule shared with Flip Cards
 * and Work Time Tracker, and a wallpaper palette would silently replace it on Android 12+ —
 * most devices — leaving the shared blue visible only on the launcher icon and the store
 * listing. Re-enabling it is a decision about the whole family of apps, not about this file.
 */
@Composable
fun CryptVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
