package com.example.soundboard

import android.net.Uri

data class Sound(
    val id: Long,
    val name: String,
    val uri: Uri?,
    val durationSec: Double,
    val fadeOut: Boolean,
    val category1: String? = null,
    val category2: String? = null,
    val backgroundColor: String? = null,
    val imageUri: Uri? = null,
    val startSec: Double = 0.0,
    val endSec: Double = 0.0
)
