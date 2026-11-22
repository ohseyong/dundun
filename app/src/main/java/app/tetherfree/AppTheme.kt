package app.tetherfree

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.LineBreak

// ---- 색 ----

private val Accent = Color(0xFF2457D6)
private val AccentDark = Color(0xFF8DB1FF)

@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = AccentDark, onPrimary = Color(0xFF0B1B44),
        background = Color(0xFF121316), surface = Color(0xFF1B1C20),
        onBackground = Color(0xFFECECEE), onSurface = Color(0xFFECECEE),
        onSurfaceVariant = Color(0xFFA3A5AD), outlineVariant = Color(0xFF2E3036),
        error = Color(0xFFFF8A80),
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.White,
        background = Color(0xFFF6F6F4), surface = Color.White,
        onBackground = Color(0xFF16181D), onSurface = Color(0xFF16181D),
        onSurfaceVariant = Color(0xFF62656E), outlineVariant = Color(0xFFE6E6E3),
        error = Color(0xFFC62828),
    )
    val base = MaterialTheme.typography
    val phrase = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
    MaterialTheme(colorScheme = scheme, typography = base.copy(
        bodyLarge = base.bodyLarge.copy(lineBreak = phrase),
        bodyMedium = base.bodyMedium.copy(lineBreak = phrase),
    )) {
        ProvideTextStyle(LocalTextStyle.current.copy(lineBreak = phrase), content)
    }
}
