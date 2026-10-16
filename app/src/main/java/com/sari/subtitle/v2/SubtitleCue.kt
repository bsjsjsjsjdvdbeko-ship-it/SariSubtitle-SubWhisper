package com.sari.subtitle.v2

/**
 * يمثل سطر ترجمة واحد من ملف SRT.
 */
data class SubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
) {
    fun contains(ms: Long): Boolean = ms in startMs until endMs
}
