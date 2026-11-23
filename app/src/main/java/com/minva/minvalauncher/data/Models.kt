package com.minva.minvalauncher.data

enum class ThemeMode {
    BASE,
    CUSTOM,
}

enum class WallpaperMode {
    NONE,
    IMAGE,
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.BASE,
    val background: String = "#0B0B0B",
    val text: String = "#EDEDED",
    val muted: String = "#8A8A8A",
    val accent: String = "#C9A7F5",
)

data class Palette(
    val background: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
)

data class AppInfo(
    val label: String,
    val packageName: String,
    val activityName: String,
)

data class AppGroup(
    val id: String,
    var name: String,
    var collapsed: Boolean = false,
)

val BASE_PALETTE = Palette(
    background = 0xFF0B0B0B.toInt(),
    text = 0xFFEDEDED.toInt(),
    muted = 0xFF8A8A8A.toInt(),
    accent = 0xFFC9A7F5.toInt(),
)

fun parseColor(value: String, fallback: Int): Int = try {
    val hex = value.trim().removePrefix("#")
    when (hex.length) {
        6 -> android.graphics.Color.parseColor("#FF$hex")
        8 -> android.graphics.Color.parseColor("#$hex")
        else -> fallback
    }
} catch (error: IllegalArgumentException) {
    fallback
}
