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
        // Custom comparison never returns 0, so stdlib binarySearch always falls into its
        // "not found" branch and returns -(insertionPoint) - 1, where insertionPoint is the
        // first index whose start is > position (the upper bound). Stepping back one from
        // that upper bound gives the last start ≤ position — the same semantics as before.
        val insertionPoint = sortedStarts.binarySearch { start -> if (start <= position) -1 else 1 }
        val upperBound = -insertionPoint - 1
        return upperBound - 1
    }
}
