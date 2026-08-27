package com.roverspi.memsgauge.logging

import android.net.Uri

data class LogFileEntry(
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val uri: Uri
)
