package com.example.soundboard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    var fadeMs: Long = SoundStore.DEFAULT_FADE_MS,
    val categories: MutableList<String> = mutableListOf()
)

class SoundStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<Sound> {
        val json = prefs.getString(KEY_SOUNDS, null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val uriString = o.optString("uri")
            Sound(
                id = o.getLong("id"),
                name = o.getString("name"),
                uri = if (uriString.isNotEmpty()) Uri.parse(uriString) else null,
                durationSec = o.optDouble("durationSec", 0.0),
                fadeOut = o.optBoolean("fadeOut", true),
                category1 = o.optString("category1").ifEmpty { null },
                category2 = o.optString("category2").ifEmpty { null }
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
