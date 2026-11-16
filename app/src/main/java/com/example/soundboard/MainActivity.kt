package com.example.soundboard

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import com.example.soundboard.databinding.ActivityMainBinding
import com.example.soundboard.databinding.DialogEditSoundBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SoundStore
    private lateinit var profileStore: ProfileStore
    private lateinit var player: SoundPlayer
    private val sounds = mutableListOf<Sound>()
    private lateinit var settings: AppSettings
    private lateinit var adapter: SoundAdapter

    private var pickedUri: Uri? = null
    private var dialogColor: String? = null
    private var dialogImageUri: Uri? = null
    private var editDialog: DialogEditSoundBinding? = null

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            pickedUri = uri
            editDialog?.pickedFile?.text = displayName(uri)
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            dialogImageUri = uri
            editDialog?.pickedImage?.text = displayName(uri)
        }

    private val exportProfile =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) return@registerForActivityResult
            profileStore.export(
                uri,
                settings.copy(categories = settings.categories.toMutableList()),
                sounds.toList()
            ) { ok ->
                Toast.makeText(
                    this,
                    if (ok) R.string.profile_saved else R.string.profile_save_error,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val importProfile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            profileStore.import(uri) { result, error ->
                if (result == null) {
                    val msg = if (error != null) {
                        getString(R.string.profile_load_error_detail, error)
                    } else {
                        getString(R.string.profile_load_error)
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                } else {
                    settings = result.settings
                    store.saveSettings(settings)
                    player.fadeMs = settings.fadeMs
                    sounds.clear()
                    sounds.addAll(result.sounds)
                    store.save(sounds)
                    adapter.playingId = null
                    binding.stopButton.isEnabled = false
                    adapter.notifyDataSetChanged()
                    updateEmptyView()
                    Toast.makeText(this, R.string.profile_loaded, Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        store = SoundStore(this)
        profileStore = ProfileStore(this)
        player = SoundPlayer(this)

        settings = store.loadSettings()
        player.fadeMs = settings.fadeMs

        sounds.addAll(store.load())
        while (sounds.size < SoundStore.GRID_SIZE) {
            sounds.add(newEmptySound(sounds.size))
        }

        adapter = SoundAdapter(
            sounds,
            onClick = { pos ->
                if (pos < 0 || pos >= sounds.size) return@SoundAdapter
                val s = sounds[pos]
                if (s.uri != null) {
                    player.play(s)
                    adapter.playingId = s.id
                    binding.stopButton.isEnabled = true
                } else showEditDialog(pos)
            },
            onLongClick = { pos ->
                if (pos >= 0 && pos < sounds.size) showEditDialog(pos)
            }
        )
        player.onStopped = {
            adapter.playingId = null
            binding.stopButton.isEnabled = false
        }
        binding.stopButton.setOnClickListener { player.stopWithQuickFade() }

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.recyclerView.adapter = adapter
        updateEmptyView()
    }

    override fun onResume() {
        super.onResume()
        settings = store.loadSettings()
        player.fadeMs = settings.fadeMs
        sounds.clear()
        sounds.addAll(store.load())
        while (sounds.size < SoundStore.GRID_SIZE) {
            sounds.add(newEmptySound(sounds.size))
        }
        binding.stopButton.isEnabled = false
        adapter.notifyDataSetChanged()
        updateEmptyView()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_stop_all -> {
                player.stop()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_export_profile -> {
                exportProfile.launch("soundboard-profil.zip")
                true
            }
            R.id.action_import_profile -> {
                importProfile.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream"
                    )
                )
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private fun newEmptySound(slot: Int) =
        Sound(System.currentTimeMillis() + slot, "", null, 0.0, true, null, null)

    private fun showEditDialog(slot: Int) {
        val current = sounds[slot]
        pickedUri = null
        val dialogBinding = DialogEditSoundBinding.inflate(layoutInflater)
        editDialog = dialogBinding
        dialogBinding.nameInput.setText(current.name)
        dialogBinding.durationInput.setText(
            if (current.durationSec > 0) current.durationSec.toString() else ""
        )
        dialogBinding.fadeSwitch.isChecked = current.fadeOut
        dialogBinding.pickedFile.text =
            current.uri?.let { displayName(it) } ?: getString(R.string.no_sound_picked)

        val categoryOptions = listOf(getString(R.string.no_category)) + settings.categories
        setupCategorySpinner(dialogBinding.category1Spinner, categoryOptions, current.category1)
        setupCategorySpinner(dialogBinding.category2Spinner, categoryOptions, current.category2)

        dialogColor = current.backgroundColor
        dialogImageUri = current.imageUri
        dialogBinding.pickedImage.text =
            current.imageUri?.let { displayName(it) } ?: getString(R.string.no_image_picked)
        rebuildColorRow(dialogBinding)
        dialogBinding.pickImageButton.setOnClickListener {
            pickImage.launch(arrayOf("image/*"))
        }
        dialogBinding.clearImageButton.setOnClickListener {
            dialogImageUri = null
            dialogBinding.pickedImage.text = getString(R.string.no_image_picked)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (current.uri == null) R.string.add_sound else R.string.edit_sound)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .apply {
                if (current.uri != null) {
                    setNeutralButton(R.string.delete) { _, _ ->
                        sounds[slot] = newEmptySound(slot)
                        persist()
                        adapter.notifyItemChanged(slot)
                    }
                }
            }
            .create()

        dialogBinding.pickButton.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val uri = pickedUri ?: current.uri
                if (uri == null) {
                    Toast.makeText(this, R.string.pick_sound_first, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val name = dialogBinding.nameInput.text.toString().trim()
                    .ifEmpty { displayName(uri) }
                val duration = dialogBinding.durationInput.text.toString()
                    .replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
                sounds[slot] = Sound(
                    id = current.id,
                    name = name,
                    uri = uri,
                    durationSec = duration,
                    fadeOut = dialogBinding.fadeSwitch.isChecked,
                    category1 = selectedCategory(dialogBinding.category1Spinner, categoryOptions),
                    category2 = selectedCategory(dialogBinding.category2Spinner, categoryOptions),
                    backgroundColor = dialogColor,
                    imageUri = dialogImageUri
                )
                persist()
                adapter.notifyItemChanged(slot)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { editDialog = null }
        dialog.show()
    }

    private fun rebuildColorRow(dialogBinding: DialogEditSoundBinding) {
        dialogBinding.colorRow.removeAllViews()
        val density = resources.displayMetrics.density
        (listOf<String?>(null) + PRESET_COLORS).forEach { hex ->
            val swatch = View(this).apply {
                val size = (44 * density).toInt()
                val margin = (10 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = margin }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (hex == null) Color.TRANSPARENT else Color.parseColor(hex))
                    setStroke(
                        (3 * density).toInt(),
                        if (hex == dialogColor) Color.BLACK else Color.LTGRAY
                    )
                }
                setOnClickListener {
                    dialogColor = hex
                    rebuildColorRow(dialogBinding)
                }
            }
            dialogBinding.colorRow.addView(swatch)
        }
    }

    private fun setupCategorySpinner(spinner: Spinner, options: List<String>, current: String?) {
        spinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val idx = current?.let { options.indexOf(it) } ?: -1
        spinner.setSelection(if (idx >= 0) idx else 0)
    }

    private fun selectedCategory(spinner: Spinner, options: List<String>): String? {
        val pos = spinner.selectedItemPosition
        return if (pos <= 0) null else options[pos]
    }

    private fun persist() {
        store.save(sounds)
        updateEmptyView()
    }

    private fun updateEmptyView() {
        binding.emptyView.isVisible = sounds.all { it.uri == null }
    }

    private fun displayName(uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "Sound"
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) ?: fallback else fallback }
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    companion object {
        private const val GRID_COLUMNS = 3
        private val PRESET_COLORS = listOf(
            "#F44336", "#FF9800", "#FFEB3B", "#4CAF50", "#009688",
            "#2196F3", "#673AB7", "#E91E63", "#795548", "#607D8B"
        )
    }
}
