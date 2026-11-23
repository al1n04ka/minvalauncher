package com.minva.minvalauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

class LauncherRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("minvalauncher_prefs", Context.MODE_PRIVATE)

    fun theme(): ThemeSettings {
        val mode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.BASE.name) ?: ThemeMode.BASE.name)
        }.getOrDefault(ThemeMode.BASE)
        val defaults = ThemeSettings()

        return ThemeSettings(
            mode = mode,
            background = prefs.getString(KEY_THEME_BG, defaults.background) ?: defaults.background,
            text = prefs.getString(KEY_THEME_TEXT, defaults.text) ?: defaults.text,
            muted = prefs.getString(KEY_THEME_MUTED, defaults.muted) ?: defaults.muted,
            accent = prefs.getString(KEY_THEME_ACCENT, defaults.accent) ?: defaults.accent,
        )
    }

    fun saveTheme(settings: ThemeSettings) {
        prefs.edit()
            .putString(KEY_THEME_MODE, settings.mode.name)
            .putString(KEY_THEME_BG, settings.background)
            .putString(KEY_THEME_TEXT, settings.text)
            .putString(KEY_THEME_MUTED, settings.muted)
            .putString(KEY_THEME_ACCENT, settings.accent)
            .apply()
    }

    fun palette(settings: ThemeSettings = theme()): Palette {
        if (settings.mode == ThemeMode.BASE) return BASE_PALETTE
        return Palette(
            background = parseColor(settings.background, BASE_PALETTE.background),
            text = parseColor(settings.text, BASE_PALETTE.text),
            muted = parseColor(settings.muted, BASE_PALETTE.muted),
            accent = parseColor(settings.accent, BASE_PALETTE.accent),
        )
    }

    fun wallpaperMode(): WallpaperMode = runCatching {
        WallpaperMode.valueOf(prefs.getString(KEY_WALLPAPER_MODE, WallpaperMode.NONE.name) ?: WallpaperMode.NONE.name)
    }.getOrDefault(WallpaperMode.NONE)

    fun wallpaperUri(): String? = prefs.getString(KEY_WALLPAPER_URI, null)

    fun saveWallpaper(mode: WallpaperMode, uri: String? = null) {
        val editor = prefs.edit().putString(KEY_WALLPAPER_MODE, mode.name)
        if (uri == null) {
            editor.remove(KEY_WALLPAPER_URI)
        } else {
            editor.putString(KEY_WALLPAPER_URI, uri)
        }
        editor.apply()
    }

    fun wallpaperOpacity(): Int = prefs.getInt(KEY_WALLPAPER_OPACITY, 60)

    fun saveWallpaperOpacity(value: Int) {
        prefs.edit().putInt(KEY_WALLPAPER_OPACITY, value.coerceIn(0, 100)).apply()
    }

    fun clearConfig() {
        prefs.edit().clear().apply()
    }

    fun loadApps(packageManager: PackageManager, selfPackage: String): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)

        return activities
            .asSequence()
            .mapNotNull { info ->
                val label = info.loadLabel(packageManager).toString().trim()
                val packageName = info.activityInfo?.packageName.orEmpty()
                val activityName = info.activityInfo?.name.orEmpty()
                if (label.isEmpty() || packageName.isEmpty() || activityName.isEmpty() || packageName == selfPackage) {
                    null
                } else {
                    AppInfo(label, packageName, activityName)
                }
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    fun loadGroups(): MutableList<AppGroup> {
        val raw = prefs.getString(KEY_GROUPS, null) ?: return defaultGroups()
        val result = mutableListOf<AppGroup>()

        for (line in raw.split("\n")) {
            val parts = line.split("|")
            if (parts.size < 3) continue
            result.add(AppGroup(parts[0], parts[1], parts[2] == "1"))
        }

        return if (result.isEmpty()) defaultGroups() else result
    }

    fun saveGroups(groups: List<AppGroup>) {
        val encoded = groups.joinToString("\n") { group ->
            listOf(
                group.id,
                group.name.replace(Regex("[|\n\r]"), " "),
                if (group.collapsed) "1" else "0",
            ).joinToString("|")
        }
        prefs.edit().putString(KEY_GROUPS, encoded).apply()
    }

    fun addGroup(name: String): AppGroup {
        val group = AppGroup(id = "g${System.currentTimeMillis()}", name = name.trim())
        val groups = loadGroups()
        groups.add(group)
        saveGroups(groups)
        return group
    }

    fun deleteGroup(id: String) {
        if (id == GROUP_DEFAULT || id == GROUP_FAVORITES) return
        saveGroups(loadGroups().filter { it.id != id })

        val editor = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(PREFIX_ASSIGN) && value == id) {
                editor.putString(key, GROUP_DEFAULT)
            }
        }
        editor.apply()
    }

    fun groupOf(packageName: String): String =
        prefs.getString(PREFIX_ASSIGN + packageName, GROUP_DEFAULT) ?: GROUP_DEFAULT

    fun setGroupOf(packageName: String, groupId: String) {
        prefs.edit().putString(PREFIX_ASSIGN + packageName, groupId).apply()
    }

    fun labelOf(packageName: String): String? = prefs.getString(PREFIX_LABEL + packageName, null)

    fun setLabel(packageName: String, label: String) {
        prefs.edit().putString(PREFIX_LABEL + packageName, label.trim()).apply()
    }

    fun clearLabel(packageName: String) {
        prefs.edit().remove(PREFIX_LABEL + packageName).apply()
    }

    private fun defaultGroups(): MutableList<AppGroup> = mutableListOf(
        AppGroup(GROUP_FAVORITES, "Favorites"),
        AppGroup(GROUP_DEFAULT, "Default"),
    )

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_THEME_BG = "theme_background"
        const val KEY_THEME_TEXT = "theme_text"
        const val KEY_THEME_MUTED = "theme_muted"
        const val KEY_THEME_ACCENT = "theme_accent"
        const val KEY_GROUPS = "groups"
        const val KEY_WALLPAPER_MODE = "wallpaper_mode"
        const val KEY_WALLPAPER_URI = "wallpaper_uri"
        const val KEY_WALLPAPER_OPACITY = "wallpaper_opacity"
        const val PREFIX_ASSIGN = "assign:"
        const val PREFIX_LABEL = "label:"
        const val GROUP_FAVORITES = "favorites"
        const val GROUP_DEFAULT = "default"
    }
}
