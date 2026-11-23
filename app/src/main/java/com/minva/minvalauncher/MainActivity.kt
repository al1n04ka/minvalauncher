package com.minva.minvalauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.window.OnBackInvokedDispatcher
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.minva.minvalauncher.data.AppGroup
import com.minva.minvalauncher.data.AppInfo
import com.minva.minvalauncher.data.BASE_PALETTE
import com.minva.minvalauncher.data.ConfigManager
import com.minva.minvalauncher.data.LauncherRepository
import com.minva.minvalauncher.data.Palette
import com.minva.minvalauncher.data.ThemeMode
import com.minva.minvalauncher.data.ThemeSettings
import com.minva.minvalauncher.data.WallpaperMode
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class BatteryState(
    val level: Int = -1,
    val temperatureC: Float = -1f,
    val charging: Boolean = false,
)

private sealed class Row {
    data class Header(val group: AppGroup, val count: Int) : Row()
    data class AppRow(val app: AppInfo) : Row()
}

class MainActivity : Activity() {
    private lateinit var repository: LauncherRepository
    private lateinit var configManager: ConfigManager
    private lateinit var root: FrameLayout
    private lateinit var homeView: LinearLayout
    private lateinit var wallpaperView: ImageView
    private lateinit var wallpaperTint: View
    private lateinit var timeText: TextView
    private lateinit var infoText: TextView
    private lateinit var settingsButton: TextView
    private lateinit var searchButton: TextView
    private lateinit var listView: ListView
    private lateinit var searchEdit: EditText
    private lateinit var settingsPanel: ScrollView
    private lateinit var settingsContent: LinearLayout
    private lateinit var adapter: LauncherAdapter

    private var allApps: List<AppInfo> = emptyList()
    private var groups: MutableList<AppGroup> = mutableListOf()
    private var palette: Palette = BASE_PALETTE
    private var battery = BatteryState()

    companion object {
        private const val REQUEST_PICK_WALLPAPER = 101
        private const val REQUEST_EXPORT_CONFIG = 201
        private const val REQUEST_IMPORT_CONFIG = 202
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                    val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f
                    val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    battery = BatteryState(percent, temperature, charging)
                }

                Intent.ACTION_POWER_CONNECTED -> battery = battery.copy(charging = true)
                Intent.ACTION_POWER_DISCONNECTED -> battery = battery.copy(charging = false)
            }
            updateClock()
        }
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LauncherRepository(this)
        configManager = ConfigManager(this, repository)
        setContentView(R.layout.activity_home)

        root = findViewById(R.id.root)
        homeView = findViewById(R.id.home)
        wallpaperView = findViewById(R.id.wallpaper)
        wallpaperTint = findViewById(R.id.wallpaper_tint)
        timeText = findViewById(R.id.time)
        infoText = findViewById(R.id.info)
        settingsButton = findViewById(R.id.settings_button)
        searchButton = findViewById(R.id.search_button)
        listView = findViewById(R.id.app_list)
        searchEdit = findViewById(R.id.search)
        settingsPanel = findViewById(R.id.settings_panel)
        settingsContent = findViewById(R.id.settings_content)

        setupGestureNavigation()

        settingsButton.setOnClickListener { showSettings() }
        searchButton.setOnClickListener { toggleSearch() }
        timeText.setOnLongClickListener {
            showSettings()
            true
        }
        infoText.setOnLongClickListener {
            showSettings()
            true
        }

        val emptyArea = View(this).apply {
            layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360),
            )
            isLongClickable = true
            setOnLongClickListener {
                showSettings()
                true
            }
        }
        listView.addFooterView(emptyArea, null, false)

        adapter = LauncherAdapter()
        listView.adapter = adapter

        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = buildRows()
        })

        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(packageReceiver, packageFilter)
        }

        refresh()
        handler.post(ticker)
    }

    override fun onStart() {
        super.onStart()
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(batteryReceiver, batteryFilter)
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { sticky ->
            batteryReceiver.onReceive(this, sticky)
        }
    }

    override fun onStop() {
        unregisterReceiver(batteryReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.categories?.contains(Intent.CATEGORY_HOME) == true) {
            searchEdit.text.clear()
            searchEdit.visibility = View.GONE
            listView.setSelection(0)
            hideSettings()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQUEST_PICK_WALLPAPER -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (error: SecurityException) {
                    Toast.makeText(this, "Cannot keep this image", Toast.LENGTH_SHORT).show()
                }
                repository.saveWallpaper(WallpaperMode.IMAGE, uri.toString())
                applyTheme()
            }

            REQUEST_EXPORT_CONFIG -> {
                val ok = configManager.export(uri)
                Toast.makeText(
                    this,
                    if (ok) "Config exported" else "Export failed",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            REQUEST_IMPORT_CONFIG -> {
                val ok = configManager.import(uri)
                Toast.makeText(
                    this,
                    if (ok) "Config imported" else "Import failed",
                    Toast.LENGTH_SHORT,
                ).show()
                if (ok) refresh()
            }
        }
    }

    private fun setupGestureNavigation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }

        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val top: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                left = bars.left
                top = bars.top
                right = bars.right
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }

            homeView.setPadding(
                left + dp(20),
                top + dp(30),
                right + dp(20),
                bottom + dp(30),
            )
            settingsContent.setPadding(
                left + dp(20),
                top + dp(20),
                right + dp(20),
                bottom + dp(24),
            )
            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { handleBack() }
        }
    }

    private fun handleBack() {
        if (settingsPanel.visibility == View.VISIBLE) hideSettings()
    }

    @SuppressLint("GestureBackNavigation", "MissingSuperCall")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    private fun refresh() {
        allApps = repository.loadApps(packageManager, packageName)
        groups = repository.loadGroups()
        applyTheme()
    }

    private fun applyTheme() {
        palette = repository.palette()
        root.setBackgroundColor(palette.background)
        settingsPanel.setBackgroundColor(palette.background)
        timeText.setTextColor(palette.text)
        infoText.setTextColor(palette.muted)
        settingsButton.setTextColor(palette.accent)
        searchButton.setTextColor(palette.accent)
        searchEdit.setTextColor(palette.text)
        searchEdit.setHintTextColor(palette.muted)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        applyWallpaper()
        setupSettings()
        buildRows()
    }

    private fun applyWallpaper() {
        val mode = repository.wallpaperMode()
        val opacity = repository.wallpaperOpacity()
        val hasImage = when (mode) {
            WallpaperMode.NONE -> false
            WallpaperMode.IMAGE -> {
                val uriString = repository.wallpaperUri()
                val bitmap = uriString?.let { raw ->
                    try {
                        contentResolver.openInputStream(Uri.parse(raw))?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } catch (error: SecurityException) {
                        null
                    }
                }
                if (bitmap != null) {
                    wallpaperView.setImageBitmap(bitmap)
                    true
                } else {
                    repository.saveWallpaper(WallpaperMode.NONE)
                    Toast.makeText(this, "Wallpaper unavailable", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        wallpaperView.visibility = if (hasImage) View.VISIBLE else View.GONE
        wallpaperTint.visibility = if (hasImage) View.VISIBLE else View.GONE
        wallpaperView.alpha = opacity / 100f
        val tintAlpha = (255 * (100 - opacity) / 100f).toInt()
        wallpaperTint.setBackgroundColor(
            Color.argb(
                tintAlpha,
                Color.red(palette.background),
                Color.green(palette.background),
                Color.blue(palette.background),
            ),
        )
    }

    private fun updateClock() {
        val now = Date()
        val locale = Locale.getDefault()
        timeText.text = SimpleDateFormat("HH:mm", locale).format(now)
        val dayOfYear = Calendar.getInstance(locale).get(Calendar.DAY_OF_YEAR)
        val date = SimpleDateFormat("EEE d MMM", locale).format(now).uppercase(locale)

        infoText.text = buildString {
            append(date)
            append("  D").append(dayOfYear)
            append("  ")
            if (battery.level >= 0) append(battery.level).append('%') else append("--")
            if (battery.charging) append(" +")
            append(' ')
            if (battery.temperatureC > 0f) {
                append(String.format(locale, "%.1f°C", battery.temperatureC))
            } else {
                append("--")
            }
        }
    }

    private fun buildRows() {
        val needle = searchEdit.text.toString().trim().lowercase(Locale.getDefault())
        val rows = mutableListOf<Row>()

        for (group in groups) {
            val apps = allApps.filter { app ->
                val label = repository.labelOf(app.packageName) ?: app.label
                val matchesGroup = repository.groupOf(app.packageName) == group.id
                val matchesQuery = needle.isEmpty() ||
                    label.contains(needle, ignoreCase = true) ||
                    app.packageName.contains(needle, ignoreCase = true)
                matchesGroup && matchesQuery
            }
            if (apps.isEmpty() && needle.isNotEmpty()) continue

            rows.add(Row.Header(group, apps.size))
            if (!group.collapsed || needle.isNotEmpty()) {
                apps.forEach { rows.add(Row.AppRow(it)) }
            }
        }

        adapter.submit(rows)
    }

    private fun launchApp(app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "Cannot launch app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppActions(app: AppInfo) {
        val title = repository.labelOf(app.packageName) ?: app.label
        val options = arrayOf("MOVE TO GROUP", "RENAME", "NEW GROUP", "RESET NAME", "CANCEL")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showGroupPicker(app)
                    1 -> showTextInput("Rename app", title) { value ->
                        repository.setLabel(app.packageName, value)
                        refresh()
                    }

                    2 -> showTextInput("New group name", "") { value ->
                        val group = repository.addGroup(value)
                        repository.setGroupOf(app.packageName, group.id)
                        refresh()
                    }

                    3 -> {
                        repository.clearLabel(app.packageName)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun showGroupPicker(app: AppInfo) {
        val names = groups.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Move to group")
            .setItems(names) { _, which ->
                repository.setGroupOf(app.packageName, groups[which].id)
                refresh()
            }
            .show()
    }

    private fun showGroupActions(group: AppGroup) {
        val options = mutableListOf("RENAME", if (group.collapsed) "EXPAND" else "COLLAPSE")
        if (group.id != "favorites" && group.id != "default") options.add("DELETE")
        options.add("CANCEL")

        AlertDialog.Builder(this)
            .setTitle(group.name)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "RENAME" -> showTextInput("Rename group", group.name) { value ->
                        group.name = value
                        repository.saveGroups(groups)
                        refresh()
                    }

                    "COLLAPSE", "EXPAND" -> {
                        group.collapsed = !group.collapsed
                        repository.saveGroups(groups)
                        refresh()
                    }

                    "DELETE" -> AlertDialog.Builder(this)
                        .setTitle("Delete ${group.name}?")
                        .setMessage("Apps will move to Default.")
                        .setPositiveButton("DELETE") { _, _ ->
                            repository.deleteGroup(group.id)
                            refresh()
                        }
                        .setNegativeButton("CANCEL", null)
                        .show()
                }
            }
            .show()
    }

    private fun showTextInput(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            typeface = Typeface.MONOSPACE
            setTextColor(palette.text)
            setHintTextColor(palette.muted)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) onOk(value)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun setupSettings() {
        settingsContent.removeAllViews()
        var draft = repository.theme()

        val header = horizontalRow()
        header.addView(label("SETTINGS", 22f, palette.accent, weight = 1f))
        header.addView(action("X", palette.muted) { hideSettings() })
        settingsContent.addView(header)

        section("THEME")
        val themeRow = horizontalRow()
        themeRow.addView(action("BASE", if (draft.mode == ThemeMode.BASE) palette.accent else palette.muted) {
            draft = draft.copy(mode = ThemeMode.BASE)
            repository.saveTheme(draft)
            applyTheme()
        })
        themeRow.addView(spacer(12))
        themeRow.addView(action("CUSTOM", if (draft.mode == ThemeMode.CUSTOM) palette.accent else palette.muted) {
            draft = draft.copy(mode = ThemeMode.CUSTOM)
            repository.saveTheme(draft)
            applyTheme()
        })
        settingsContent.addView(themeRow)

        if (draft.mode == ThemeMode.CUSTOM) {
            settingsContent.addView(verticalSpace(16))
            settingsContent.addView(colorField("BG", draft.background) {
                draft = draft.copy(background = it)
                repository.saveTheme(draft)
            })
            settingsContent.addView(verticalSpace(8))
            settingsContent.addView(colorField("TEXT", draft.text) {
                draft = draft.copy(text = it)
                repository.saveTheme(draft)
            })
            settingsContent.addView(verticalSpace(8))
            settingsContent.addView(colorField("MUTED", draft.muted) {
                draft = draft.copy(muted = it)
                repository.saveTheme(draft)
            })
            settingsContent.addView(verticalSpace(8))
            settingsContent.addView(colorField("ACCENT", draft.accent) {
                draft = draft.copy(accent = it)
                repository.saveTheme(draft)
            })
            settingsContent.addView(verticalSpace(12))
            settingsContent.addView(action("RESET COLORS", palette.muted) {
                draft = ThemeSettings(mode = ThemeMode.CUSTOM)
                repository.saveTheme(draft)
                applyTheme()
            })
        }

        section("GROUPS")
        settingsContent.addView(action("+ ADD GROUP", palette.accent) {
            showTextInput("New group name", "") { value ->
                repository.addGroup(value)
                refresh()
            }
        })

        for (group in groups) {
            settingsContent.addView(verticalSpace(14))
            settingsContent.addView(
                label(
                    "${group.name}${if (group.collapsed) "  (collapsed)" else ""}",
                    15f,
                    palette.text,
                ),
            )
            val groupRow = horizontalRow()
            groupRow.addView(action("RENAME", palette.muted) {
                showTextInput("Rename group", group.name) { value ->
                    group.name = value
                    repository.saveGroups(groups)
                    refresh()
                }
            })
            groupRow.addView(spacer(12))
            groupRow.addView(action(if (group.collapsed) "EXPAND" else "COLLAPSE", palette.muted) {
                group.collapsed = !group.collapsed
                repository.saveGroups(groups)
                refresh()
            })
            if (group.id != "favorites" && group.id != "default") {
                groupRow.addView(spacer(12))
                groupRow.addView(action("DELETE", palette.muted) {
                    repository.deleteGroup(group.id)
                    refresh()
                })
            }
            settingsContent.addView(groupRow)
        }

        section("WALLPAPER")
        val wallpaperRow = horizontalRow()
        wallpaperRow.addView(action("PICK IMAGE", palette.accent) { pickWallpaper() })
        wallpaperRow.addView(spacer(12))
        wallpaperRow.addView(action("CLEAR", palette.muted) {
            repository.saveWallpaper(WallpaperMode.NONE)
            applyTheme()
        })
        settingsContent.addView(wallpaperRow)

        val opacity = repository.wallpaperOpacity()
        val opacityLabel = label("OPACITY $opacity%", 12f, palette.muted)
        settingsContent.addView(opacityLabel)
        val seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            max = 100
            progress = opacity
            progressTintList = ColorStateList.valueOf(palette.accent)
            thumbTintList = ColorStateList.valueOf(palette.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    opacityLabel.text = "OPACITY $progress%"
                    repository.saveWallpaperOpacity(progress)
                    applyWallpaper()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        settingsContent.addView(seekBar)

        section("CONFIG")
        val configRow = horizontalRow()
        configRow.addView(action("EXPORT", palette.accent) { exportConfig() })
        configRow.addView(spacer(12))
        configRow.addView(action("IMPORT", palette.muted) { importConfig() })
        settingsContent.addView(configRow)
        settingsContent.addView(verticalSpace(8))
        settingsContent.addView(label("MAKO JSON SUPPORTED", 11f, palette.muted))

        section("SYSTEM")
        settingsContent.addView(action("SET AS HOME", palette.accent) { openHomeSettings() })

        settingsContent.addView(verticalSpace(24))
        settingsContent.addView(label("LONG PRESS LIST TO OPEN SETTINGS", 11f, palette.muted))
    }

    private fun toggleSearch() {
        if (searchEdit.visibility == View.VISIBLE) {
            searchEdit.text.clear()
            searchEdit.visibility = View.GONE
            buildRows()
        } else {
            searchEdit.visibility = View.VISIBLE
            searchEdit.requestFocus()
            val input = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showSettings() {
        setupSettings()
        settingsPanel.visibility = View.VISIBLE
    }

    private fun hideSettings() {
        settingsPanel.visibility = View.GONE
        applyTheme()
    }

    private fun exportConfig() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "minvalauncher-config.json")
        }
        try {
            startActivityForResult(intent, REQUEST_EXPORT_CONFIG)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "No file manager available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importConfig() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        try {
            startActivityForResult(intent, REQUEST_IMPORT_CONFIG)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "No file manager available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_WALLPAPER)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "No image picker available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openHomeSettings() {
        val intent = Intent(android.provider.Settings.ACTION_HOME_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "Home settings unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    private fun section(title: String): View {
        val view = label(title, 12f, palette.muted)
        (view.layoutParams as LinearLayout.LayoutParams).topMargin = dp(28)
        settingsContent.addView(view)
        return view
    }

    private fun label(text: String, size: Float, color: Int, weight: Float = 0f): TextView {
        val params = if (weight > 0f) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return TextView(this).apply {
            layoutParams = params
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.MONOSPACE
        }
    }

    private fun action(text: String, color: Int, onClick: () -> Unit): TextView =
        label(text, 13f, color).apply {
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun spacer(width: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(width, 1)
    }

    private fun verticalSpace(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height))
    }

    private fun horizontalRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun colorField(name: String, value: String, onChange: (String) -> Unit): View {
        val row = horizontalRow()
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            setBackgroundColor(com.minva.minvalauncher.data.parseColor(value, palette.background))
        }
        val caption = label(name, 12f, palette.muted)
        (caption.layoutParams as LinearLayout.LayoutParams).width = dp(60)
        val input = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setText(value)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(palette.text)
            background = null
            maxLines = 1
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val next = s?.toString().orEmpty()
                    if (next.length <= 9) {
                        preview.setBackgroundColor(
                            com.minva.minvalauncher.data.parseColor(next, palette.background),
                        )
                        onChange(next)
                    }
                }
            })
        }
        row.addView(preview)
        row.addView(spacer(10))
        row.addView(caption)
        row.addView(input)
        return row
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class LauncherAdapter : BaseAdapter() {
        private val rows = mutableListOf<Row>()

        fun submit(newRows: List<Row>) {
            rows.clear()
            rows.addAll(newRows)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                layoutParams = AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                typeface = Typeface.MONOSPACE
            }

            when (val row = rows[position]) {
                is Row.Header -> {
                    view.text = buildString {
                        append(if (row.group.collapsed) "▸ " else "▾ ")
                        append(row.group.name.uppercase(Locale.getDefault()))
                        append("  ").append(row.count)
                    }
                    view.textSize = 14f
                    view.setTextColor(palette.accent)
                    view.setPadding(0, dp(20), 0, dp(8))
                    view.setOnClickListener {
                        row.group.collapsed = !row.group.collapsed
                        repository.saveGroups(groups)
                        buildRows()
                    }
                    view.setOnLongClickListener {
                        showGroupActions(row.group)
                        true
                    }
                }

                is Row.AppRow -> {
                    view.text = (repository.labelOf(row.app.packageName) ?: row.app.label)
                        .uppercase(Locale.getDefault())
                    view.textSize = 18f
                    view.setTextColor(palette.text)
                    view.setPadding(dp(20), dp(9), 0, dp(9))
                    view.setOnClickListener { launchApp(row.app) }
                    view.setOnLongClickListener {
                        showAppActions(row.app)
                        true
                    }
                }
            }
            return view
        }
    }
}
