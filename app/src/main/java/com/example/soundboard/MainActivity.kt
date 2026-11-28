package com.example.soundboard

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.soundboard.databinding.ActivityMainBinding
import com.example.soundboard.databinding.DialogEditSoundBinding
import com.google.android.material.textfield.MaterialAutoCompleteTextView

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SoundStore
    private lateinit var profileStore: ProfileStore
    private lateinit var player: SoundPlayer
    private val sounds = mutableListOf<Sound>()
    private lateinit var settings: AppSettings
    private lateinit var adapter: SoundAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var gestureStartColumns = GRID_COLUMNS
    private var gestureStartSpan = 0f
    private val durationExecutor = Executors.newSingleThreadExecutor()

    private var pickedUri: Uri? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var pendingMode = MODE_MIC
    private var serviceRecording = false
    private var dialogColor: String? = null
    private var dialogImageUri: Uri? = null
    private var editDialog: DialogEditSoundBinding? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val label = displayName(uri)
            copyPickedToPrivate(uri, "audio") { local ->
                if (local != null) {
                    pickedUri = local
                    editDialog?.pickedFile?.text = label
                    editDialog?.let { setupTrimControls(it, local, 0.0, 0.0, 0.0, 0.0) }
                } else {
                    Toast.makeText(this, R.string.copy_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val label = displayName(uri)
            copyPickedToPrivate(uri, "image") { local ->
                if (local != null) {
                    dialogImageUri = local
                    editDialog?.pickedImage?.text = label
                } else {
                    Toast.makeText(this, R.string.copy_failed, Toast.LENGTH_LONG).show()
                }
            }
        }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val micGranted = results[Manifest.permission.RECORD_AUDIO] == true
            when (pendingMode) {
                MODE_MIC -> {
                    if (micGranted) startRecording(MediaRecorder.AudioSource.MIC) else {
                        Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
                    }
                }
                MODE_INTERNAL -> {
                    if (Build.VERSION.SDK_INT < 29) {
                        if (micGranted) {
                            startRecording(MediaRecorder.AudioSource.REMOTE_SUBMIX)
                        } else {
                            Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val mgr = getSystemService(MediaProjectionManager::class.java)
                        val intent = if (Build.VERSION.SDK_INT >= 34) {
                            mgr.createScreenCaptureIntent(
                                MediaProjectionConfig.createConfigForDefaultDisplay()
                            )
                        } else {
                            mgr.createScreenCaptureIntent()
                        }
                        projectionLauncher.launch(intent)
                    }
                }
            }
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val svc = Intent(this, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(RecordingService.EXTRA_RESULT_DATA, result.data)
                ContextCompat.startForegroundService(this, svc)
            } else {
                Toast.makeText(this, R.string.record_failed, Toast.LENGTH_SHORT).show()
            }
        }

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra(RecordingService.EXTRA_STATE)) {
                RecordingService.STATE_STARTED -> {
                    serviceRecording = true
                    editDialog?.recordButton?.text = getString(R.string.recording)
                }
                RecordingService.STATE_STOPPED -> {
                    serviceRecording = false
                    editDialog?.recordButton?.text = getString(R.string.record_sound)
                    val path = intent.getStringExtra(RecordingService.EXTRA_FILE)
                    if (!path.isNullOrEmpty() && File(path).length() > 0) {
                        val local = Uri.fromFile(File(path))
                        pickedUri = local
                        editDialog?.pickedFile?.text = displayName(local)
                        editDialog?.let { setupTrimControls(it, local, 0.0, 0.0, 0.0, 0.0) }
                    } else {
                        Toast.makeText(this@MainActivity, R.string.record_failed, Toast.LENGTH_LONG).show()
                    }
                }
                RecordingService.STATE_ERROR -> {
                    serviceRecording = false
                    editDialog?.recordButton?.text = getString(R.string.record_sound)
                    Toast.makeText(this@MainActivity, R.string.record_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
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
                    player.stop()
                    settings = result.settings
                    store.saveSettings(settings)
                    applyColumnsFromSettings()
                    sounds.clear()
                    sounds.addAll(result.sounds)
                    store.save(sounds)
                    adapter.playCounts.clear()
                    loadDurations()
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
        supportActionBar?.setDisplayShowTitleEnabled(false)

        store = SoundStore(this)
        profileStore = ProfileStore(this)
        player = SoundPlayer(this)

        settings = store.loadSettings()

        sounds.addAll(store.load())
        migrateContentUrisToPrivate()

        adapter = SoundAdapter(
            sounds,
            onClick = { pos ->
                if (pos < 0 || pos >= sounds.size) return@SoundAdapter
                val s = sounds[pos]
                if (s.isHeader) {
                    showHeaderDialog(pos)
                } else if (s.uri != null) {
                    player.play(s)
                    adapter.playCounts[s.id] = (adapter.playCounts[s.id] ?: 0) + 1
                    adapter.playingId = s.id
                    adapter.notifyItemChanged(pos)
                    binding.stopButton.isEnabled = true
                } else showEditDialog(pos)
            },
            onLongClick = { pos ->
                if (pos < 0 || pos >= sounds.size) return@SoundAdapter
                if (sounds[pos].isHeader) showHeaderDialog(pos) else showEditDialog(pos)
            },
            onStartDrag = { holder ->
                if (!settings.reorderLocked) itemTouchHelper.startDrag(holder)
            }
        )
        adapter.dragEnabled = !settings.reorderLocked
        player.onStopped = {
            adapter.playingId = null
            binding.stopButton.isEnabled = false
        }
        binding.stopButton.setOnClickListener { player.stopWithQuickFade() }

        binding.recyclerView.setHasFixedSize(true)
        val layoutManager = GridLayoutManager(
            this,
            settings.columns.coerceIn(GRID_COLUMNS, MAX_COLUMNS)
        )
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            init {
                isSpanIndexCacheEnabled = false
                isSpanGroupIndexCacheEnabled = false
            }

            override fun getSpanSize(position: Int): Int =
                if (position in sounds.indices && sounds[position].isHeader) {
                    layoutManager.spanCount
                } else 1
        }
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    gestureStartColumns = layoutManager.spanCount
                    gestureStartSpan = detector.currentSpan
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (gestureStartSpan <= 0f) return false
                    val cumulative = detector.currentSpan / gestureStartSpan
                    if (cumulative <= 0f) return false
                    val target = (gestureStartColumns / cumulative)
                        .roundToInt()
                        .coerceIn(GRID_COLUMNS, MAX_COLUMNS)
                    if (target != layoutManager.spanCount) {
                        layoutManager.spanCount = target
                        settings.columns = target
                        store.saveSettings(settings)
                    }
                    return true
                }
            }
        )
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                    ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                return makeMovementFlags(dragFlags, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                Collections.swap(sounds, from, to)
                adapter.notifyItemMoved(from, to)
                (viewHolder as? SoundAdapter.ViewHolder)?.let {
                    adapter.applyItemHeight(it, sounds[to].isHeader)
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = false

            override fun isItemViewSwipeEnabled() = false

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                persist()
                recyclerView.post { adapter.notifyDataSetChanged() }
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.recyclerView)
        binding.recyclerView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            false
        }
        ContextCompat.registerReceiver(
            this,
            recordingReceiver,
            IntentFilter(RecordingService.BROADCAST_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        binding.emptyView.setOnClickListener { addTile() }
        updateEmptyView()
        loadDurations()
        showUsageNotesDialog()
    }

    override fun onResume() {
        super.onResume()
        settings = store.loadSettings()
        sounds.clear()
        sounds.addAll(store.load())
        binding.stopButton.isEnabled = false
        adapter.dragEnabled = !settings.reorderLocked
        applyColumnsFromSettings()
        adapter.notifyDataSetChanged()
        updateEmptyView()
        invalidateOptionsMenu()
    }

    private fun applyColumnsFromSettings() {
        (binding.recyclerView.layoutManager as? GridLayoutManager)?.spanCount =
            settings.columns.coerceIn(GRID_COLUMNS, MAX_COLUMNS)
    }

    private fun showUsageNotesDialog() {
        val density = resources.displayMetrics.density
        val secondaryColor = TypedValue().also {
            theme.resolveAttribute(android.R.attr.textColorSecondary, it, true)
        }.data
        val messageView = TextView(this).apply {
            setText(R.string.usage_notes_message)
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val feedbackView = TextView(this).apply {
            textSize = 12f
            setTextColor(secondaryColor)
            setPadding(0, (12 * density).toInt(), 0, 0)
            setText(R.string.usage_notes_feedback)
        }
        val footerView = TextView(this).apply {
            textSize = 11f
            setTextColor(secondaryColor)
            setPadding(0, (8 * density).toInt(), 0, 0)
            text = getString(
                R.string.usage_notes_footer,
                BuildConfig.VERSION_NAME,
                Calendar.getInstance().get(Calendar.YEAR)
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * density).toInt()
            setPadding(horizontal, 0, horizontal, 0)
            addView(messageView)
            addView(feedbackView)
            addView(footerView)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.usage_notes_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.ok, null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.title)?.isAllCaps = true
        }
        dialog.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_toggle_lock)?.setTitle(
            if (settings.reorderLocked) R.string.unlock_reorder else R.string.lock_reorder
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_add_sound -> {
                addTile()
                true
            }
            R.id.action_add_header -> {
                addHeader()
                true
            }
            R.id.action_toggle_lock -> {
                settings.reorderLocked = !settings.reorderLocked
                store.saveSettings(settings)
                adapter.dragEnabled = !settings.reorderLocked
                invalidateOptionsMenu()
                true
            }
            R.id.action_usage_notes -> {
                showUsageNotesDialog()
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
        if (isRecording()) stopRecording()
        try {
            unregisterReceiver(recordingReceiver)
        } catch (_: Exception) {
        }
        player.release()
        durationExecutor.shutdown()
        super.onDestroy()
    }

    private var idSeq = 0L

    private fun newEmptySound() =
        Sound(System.currentTimeMillis() + idSeq++, "", null)

    private fun addTile() {
        sounds.add(newEmptySound())
        persist()
        adapter.notifyItemInserted(sounds.size - 1)
        binding.recyclerView.smoothScrollToPosition(sounds.size - 1)
    }

    private fun addHeader() {
        val pos = sounds.size
        sounds.add(Sound(System.currentTimeMillis() + idSeq++, "", null, isHeader = true))
        persist()
        adapter.notifyItemInserted(pos)
        binding.recyclerView.smoothScrollToPosition(pos)
        showHeaderDialog(pos)
    }

    private fun showHeaderDialog(slot: Int) {
        if (slot < 0 || slot >= sounds.size) return
        val current = sounds[slot]
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(current.name)
        input.hint = getString(R.string.header_hint)
        input.setSelection(input.text.length)
        AlertDialog.Builder(this)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                sounds[slot] = current.copy(name = input.text.toString().trim())
                persist()
                adapter.notifyItemChanged(slot)
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete) { _, _ ->
                sounds.removeAt(slot)
                persist()
                adapter.notifyItemRemoved(slot)
            }
            .show()
    }

    private fun copyPickedToPrivate(uri: Uri, prefix: String, onDone: (Uri?) -> Unit) {
        thread {
            val local = copyToPrivateSync(uri, prefix)
            mainHandler.post { onDone(local) }
        }
    }

    private fun copyToPrivateSync(uri: Uri, prefix: String): Uri? {
        return try {
            val dir = File(filesDir, "media")
            dir.mkdirs()
            val name = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(dir, "${prefix}_${System.currentTimeMillis()}_$name")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            if (target.length() > 0) Uri.fromFile(target) else null
        } catch (e: Exception) {
            Log.e(TAG, "copyToPrivateSync failed for $uri", e)
            null
        }
    }

    private fun migrateContentUrisToPrivate() {
        var dirty = false
        for (i in sounds.indices) {
            val s = sounds[i]
            var newUri = s.uri
            var newImage = s.imageUri
            if (s.uri?.scheme == "content") {
                copyToPrivateSync(s.uri, "audio")?.let { newUri = it }
            }
            if (s.imageUri?.scheme == "content") {
                copyToPrivateSync(s.imageUri, "image")?.let { newImage = it }
            }
            if (newUri != s.uri || newImage != s.imageUri) {
                sounds[i] = s.copy(uri = newUri, imageUri = newImage)
                dirty = true
            }
        }
        if (dirty) store.save(sounds)
    }

    private fun deletePrivateMedia(uri: Uri?) {
        val u = uri ?: return
        if (u.scheme == "file") {
            val path = u.path ?: return
            if (path.startsWith(filesDir.absolutePath)) {
                File(path).delete()
            }
        }
    }

    private fun showEditDialog(slot: Int) {
        val current = sounds[slot]
        pickedUri = null
        val dialogBinding = DialogEditSoundBinding.inflate(layoutInflater)
        editDialog = dialogBinding
        dialogBinding.nameInput.setText(current.name)
        dialogBinding.pickedFile.text =
            current.uri?.let { displayName(it) } ?: getString(R.string.no_sound_picked)
        setupTrimControls(
            dialogBinding,
            current.uri,
            current.startSec,
            current.endSec,
            current.fadeInSec,
            current.fadeOutSec
        )
        dialogBinding.bgDuckSwitch.isChecked = current.bgDuckPercent == 0
        updateBgMusicUi(dialogBinding)

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

        dialogBinding.trimStart.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress > dialogBinding.trimEnd.progress) dialogBinding.trimEnd.progress = progress
                updateTrimUi(dialogBinding)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        dialogBinding.trimEnd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress < dialogBinding.trimStart.progress) dialogBinding.trimStart.progress = progress
                updateTrimUi(dialogBinding)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        dialogBinding.fadeIn.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTrimUi(dialogBinding)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        dialogBinding.fadeOutLen.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTrimUi(dialogBinding)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        dialogBinding.bgDuckSwitch.setOnCheckedChangeListener { _, _ ->
            updateBgMusicUi(dialogBinding)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .apply {
                setNeutralButton(R.string.delete) { _, _ ->
                    deletePrivateMedia(current.uri)
                    deletePrivateMedia(current.imageUri)
                    sounds.removeAt(slot)
                    persist()
                    adapter.notifyItemRemoved(slot)
                }
            }
            .create()

        dialogBinding.pickButton.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        dialogBinding.recordButton.setOnClickListener {
            if (isRecording()) {
                stopRecording()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(R.string.record_source_title)
                    .setItems(
                        arrayOf(
                            getString(R.string.record_source_mic),
                            getString(R.string.record_source_internal)
                        )
                    ) { _, which ->
                        pendingMode = if (which == 0) MODE_MIC else MODE_INTERNAL
                        requestPermissions.launch(
                            arrayOf(
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
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
                if (uri != current.uri) deletePrivateMedia(current.uri)
                if (dialogImageUri != current.imageUri) deletePrivateMedia(current.imageUri)
                sounds[slot] = Sound(
                    id = current.id,
                    name = name,
                    uri = uri,
                    category1 = selectedCategory(dialogBinding.category1Spinner, categoryOptions),
                    category2 = selectedCategory(dialogBinding.category2Spinner, categoryOptions),
                    backgroundColor = dialogColor,
                    imageUri = dialogImageUri,
                    startSec = if (dialogBinding.trimStart.max > 0) {
                        dialogBinding.trimStart.progress / 1000.0
                    } else 0.0,
                    endSec = if (dialogBinding.trimEnd.max > 0 &&
                        dialogBinding.trimEnd.progress < dialogBinding.trimEnd.max
                    ) {
                        dialogBinding.trimEnd.progress / 1000.0
                    } else 0.0,
                    fadeInSec = dialogBinding.fadeIn.progress / 1000.0,
                    fadeOutSec = dialogBinding.fadeOutLen.progress / 1000.0,
                    bgDuckPercent = if (dialogBinding.bgDuckSwitch.isChecked) 0 else 25
                )
                persist()
                loadDurations()
                adapter.notifyItemChanged(slot)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            if (isRecording()) stopRecording()
            editDialog = null
        }
        dialog.show()
    }

    private fun startRecording(audioSource: Int) {
        val b = editDialog ?: return
        try {
            val dir = File(filesDir, "media")
            dir.mkdirs()
            val file = File(dir, "audio_${System.currentTimeMillis()}_aufnahme.mp3")
            val rec = MediaRecorder(this)
            rec.setAudioSource(audioSource)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingFile = file
            b.recordButton.text = getString(R.string.recording)
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            recorder = null
            recordingFile = null
            Toast.makeText(this, R.string.record_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun isRecording() = recorder != null || serviceRecording

    private fun stopRecording() {
        val b = editDialog
        if (serviceRecording) {
            stopService(
                Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
            )
            return
        }
        val rec = recorder ?: return
        recorder = null
        val file = recordingFile
        recordingFile = null
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        try {
            rec.release()
        } catch (_: Exception) {
        }
        b?.recordButton?.text = getString(R.string.record_sound)
        if (file != null && file.exists() && file.length() > 0) {
            val local = Uri.fromFile(file)
            pickedUri = local
            b?.pickedFile?.text = displayName(local)
            b?.let { setupTrimControls(it, local, 0.0, 0.0, 0.0, 0.0) }
        } else {
            Toast.makeText(this, R.string.record_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupTrimControls(
        dialogBinding: DialogEditSoundBinding,
        uri: Uri?,
        startSec: Double,
        endSec: Double,
        fadeInSec: Double,
        fadeOutSec: Double
    ) {
        val wv = dialogBinding.waveformView
        wv.tag = uri
        wv.peaks = FloatArray(0)
        dialogBinding.trimStart.max = 0
        dialogBinding.trimEnd.max = 0
        dialogBinding.trimStart.isEnabled = false
        dialogBinding.trimEnd.isEnabled = false
        dialogBinding.fadeIn.max = FADE_SLIDER_MAX_MS
        dialogBinding.fadeOutLen.max = FADE_SLIDER_MAX_MS
        dialogBinding.fadeIn.progress = (fadeInSec * 1000).toLong().coerceIn(0, FADE_SLIDER_MAX_MS.toLong()).toInt()
        dialogBinding.fadeOutLen.progress = (fadeOutSec * 1000).toLong().coerceIn(0, FADE_SLIDER_MAX_MS.toLong()).toInt()
        updateTrimUi(dialogBinding)
        if (uri == null) return
        WaveformLoader.load(this, uri) { res ->
            if (editDialog !== dialogBinding || wv.tag != uri) return@load
            if (res == null) return@load
            val durMs = res.durationMs.coerceAtLeast(1)
            wv.peaks = res.peaks
            dialogBinding.trimStart.max = durMs.toInt()
            dialogBinding.trimEnd.max = durMs.toInt()
            val s = (startSec * 1000).toLong().coerceIn(0, durMs)
            val e = if (endSec > 0) (endSec * 1000).toLong().coerceIn(s, durMs) else durMs
            dialogBinding.trimStart.progress = s.toInt()
            dialogBinding.trimEnd.progress = e.toInt()
            dialogBinding.trimStart.isEnabled = true
            dialogBinding.trimEnd.isEnabled = true
            updateTrimUi(dialogBinding)
        }
    }

    private fun updateTrimUi(dialogBinding: DialogEditSoundBinding) {
        val dur = dialogBinding.trimStart.max.toLong()
        val s = dialogBinding.trimStart.progress.toLong()
        val e = dialogBinding.trimEnd.progress.toLong()
        dialogBinding.trimStartLabel.text =
            getString(R.string.trim_start_label) + ": " + String.format("%.1f", s / 1000.0) + " s"
        dialogBinding.trimEndLabel.text =
            getString(R.string.trim_end_label) + ": " + String.format("%.1f", e / 1000.0) + " s"
        dialogBinding.fadeInLabel.text =
            getString(R.string.fade_in_label) + ": " +
                String.format("%.1f", dialogBinding.fadeIn.progress / 1000.0) + " s"
        dialogBinding.fadeOutLenLabel.text =
            getString(R.string.fade_out_len_label) + ": " +
                String.format("%.1f", dialogBinding.fadeOutLen.progress / 1000.0) + " s"
        dialogBinding.waveformView.selStart = if (dur > 0) s.toFloat() / dur else 0f
        dialogBinding.waveformView.selEnd = if (dur > 0) e.toFloat() / dur else 1f
        dialogBinding.waveformView.invalidate()
    }

    private fun updateBgMusicUi(dialogBinding: DialogEditSoundBinding) {
        val pause = dialogBinding.bgDuckSwitch.isChecked
        dialogBinding.bgDuck25.setTypeface(if (pause) Typeface.DEFAULT else Typeface.DEFAULT_BOLD)
        dialogBinding.bgDuck0.setTypeface(if (pause) Typeface.DEFAULT_BOLD else Typeface.DEFAULT)
    }

    private fun rebuildColorRow(dialogBinding: DialogEditSoundBinding) {
        dialogBinding.colorRow.removeAllViews()
        val density = resources.displayMetrics.density
        (listOf<String?>(null) + PRESET_COLORS).forEach { hex ->
            val swatch = View(this).apply {
                val size = (30 * density).toInt()
                val margin = (6 * density).toInt()
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

    private fun setupCategorySpinner(
        view: MaterialAutoCompleteTextView,
        options: List<String>,
        current: String?
    ) {
        view.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_category, options))
        val idx = current?.let { options.indexOf(it) } ?: -1
        view.setText(options[if (idx >= 0) idx else 0], false)
    }

    private fun selectedCategory(view: MaterialAutoCompleteTextView, options: List<String>): String? {
        val pos = options.indexOf(view.text.toString())
        return if (pos <= 0) null else options[pos]
    }

    private fun persist() {
        store.save(sounds)
        updateEmptyView()
    }

    private fun loadDurations() {
        val snapshot = sounds.filter { !it.isHeader && it.uri != null }
        durationExecutor.execute {
            val result = HashMap<Long, Long>()
            for (s in snapshot) {
                val uri = s.uri ?: continue
                val retriever = MediaMetadataRetriever()
                try {
                    val path = uri.path
                    if (uri.scheme == "file" && path != null) {
                        retriever.setDataSource(path)
                    } else {
                        retriever.setDataSource(this, uri)
                    }
                    val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                    if (d != null && d > 0) result[s.id] = d
                } catch (_: Exception) {
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            }
            runOnUiThread {
                adapter.durationsMs.clear()
                adapter.durationsMs.putAll(result)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun updateEmptyView() {
        binding.emptyView.isVisible = sounds.isEmpty()
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
        private const val TAG = "MainActivity"
        private const val GRID_COLUMNS = 3
        private const val MAX_COLUMNS = 5
        private const val MODE_MIC = 0
        private const val MODE_INTERNAL = 1
        private const val FADE_SLIDER_MAX_MS = 10_000
        private val PRESET_COLORS = listOf(
            "#F44336", "#FF9800", "#FFEB3B", "#4CAF50",
            "#00B0FF", "#E0E0E0"
        )
    }
}
