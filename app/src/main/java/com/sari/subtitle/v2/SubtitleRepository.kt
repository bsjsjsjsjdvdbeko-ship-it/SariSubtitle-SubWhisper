package com.sari.subtitle.v2

/**
 * يدير قائمة cues + الإزاحة الزمنية + البحث.
 */
class SubtitleRepository {

    private var cues: List<SubtitleCue> = emptyList()

    /** الإزاحة بالمللي ثانية (تُضاف لوقت الفيديو) */
    var offsetMs: Long = 0L

    val size: Int get() = cues.size
    val isEmpty: Boolean get() = cues.isEmpty()

    fun setCues(newCues: List<SubtitleCue>) {
        cues = newCues.sortedBy { it.startMs }
    }

    fun clear() {
        cues = emptyList()
        offsetMs = 0L
    }

    /**
     * يبحث عن cue عند وقت الفيديو + الإزاحة.
     */
    fun findCueAt(videoMs: Long): SubtitleCue? {
        if (cues.isEmpty()) return null
        val timeMs = videoMs + offsetMs

        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val c = cues[mid]
            when {
                timeMs < c.startMs -> hi = mid - 1
                timeMs >= c.endMs -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }
}
