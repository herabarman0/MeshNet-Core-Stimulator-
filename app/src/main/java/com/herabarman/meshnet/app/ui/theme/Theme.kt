package com.herabarman.meshnet.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.herabarman.meshnet.app.R

val MeshBg = Color(0xFF0B0F17)
val MeshPanel = Color(0xFF121927)
val MeshPanelElevated = Color(0xFF182236)
val MeshOutline = Color(0xFF26304A)
val MeshMint = Color(0xFF00E5A0)
val MeshCobalt = Color(0xFF4DA3FF)
val MeshAmber = Color(0xFFFFB84D)
val MeshCoral = Color(0xFFFF5C5C)
val MeshViolet = Color(0xFFA29BFE)
val MeshText = Color(0xFFE6E9EF)
val MeshTextDim = Color(0xFF8B93A5)

val DisplayFont = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

val BodyFont = FontFamily(
    Font(R.font.noto_sans_bengali, FontWeight.Normal),
    Font(R.font.noto_sans_bengali, FontWeight.Medium),
    Font(R.font.noto_sans_bengali, FontWeight.Bold)
)

val MonoFont = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)

private val MeshColorScheme = darkColorScheme(
    primary = MeshMint,
    onPrimary = Color(0xFF04110C),
    primaryContainer = Color(0xFF0C3B2D),
    onPrimaryContainer = MeshMint,
    secondary = MeshCobalt,
    onSecondary = Color(0xFF06162A),
    secondaryContainer = Color(0xFF162C4A),
    onSecondaryContainer = MeshCobalt,
    tertiary = MeshAmber,
    onTertiary = Color(0xFF241600),
    background = MeshBg,
    onBackground = MeshText,
    surface = MeshPanel,
    onSurface = MeshText,
    surfaceVariant = MeshPanelElevated,
    onSurfaceVariant = MeshTextDim,
    outline = MeshOutline,
    error = MeshCoral,
    onError = Color(0xFF2A0707)
)

val MeshTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = DisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelMedium = TextStyle(
        fontFamily = MonoFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = BodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

val MeshShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp)
)

@Composable
fun MeshNetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshColorScheme,
        typography = MeshTypography,
        shapes = MeshShapes,
        content = content
    )
}
