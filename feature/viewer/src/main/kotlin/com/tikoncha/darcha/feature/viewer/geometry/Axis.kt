package com.tikoncha.darcha.feature.viewer.geometry

/**
 * One axis of the grid — the rows or the columns — as sizes and offsets in
 * unzoomed content pixels (TECH_SPEC §9).
 *
 * Sheets are overwhelmingly default-sized, and a sheet may have a million rows,
 * so nothing is stored per index. An offset is instead computed as
 *
 * ```
 * offset(i) = i * defaultSize + Σ (size(c) - defaultSize) for every custom c < i
 * ```
 *
 * which needs only the sorted custom indices and a prefix sum of their deltas —
 * two arrays as long as the *custom* entries, typically a handful. Lookups are
 * a binary search over that small array, so memory is independent of [count].
 *
 * @param defaultSize size of an entry with no custom size, in pixels.
 * @param customSizes custom sizes in pixels, keyed by 0-based index.
 * @param count number of entries on this axis.
 */
internal class Axis(
    private val defaultSize: Float,
    customSizes: Map<Int, Float>,
    val count: Int,
) {
    /** Custom indices, ascending. */
    private val indices: IntArray = customSizes.keys
        .filter { it in 0 until count }
        .sorted()
        .toIntArray()

    /** Sizes parallel to [indices]. */
    private val sizes: FloatArray = FloatArray(indices.size) { customSizes.getValue(indices[it]) }

    /**
     * `deltas[k]` is the total size difference contributed by the first `k`
     * custom entries, so `deltas[0] == 0` and the array has one extra slot.
     */
    private val deltas: FloatArray = FloatArray(indices.size + 1).also { prefix ->
        for (k in indices.indices) prefix[k + 1] = prefix[k] + (sizes[k] - defaultSize)
    }

    /** Total length of the axis, in content pixels. */
    val totalSize: Float get() = offsetOf(count)

    /** Size of the entry at [index], in content pixels. */
    fun sizeOf(index: Int): Float {
        val k = indexOfCustom(index)
        return if (k >= 0) sizes[k] else defaultSize
    }

    /**
     * Distance from the axis origin to the start of [index], in content pixels.
     * Accepts [count] itself, which yields the axis length.
     */
    fun offsetOf(index: Int): Float {
        val clamped = index.coerceIn(0, count)
        return clamped * defaultSize + deltas[customsBefore(clamped)]
    }

    /**
     * The entry containing [offset]: the largest index whose start is at or
     * before it, clamped to the axis. Offsets past the end return the last entry.
     */
    fun indexAt(offset: Float): Int {
        if (offset <= 0f) return 0
        if (offset >= totalSize) return count - 1
        // offsetOf is strictly increasing (sizes are positive), so a plain binary
        // search over the index range works without materializing anything.
        var low = 0
        var high = count - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (offsetOf(mid) <= offset) low = mid else high = mid - 1
        }
        return low
    }

    /**
     * The last index that starts strictly before [offset] — the final entry
     * still touching a viewport that ends there. Never below 0.
     */
    fun lastIndexBefore(offset: Float): Int {
        val candidate = indexAt(offset)
        return if (candidate > 0 && offsetOf(candidate) >= offset) candidate - 1 else candidate
    }

    /** Position of [index] in [indices], or a negative value if it is not custom. */
    private fun indexOfCustom(index: Int): Int = indices.binarySearch(index)

    /** How many custom entries lie strictly before [index]. */
    private fun customsBefore(index: Int): Int {
        val found = indices.binarySearch(index)
        // binarySearch returns the insertion point as -(insertion) - 1 when absent;
        // either way that is exactly the count of smaller entries.
        return if (found >= 0) found else -found - 1
    }
}
