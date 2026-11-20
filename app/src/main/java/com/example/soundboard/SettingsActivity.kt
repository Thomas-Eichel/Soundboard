package com.example.soundboard

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.soundboard.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: SoundStore
    private lateinit var settings: AppSettings
    private val sounds = mutableListOf<Sound>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        store = SoundStore(this)
        settings = store.loadSettings()
        sounds.addAll(store.load())

        binding.addCategoryButton.setOnClickListener { showAddCategoryDialog() }
        rebuildCategoryList()
    }

    override fun onPause() {
        super.onPause()
        store.saveSettings(settings)
        store.save(sounds)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun rebuildCategoryList() {
        binding.categoriesContainer.removeAllViews()
        if (settings.categories.isEmpty()) {
            val tv = TextView(this)
            tv.text = getString(R.string.no_categories_yet)
            binding.categoriesContainer.addView(tv)
            return
        }
        settings.categories.forEach { cat ->
            val row = TextView(this)
            row.text = cat
            row.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge
            )
            row.setPadding(0, 32, 0, 32)
            row.setOnClickListener { showEditCategoryDialog(cat) }
            binding.categoriesContainer.addView(row)
        }
    }

    private fun showAddCategoryDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.category_name_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_category)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                if (name in settings.categories) {
                    Toast.makeText(this, R.string.category_exists, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                settings.categories.add(name)
                store.saveSettings(settings)
                rebuildCategoryList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditCategoryDialog(oldName: String) {
        val input = EditText(this)
        input.setText(oldName)
        AlertDialog.Builder(this)
            .setTitle(oldName)
            .setView(input)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == oldName) return@setPositiveButton
                if (newName in settings.categories) {
                    Toast.makeText(this, R.string.category_exists, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val idx = settings.categories.indexOf(oldName)
                if (idx >= 0) settings.categories[idx] = newName
                sounds.forEachIndexed { i, s ->
                    if (s.category1 == oldName || s.category2 == oldName) {
                        sounds[i] = s.copy(
                            category1 = if (s.category1 == oldName) newName else s.category1,
                            category2 = if (s.category2 == oldName) newName else s.category2
                        )
                    }
                }
                store.saveSettings(settings)
                store.save(sounds)
                rebuildCategoryList()
            }
            .setNeutralButton(R.string.delete) { _, _ ->
                settings.categories.remove(oldName)
                sounds.forEachIndexed { i, s ->
                    if (s.category1 == oldName || s.category2 == oldName) {
                        sounds[i] = s.copy(
                            category1 = if (s.category1 == oldName) null else s.category1,
                            category2 = if (s.category2 == oldName) null else s.category2
                        )
                    }
                }
                store.saveSettings(settings)
                store.save(sounds)
                rebuildCategoryList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
