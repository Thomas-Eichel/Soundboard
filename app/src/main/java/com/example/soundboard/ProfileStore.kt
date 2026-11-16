package com.example.soundboard

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

data class ProfileResult(
    val settings: AppSettings,
    val sounds: List<Sound>
)

class ProfileStore(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun export(uri: Uri, settings: AppSettings, sounds: List<Sound>, onDone: (Boolean) -> Unit) {
        thread {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                        sounds.forEachIndexed { slot, s ->
                            if (s.uri != null) {
                                zip.putNextEntry(ZipEntry("audio/$slot.mp3"))
                                context.contentResolver.openInputStream(s.uri)?.use { input ->
                                    input.copyTo(zip)
                                }
                                zip.closeEntry()
                            }
                        }
                        val root = JSONObject()
                        root.put("version", 1)
                        root.put(
                            "settings",
                            JSONObject()
                                .put("fadeMs", settings.fadeMs)
                                .put("categories", JSONArray(settings.categories))
                        )
                        val arr = JSONArray()
                        sounds.forEachIndexed { slot, s ->
                            arr.put(
                                JSONObject()
                                    .put("slot", slot)
                                    .put("id", s.id)
                                    .put("name", s.name)
                                    .put("durationSec", s.durationSec)
                                    .put("fadeOut", s.fadeOut)
                                    .put("category1", s.category1 ?: "")
                                    .put("category2", s.category2 ?: "")
                                    .put(
                                        "audioEntry",
                                        if (s.uri != null) "audio/$slot.mp3" else JSONObject.NULL
                                    )
                            )
                        }
                        root.put("sounds", arr)
                        zip.putNextEntry(ZipEntry("profile.json"))
                        zip.write(root.toString().toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
            mainHandler.post { onDone(ok) }
        }
    }

    fun import(uri: Uri, onResult: (ProfileResult?) -> Unit) {
        thread {
            val result = try {
                val dir = File(context.filesDir, "profile_sounds")
                dir.deleteRecursively()
                dir.mkdirs()
                var root: JSONObject? = null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            when {
                                entry.name == "profile.json" ->
                                    root = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                                entry.name.startsWith("audio/") -> {
                                    val f = File(dir, entry.name.substringAfter('/'))
                                    f.outputStream().use { zip.copyTo(it) }
                                }
                            }
                            entry = zip.nextEntry
                        }
                    }
                }
                val json = root ?: error("profile.json fehlt")
                val settingsObj = json.getJSONObject("settings")
                val categories = mutableListOf<String>()
                settingsObj.optJSONArray("categories")?.let { arr ->
                    for (i in 0 until arr.length()) categories.add(arr.getString(i))
                }
                val settings = AppSettings(
                    fadeMs = settingsObj.optLong("fadeMs", SoundStore.DEFAULT_FADE_MS),
                    categories = categories
                )
                val slots = MutableList(SoundStore.GRID_SIZE) {
                    Sound(System.currentTimeMillis() + it, "", null, 0.0, true, null, null)
                }
                val arr = json.getJSONArray("sounds")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val slot = o.optInt("slot", -1)
                    if (slot < 0 || slot >= slots.size) continue
                    val audioEntry = o.optString("audioEntry")
                    val file =
                        if (audioEntry.isNotEmpty()) File(dir, audioEntry.substringAfter('/')) else null
                    slots[slot] = Sound(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        uri = if (file != null && file.exists()) Uri.fromFile(file) else null,
                        durationSec = o.optDouble("durationSec", 0.0),
                        fadeOut = o.optBoolean("fadeOut", true),
                        category1 = o.optString("category1").ifEmpty { null },
                        category2 = o.optString("category2").ifEmpty { null }
                    )
                }
                ProfileResult(settings, slots)
            } catch (_: Exception) {
                null
            }
            mainHandler.post { onResult(result) }
        }
    }
}
