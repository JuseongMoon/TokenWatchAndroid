package com.ScienceFiction.TokenWatchAndroid.domain

/**
 * Returns a list with the identified item swapped by [offset]. Missing IDs and out-of-bounds
 * destinations are no-ops, matching the iOS baseline's reorder behavior.
 */
fun <ID, T : Identifiable<ID>> List<T>.reordered(movingId: ID, offset: Int): List<T> {
    val from = indexOfFirst { it.id == movingId }
    if (from < 0) return this

    val destination = from + offset
    if (destination !in indices) return this

    return toMutableList().apply {
        val moving = this[from]
        this[from] = this[destination]
        this[destination] = moving
    }
}
