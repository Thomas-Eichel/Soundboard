package com.example.soundboard

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
                val tmp = File(context.filesDir, "export_tmp.zip")
                tmp.delete()
                ZipOutputStream(BufferedOutputStream(tmp.outputStream())).use { zip ->
                    val arr = JSONArray()
                    sounds.forEachIndexed { slot, s ->
                        var audioEntry: Any = JSONObject.NULL
                        if (s.uri != null) {
                            val input = openInput(s.uri)
                            if (input != null) {
                                zip.putNextEntry(ZipEntry("audio/$slot.mp3"))
                                if (copyToZip(input, zip)) audioEntry = "audio/$slot.mp3"
                                zip.closeEntry()
                            }
                        }
                        var imageEntry: Any = JSONObject.NULL
                        if (s.imageUri != null) {
                            val input = openInput(s.imageUri)
                            if (input != null) {
                                zip.putNextEntry(ZipEntry("image/$slot.bin"))
                                if (copyToZip(input, zip)) imageEntry = "image/$slot.bin"
                                zip.closeEntry()
                            }
                        }
                        arr.put(
                            JSONObject()
                                .put("slot", slot)
                                .put("id", s.id)
                                .put("name", s.name)
                                .put("durationSec", s.durationSec)
                                .put("fadeOut", s.fadeOut)
                                .put("category1", s.category1 ?: "")
                                .put("category2", s.category2 ?: "")
                                .put("backgroundColor", s.backgroundColor ?: JSONObject.NULL)
                                .put("audioEntry", audioEntry)
                                .put("imageEntry", imageEntry)
                        )
                    }
                    val root = JSONObject()
                    root.put("version", 1)
                    root.put(
                        "settings",
                        JSONObject()
                            .put("fadeMs", settings.fadeMs)
                            .put("categories", JSONArray(settings.categories))
                    )
                    root.put("sounds", arr)
                    zip.putNextEntry(ZipEntry("profile.json"))
                    zip.write(root.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                if (tmp.length() <= 0) {
                    Log.e(TAG, "export: temp zip is empty")
                    false
                } else {
                    val out = context.contentResolver.openOutputStream(uri)
                    if (out == null) {
                        Log.e(TAG, "export: openOutputStream returned null for $uri")
                        false
                    } else {
                        out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                        tmp.delete()
                        true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "export failed", e)
                false
            }
            mainHandler.post { onDone(ok) }
        }
    }

    fun import(uri: Uri, onResult: (ProfileResult?, String?) -> Unit) {
        thread {
            var result: ProfileResult? = null
            var error: String? = null
            try {
                val dir = File(context.filesDir, "profile_sounds")
                val imageDir = File(context.filesDir, "profile_images")
                dir.deleteRecursively()
                imageDir.deleteRecursively()
                dir.mkdirs()
                imageDir.mkdirs()
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
                                entry.name.startsWith("image/") -> {
                                    val f = File(imageDir, entry.name.substringAfter('/'))
                                    f.outputStream().use { zip.copyTo(it) }
                                }
                            }
                            entry = zip.nextEntry
                        }
                    }
                }
                val json = root ?: error("profile.json fehlt oder ZIP ist keine Profildatei")
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
                    val audioEntry = o.optNullableString("audioEntry")
                    val file =
                        if (!audioEntry.isNullOrEmpty()) File(dir, audioEntry.substringAfter('/')) else null
                    val imageEntry = o.optNullableString("imageEntry")
                    val imageFile =
                        if (!imageEntry.isNullOrEmpty()) File(imageDir, imageEntry.substringAfter('/')) else null
                    slots[slot] = Sound(
                        id = o.getLong("id"),
                        name = o.getString("name"),
                        uri = if (file != null && file.exists() && file.length() > 0) {
                            Uri.fromFile(file)
                        } else null,
                        durationSec = o.optDouble("durationSec", 0.0),
                        fadeOut = o.optBoolean("fadeOut", true),
                        category1 = o.optNullableString("category1"),
                        category2 = o.optNullableString("category2"),
                        backgroundColor = o.optNullableString("backgroundColor"),
                        imageUri = if (imageFile != null && imageFile.exists() && imageFile.length() > 0) {
                            Uri.fromFile(imageFile)
                        } else null
                    )
                }
                result = ProfileResult(settings, slots)
            } catch (e: Exception) {
                Log.e(TAG, "import failed", e)
                error = e.message ?: e.javaClass.simpleName
            }
            mainHandler.post { onResult(result, error) }
        }
    }

    private fun openInput(uri: Uri) = try {
        context.contentResolver.openInputStream(uri)
    } catch (e: Exception) {
        Log.e(TAG, "openInput failed for $uri", e)
        null
    }

    private fun copyToZip(input: java.io.InputStream, zip: ZipOutputStream): Boolean {
        return try {
            input.use { it.copyTo(zip) > 0 }
        } catch (e: Exception) {
            Log.e(TAG, "copyToZip failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "ProfileStore"
    }
}
