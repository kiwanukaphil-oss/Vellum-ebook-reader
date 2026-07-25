package app.vellum.reader.shared

import android.content.Context

/**
 * Small per-account preference store for catalogue presentation. These values
 * are device-only and never alter the shared household catalogue.
 */
class SharedCataloguePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun readView(userId: String): SharedCatalogueView =
        enumValueOrDefault(preferences.getString("$userId:view", null), SharedCatalogueView.GRID)

    fun readSort(userId: String): SharedCatalogueSort =
        enumValueOrDefault(preferences.getString("$userId:sort", null), SharedCatalogueSort.RECENT)

    fun writeView(userId: String, view: SharedCatalogueView) {
        preferences.edit().putString("$userId:view", view.name).apply()
    }

    fun writeSort(userId: String, sort: SharedCatalogueSort) {
        preferences.edit().putString("$userId:sort", sort.name).apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private companion object {
        const val PREFERENCES_NAME = "shared_catalogue_preferences"
    }
}
