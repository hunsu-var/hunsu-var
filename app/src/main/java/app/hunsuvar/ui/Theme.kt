package app.hunsuvar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.hunsuvar.R
import app.hunsuvar.core.Outcome

// Design system v4 「빛의 선」 (../DESIGN.md, canvas SheetV4). Neutrals everywhere; 깜빡이 앰버 only on the blinker span,
// the value pin and the primary button; color otherwise = the three verdict words.

/** 깜빡이 앰버: the turn-signal color. Fill/line only; on light backgrounds text in it fails contrast (use [amberInk]). */
val Amber = Color(0xFFFFA51F)
val OnAmber = Color(0xFF15171A)

/** Verdict word colors. 기각 is ink, never red: red is a red card, it shames the loser. */
val Outcome.color: Color
    @Composable get() {
        val night = isSystemInDarkTheme()
        return when (this) {
            Outcome.HIT -> if (night) Color(0xFF3CCB84) else Color(0xFF0E7A4B)
            Outcome.REJECTED -> if (night) Color(0xFFF2F3F5) else Color(0xFF15171A)
            Outcome.UNDECIDABLE -> if (night) Color(0xFFA3A9B1) else Color(0xFF6A6E76)
        }
    }

/** Amber-tinted text ("이 규칙, 알고 있었나요?"). */
val amberInk: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFB84D) else Color(0xFF8A5300)

// Token → Material slot: bg=background, surface=surface, surface-2=surfaceVariant, line=outline,
// text=onSurface, text-sub=onSurfaceVariant, disabled=outlineVariant, accent(ink)=tertiary, on-accent=onTertiary,
// primary = 앰버 (primary button, selected/checked states), same in both modes.
private val Day = lightColorScheme(
    background = Color(0xFFEEF0F3), onBackground = Color(0xFF15171A),
    surface = Color.White, onSurface = Color(0xFF15171A),
    surfaceVariant = Color(0xFFF3F4F6), surfaceContainerHighest = Color(0xFFE2E5E9), onSurfaceVariant = Color(0xFF596069),
    outline = Color(0xFFDCDFE4), outlineVariant = Color(0xFF9AA0A8),
    primary = Amber, onPrimary = OnAmber,
    tertiary = Color(0xFF15171A), onTertiary = Color.White,
)
private val Night = darkColorScheme(
    background = Color(0xFF0F1114), onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF1A1D21), onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF23272C), surfaceContainerHighest = Color(0xFF23272C), onSurfaceVariant = Color(0xFFA3A9B1),
    outline = Color(0xFF30353B), outlineVariant = Color(0xFF5C6168),
    primary = Amber, onPrimary = OnAmber,
    tertiary = Color(0xFFF2F3F5), onTertiary = Color(0xFF15171A),
)
val ColorScheme.textSub get() = onSurfaceVariant
val ColorScheme.disabled get() = outlineVariant
val ColorScheme.accent get() = tertiary
val ColorScheme.onAccent get() = onTertiary

// Shapes: cards 28, inner boxes and chips 20, buttons pill.
val CardShape = RoundedCornerShape(28.dp)
val ControlShape = RoundedCornerShape(20.dp)
val PillShape = RoundedCornerShape(percent = 50)
private val ShadowInk = Color.Black.copy(alpha = 0.07f)

/** Card fill on bg. Day lifts it with a soft shadow; Night separates by surface color only. */
@Composable
fun Modifier.card(color: Color = MaterialTheme.colorScheme.surface, shape: Shape = CardShape): Modifier =
    (if (isSystemInDarkTheme()) this else shadow(8.dp, shape, ambientColor = ShadowInk, spotColor = ShadowInk)).background(color, shape)

// Asta Sans ships only as a variable font (wght 300–800) in google/fonts; each weight pins the axis.
@OptIn(ExperimentalTextApi::class)
private fun asta(w: Int) = Font(R.font.asta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val AstaSans = FontFamily(asta(400), asta(500), asta(600), asta(700), asta(800))

// App type scale: sp = DESIGN.md dp (sized for the 1700×1184 dp app area). Line 1.4, tracking −0.02em, tabular digits.
private fun type(size: Int, w: FontWeight) = TextStyle(
    fontFamily = AstaSans, fontWeight = w, fontSize = size.sp, lineHeight = (size * 1.4).sp,
    letterSpacing = (-0.02).em, fontFeatureSettings = "tnum",
)
val VerdictType = type(120, FontWeight.ExtraBold).copy(lineHeight = 124.sp, letterSpacing = (-0.025).em)
val Headline = type(48, FontWeight.Bold)
val Title = type(36, FontWeight.SemiBold)
val Body = type(32, FontWeight.Medium)
val Label = type(24, FontWeight.Medium)

/** Day by default; follows the car's system night mode (uiMode). */
@Composable
fun HunsuTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = if (isSystemInDarkTheme()) Night else Day,
    typography = Typography(displayLarge = VerdictType, headlineMedium = Headline, titleLarge = Title, bodyLarge = Body, labelLarge = Body),
    content = content,
)
