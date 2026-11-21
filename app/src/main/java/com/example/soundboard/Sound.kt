package com.example.soundboard

import android.net.Uri

data class Sound(
    val id: Long,
    val name: String,
    val uri: Uri?,
    val category1: String? = null,
    val category2: String? = null,
    val backgroundColor: String? = null,
    val imageUri: Uri? = null,
    val startSec: Double = 0.0,
    val endSec: Double = 0.0,
    val fadeInSec: Double = 0.0,
    val fadeOutSec: Double = 0.0,
    val bgFadeOutSec: Double = 0.0,
    val bgFadeInSec: Double = 0.0,
    val bgDuckPercent: Int = 25,
    val isHeader: Boolean = false
)
