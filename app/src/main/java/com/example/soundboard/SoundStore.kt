package com.example.soundboard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

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
                fadeOut = o.optBoolean("fadeOut", true)
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
            )
        }
        prefs.edit().putString(KEY_SOUNDS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "soundboard"
        private const val KEY_SOUNDS = "sounds"
    }
}
