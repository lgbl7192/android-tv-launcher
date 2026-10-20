package me.prince.launcher

import android.content.Context
import android.content.SharedPreferences

private const val KEY_APPEARANCE = "appearance"
private const val KEY_WALLPAPER = "wallpaper"
private const val KEY_CATEGORIES = "wallpaper_categories"
private const val KEY_INTERVAL = "wallpaper_interval"
private const val MINUTE_MS = 60_000L

enum class Appearance(val label: String) { Auto("Auto"), Light("Light"), Dark("Dark") }

enum class WallpaperStyle(val label: String) { Photos("Photos"), Plain("Plain colour") }

enum class ChangeInterval(val label: String, val millis: Long) {
    FiveMinutes("5 min", 5 * MINUTE_MS),
    FifteenMinutes("15 min", 15 * MINUTE_MS),
    Hourly("1 hour", 60 * MINUTE_MS),
    Daily("Daily", 24 * 60 * MINUTE_MS),
}

data class HomePrefs(
    val appearance: Appearance = Appearance.Auto,
    val wallpaper: WallpaperStyle = WallpaperStyle.Photos,
    val categories: Set<WallpaperCategory> = WallpaperCategory.entries.toSet(),
    val interval: ChangeInterval = ChangeInterval.FifteenMinutes,
) {
    fun toggle(category: WallpaperCategory): HomePrefs {
        val toggled = if (category in categories) categories - category else categories + category
        return if (toggled.isEmpty()) this else copy(categories = toggled)
    }
}

fun loadHomePrefs(context: Context): HomePrefs {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val defaults = HomePrefs()
    val categories = prefs.getString(KEY_CATEGORIES, null)
        ?.split(',')
        ?.mapNotNull { name -> WallpaperCategory.entries.find { it.name == name } }
        ?.toSet()
    return HomePrefs(
        appearance = prefs.enum(KEY_APPEARANCE, defaults.appearance),
        wallpaper = prefs.enum(KEY_WALLPAPER, defaults.wallpaper),
        categories = categories?.takeIf { it.isNotEmpty() } ?: defaults.categories,
        interval = prefs.enum(KEY_INTERVAL, defaults.interval),
    )
}

fun saveHomePrefs(context: Context, prefs: HomePrefs) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_APPEARANCE, prefs.appearance.name)
        .putString(KEY_WALLPAPER, prefs.wallpaper.name)
        .putString(KEY_CATEGORIES, prefs.categories.joinToString(",") { it.name })
        .putString(KEY_INTERVAL, prefs.interval.name)
        .apply()
}

private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, default: T): T =
    getString(key, null)?.let { name -> enumValues<T>().find { it.name == name } } ?: default
