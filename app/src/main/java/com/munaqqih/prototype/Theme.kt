package com.munaqqih.prototype

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

// ألوان منقّح: حبر بترولي للهيكل، أخضر مريمية للأفعال، وأحمر «قلم المحرر» لكل شي فيه مشكلة (مفقود / شاذ)
object Palette {
    val Ink = Color(0xFF10303A)
    val InkSoft = Color(0xFF1C4652)
    val Paper = Color(0xFFF4F6F3)
    val Card = Color(0xFFFFFFFF)
    val Sage = Color(0xFF1F7A64)
    val SageTint = Color(0xFFE3F1EC)
    val Marker = Color(0xFFD2432F)
    val MarkerTint = Color(0xFFFBE9E5)
    val Mist = Color(0xFFDCE4E0)
    val Text = Color(0xFF1B2B30)
    val Muted = Color(0xFF5B6E75)
}

// أرقام بعرض ثابت عشان الأعمدة في الجداول تصطف
val Tabular = TextStyle(fontFeatureSettings = "tnum")

private val Scheme = lightColorScheme(
    primary = Palette.Sage, onPrimary = Color.White,
    primaryContainer = Palette.SageTint, onPrimaryContainer = Palette.Ink,
    secondary = Palette.Ink, onSecondary = Color.White,
    secondaryContainer = Palette.SageTint, onSecondaryContainer = Palette.Ink,
    background = Palette.Paper, onBackground = Palette.Text,
    surface = Palette.Card, onSurface = Palette.Text,
    surfaceVariant = Palette.Paper, onSurfaceVariant = Palette.Muted,
    outline = Palette.Muted, outlineVariant = Palette.Mist,
    error = Palette.Marker, errorContainer = Palette.MarkerTint
)

@Composable
fun MunaqqihTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
