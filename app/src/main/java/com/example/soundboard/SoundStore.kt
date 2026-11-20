package com.example.soundboard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    var fadeMs: Long = SoundStore.DEFAULT_FADE_MS,
    val categories: MutableList<String> = mutableListOf()
)

// Android's JSONObject.optString returns the literal string "null" for JSON null values,
// so nullable string fields must be read via isNull() first.
internal fun JSONObject.optNullableString(key: String): String? {
    if (isNull(key)) return null
    val v = optString(key)
    return if (v.isEmpty() || v == "null") null else v
}

class SoundStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<Sound> {
        val json = prefs.getString(KEY_SOUNDS, null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Sound(
                id = o.getLong("id"),
                name = o.getString("name"),
                uri = o.optNullableString("uri")?.let { Uri.parse(it) },
                durationSec = o.optDouble("durationSec", 0.0),
                fadeOut = o.optBoolean("fadeOut", true),
                category1 = o.optNullableString("category1"),
                category2 = o.optNullableString("category2"),
                backgroundColor = o.optNullableString("backgroundColor"),
                imageUri = o.optNullableString("imageUri")?.let { Uri.parse(it) }
            )
        }
    }

    fun save(sounds: List<Sound>) {
        val arr = JSONArray()
        sounds.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("uri", s.uri?.toString() ?: "")
                    .put("durationSec", s.durationSec)
                    .put("fadeOut", s.fadeOut)
                    .put("category1", s.category1 ?: "")
                    .put("category2", s.category2 ?: "")
                    .put("backgroundColor", s.backgroundColor ?: "")
                    .put("imageUri", s.imageUri?.toString() ?: "")
            )
        }
        prefs.edit().putString(KEY_SOUNDS, arr.toString()).apply()
    }

    fun loadSettings(): AppSettings {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return AppSettings()
        val o = JSONObject(json)
        val categories = mutableListOf<String>()
        o.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) categories.add(arr.getString(i))
        }
        return AppSettings(
            fadeMs = o.optLong("fadeMs", DEFAULT_FADE_MS),
            categories = categories
        )
    }

    fun saveSettings(settings: AppSettings) {
        val o = JSONObject()
            .put("fadeMs", settings.fadeMs)
            .put("categories", JSONArray(settings.categories))
        prefs.edit().putString(KEY_SETTINGS, o.toString()).apply()
    }

    companion object {
        const val GRID_SIZE = 12
        const val DEFAULT_FADE_MS = 500L
        private const val PREFS_NAME = "soundboard"
        private const val KEY_SOUNDS = "sounds"
        private const val KEY_SETTINGS = "settings"
    }
}
