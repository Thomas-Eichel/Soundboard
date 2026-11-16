package com.example.soundboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
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
    private lateinit var player: SoundPlayer
    private val sounds = mutableListOf<Sound>()
    private lateinit var adapter: SoundAdapter

    private var pickedUri: Uri? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        store = SoundStore(this)
        player = SoundPlayer(this)

        sounds.addAll(store.load())
        while (sounds.size < GRID_SIZE) {
            sounds.add(Sound(System.currentTimeMillis() + sounds.size, "", null, 0.0, true))
        }

        adapter = SoundAdapter(
            sounds,
            onClick = { pos ->
                if (pos < 0 || pos >= sounds.size) return@SoundAdapter
                val s = sounds[pos]
                if (s.uri != null) player.play(s) else showEditDialog(pos)
            },
            onLongClick = { pos ->
                if (pos >= 0 && pos < sounds.size) showEditDialog(pos)
            }
        )
        binding.recyclerView.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        binding.recyclerView.adapter = adapter
        updateEmptyView()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_stop_all) {
            player.stop()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        player.stop()
        super.onDestroy()
    }

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

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (current.uri == null) R.string.add_sound else R.string.edit_sound)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .apply {
                if (current.uri != null) {
                    setNeutralButton(R.string.delete) { _, _ ->
                        sounds[slot] =
                            Sound(System.currentTimeMillis() + slot, "", null, 0.0, true)
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
                    fadeOut = dialogBinding.fadeSwitch.isChecked
                )
                persist()
                adapter.notifyItemChanged(slot)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener { editDialog = null }
        dialog.show()
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
        private const val GRID_SIZE = 12
        private const val GRID_COLUMNS = 3
    }
}
