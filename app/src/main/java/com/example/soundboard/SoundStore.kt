package com.example.soundboard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val categories: MutableList<String> = mutableListOf(),
    var reorderLocked: Boolean = false
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
                category1 = o.optNullableString("category1"),
                category2 = o.optNullableString("category2"),
                backgroundColor = o.optNullableString("backgroundColor"),
                imageUri = o.optNullableString("imageUri")?.let { Uri.parse(it) },
                startSec = o.optDouble("startSec", 0.0),
                endSec = o.optDouble("endSec", 0.0),
                fadeInSec = o.optDouble("fadeInSec", 0.0),
                fadeOutSec = o.optDouble("fadeOutSec", 0.0),
                isHeader = o.optBoolean("isHeader", false)
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
                    .put("category1", s.category1 ?: "")
                    .put("category2", s.category2 ?: "")
                    .put("backgroundColor", s.backgroundColor ?: "")
                    .put("imageUri", s.imageUri?.toString() ?: "")
                    .put("startSec", s.startSec)
                    .put("endSec", s.endSec)
                    .put("fadeInSec", s.fadeInSec)
                    .put("fadeOutSec", s.fadeOutSec)
                    .put("isHeader", s.isHeader)
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
            categories = categories,
            reorderLocked = o.optBoolean("reorderLocked", false)
        )
    }

    fun saveSettings(settings: AppSettings) {
        val o = JSONObject()
            .put("categories", JSONArray(settings.categories))
            .put("reorderLocked", settings.reorderLocked)
        prefs.edit().putString(KEY_SETTINGS, o.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "soundboard"
        private const val KEY_SOUNDS = "sounds"
        private const val KEY_SETTINGS = "settings"
    }
}
