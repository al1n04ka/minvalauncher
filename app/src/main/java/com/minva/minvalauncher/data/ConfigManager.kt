package com.minva.minvalauncher.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

class ConfigManager(
    private val context: Context,
    private val repository: LauncherRepository,
) {
    fun export(uri: Uri): Boolean {
        return try {
        val theme = repository.theme()
        val wallpaper = JSONObject().apply {
            put("mode", repository.wallpaperMode().name)
            put("uri", repository.wallpaperUri().orEmpty())
            put("opacity", repository.wallpaperOpacity())
        }
        val groups = JSONArray().apply {
            repository.loadGroups().forEach { group ->
                put(JSONObject().apply {
                    put("id", group.id)
                    put("name", group.name)
                    put("collapsed", group.collapsed)
                })
            }
        }
        val assignments = JSONObject()
        val labels = JSONObject()
        repository.loadApps(context.packageManager, context.packageName).forEach { app ->
            val group = repository.groupOf(app.packageName)
            if (group != "default") assignments.put(app.packageName, group)
            repository.labelOf(app.packageName)?.let { labels.put(app.packageName, it) }
        }

        val root = JSONObject().apply {
            put("config_format", "minvalauncher")
            put("version", 1)
            put("theme", JSONObject().apply {
                put("mode", theme.mode.name)
                put("background", theme.background)
                put("text", theme.text)
                put("muted", theme.muted)
                put("accent", theme.accent)
            })
            put("wallpaper", wallpaper)
            put("groups", groups)
            put("assignments", assignments)
            put("labels", labels)
        }

        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(root.toString(2).toByteArray())
        } ?: return false
        true
        } catch (error: Exception) {
            error.printStackTrace()
            false
        }
    }

    fun import(uri: Uri): Boolean {
        return try {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().readText()
        } ?: return false

        val json = JSONObject(text)
        repository.clearConfig()
        if (json.optString("config_format") == "minvalauncher") {
            importNative(json)
        } else {
            importMako(json)
        }
        true
        } catch (error: Exception) {
            error.printStackTrace()
            false
        }
    }

    private fun importNative(json: JSONObject) {
        json.optJSONObject("theme")?.let { theme ->
            val mode = runCatching {
                ThemeMode.valueOf(theme.optString("mode", ThemeMode.CUSTOM.name))
            }.getOrDefault(ThemeMode.CUSTOM)
            repository.saveTheme(
                ThemeSettings(
                    mode = mode,
                    background = theme.optString("background", ThemeSettings().background),
                    text = theme.optString("text", ThemeSettings().text),
                    muted = theme.optString("muted", ThemeSettings().muted),
                    accent = theme.optString("accent", ThemeSettings().accent),
                ),
            )
        }

        json.optJSONObject("wallpaper")?.let { wallpaper ->
            val mode = runCatching {
                WallpaperMode.valueOf(wallpaper.optString("mode", WallpaperMode.NONE.name))
            }.getOrDefault(WallpaperMode.NONE)
            val uri = wallpaper.optString("uri").takeIf { it.isNotEmpty() }
            repository.saveWallpaper(mode, uri)
            repository.saveWallpaperOpacity(wallpaper.optInt("opacity", 60))
        }

        val groups = mutableListOf<AppGroup>()
        json.optJSONArray("groups")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                groups.add(
                    AppGroup(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        collapsed = item.optBoolean("collapsed", false),
                    ),
                )
            }
        }
        if (groups.isEmpty()) {
            groups.addAll(
                listOf(
                    AppGroup("favorites", "Favorites"),
                    AppGroup("default", "Default"),
                ),
            )
        }
        repository.saveGroups(groups)

        json.optJSONObject("assignments")?.let { assignments ->
            assignments.keys().forEach { key ->
                repository.setGroupOf(key, assignments.optString(key, "default"))
            }
        }
        json.optJSONObject("labels")?.let { labels ->
            labels.keys().forEach { key ->
                repository.setLabel(key, labels.optString(key))
            }
        }
    }

    private fun importMako(json: JSONObject) {
        val rawIds = mutableListOf<String>()
        json.optJSONArray("groups:ids")?.let { ids ->
            for (i in 0 until ids.length()) {
                ids.optString(i).takeIf { it.isNotEmpty() }?.let(rawIds::add)
            }
        }
        if (rawIds.isEmpty()) rawIds.addAll(listOf("0", "1"))

        val idMap = mutableMapOf<String, String>()
        val groups = mutableListOf<AppGroup>()
        rawIds
            .sortedBy { json.optInt("group:$it:order", Int.MAX_VALUE) }
            .distinct()
            .forEach { rawId ->
                val label = json.optString("group:$rawId:label", "")
                    .removePrefix("------")
                    .trim()
                    .ifEmpty { if (rawId == "1") "Favorites" else "Default" }
                val expanded = if (json.has("group:$rawId:expanded")) {
                    json.optBoolean("group:$rawId:expanded", true)
                } else {
                    true
                }
                val ourId = when (rawId) {
                    "0" -> "default"
                    "1" -> "favorites"
                    else -> "g$rawId"
                }
                idMap[rawId] = ourId
                groups.add(AppGroup(ourId, label, !expanded))
            }
        repository.saveGroups(groups)

        json.keys().forEach { key ->
            when {
                key.startsWith("app:") && key.endsWith(":group_id") -> {
                    val pkg = key
                        .removePrefix("app:")
                        .removeSuffix(":group_id")
                        .substringBefore(":profile_")
                    if (":shortcut_" in pkg) return@forEach
                    val rawGroup = json.optString(key, "0")
                    val ourGroup = idMap[rawGroup] ?: "default"
                    repository.setGroupOf(pkg, ourGroup)
                }

                key.startsWith("app:") && key.endsWith(":custom_label") -> {
                    val pkg = key
                        .removePrefix("app:")
                        .removeSuffix(":custom_label")
                        .substringBefore(":profile_")
                    if (":shortcut_" in pkg) return@forEach
                    val label = json.optString(key)
                    if (label.isNotEmpty()) repository.setLabel(pkg, label)
                }
            }
        }

        val themeName = json.optString("app:theme:name")
        val palette = MAKO_PALETTES[themeName]
        if (palette != null) {
            repository.saveTheme(
                ThemeSettings(
                    mode = ThemeMode.CUSTOM,
                    background = palette.background,
                    text = palette.text,
                    muted = palette.muted,
                    accent = palette.accent,
                ),
            )
        } else {
            fun color(key: String, fallback: String): String {
                if (!json.has(key)) return fallback
                val value = json.opt(key)
                val int = when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull() ?: return fallback
                    else -> return fallback
                }
                return String.format("#%06X", 0xFFFFFF and int)
            }
            repository.saveTheme(
                ThemeSettings(
                    mode = ThemeMode.CUSTOM,
                    background = color("app:theme:bg_1", ThemeSettings().background),
                    text = color("app:theme:foreground", ThemeSettings().text),
                    muted = color("app:theme:disabled", ThemeSettings().muted),
                    accent = color("app:theme:accent_1", ThemeSettings().accent),
                ),
            )
        }

        if (json.has("home:background_mode:screen_opacity_strength")) {
            val strength = json.optInt("home:background_mode:screen_opacity_strength", 9)
            repository.saveWallpaperOpacity((strength * 10).coerceIn(0, 100))
        }
    }

    private data class MakoPalette(
        val background: String,
        val text: String,
        val muted: String,
        val accent: String,
    )

    private companion object {
        val MAKO_PALETTES = mapOf(
            "mako" to MakoPalette("#141417", "#CCCCCC", "#888888", "#ABD68E"),
            "rama" to MakoPalette("#0E190E", "#CBDECD", "#888888", "#ABD68D"),
            "teyin" to MakoPalette("#0E181A", "#EAE6DF", "#5D7A7C", "#4DA8AC"),
            "catppuccin_mocha" to MakoPalette("#1E1E2E", "#CDD6F4", "#6C7086", "#A6E3A1"),
            "catppuccin_latte" to MakoPalette("#EFF1F5", "#4C4F69", "#9CA0B0", "#40A02B"),
            "dracula" to MakoPalette("#282A36", "#F8F8F2", "#6272A4", "#50FA7B"),
            "melange" to MakoPalette("#161413", "#ECE1D7", "#867462", "#78997A"),
            "tokyo_night" to MakoPalette("#1A1B26", "#C0CAF5", "#565F89", "#9ECE6A"),
            "mono_dark" to MakoPalette("#040100", "#FFFFF9", "#FAFEFF", "#FBFFFF"),
            "mono_light" to MakoPalette("#FFFFFF", "#000040", "#010001", "#000001"),
        )
    }
}
