package com.github.jayteealao.playster.screens.transcript

/**
 * Pure position → active-paragraph mapping (AC1 logic leg). Given the playback
 * [positionSeconds] and the transcript's [sortedStarts] (segment offsets, sorted
 * ascending), returns the index of the last segment that has already started —
 * the line the reader is currently "on".
 *
 * −1 before the first segment starts (nothing active yet). The function is the
 * whole reason the per-second position tick can stay off the LazyColumn: the UI
 * folds it through `derivedStateOf`, so only the two rows whose active-ness
 * flips ever recompose (Step 9 perf discipline). Kept device-free so it is
 * JVM-unit-tested against the boundary cases.
 */
object ActiveLineIndex {
    /** Index of the last start ≤ [positionSeconds]; −1 before the first start. */
    fun activeIndex(
        positionSeconds: Float,
        sortedStarts: List<Double>,
    ): Int {
        if (sortedStarts.isEmpty()) return -1
        val position = positionSeconds.toDouble()
        // Binary search for the insertion point, then step back to the last start ≤ position.
        var lo = 0
        var hi = sortedStarts.size - 1
        var answer = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sortedStarts[mid] <= position) {
                answer = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return answer
    }
}
