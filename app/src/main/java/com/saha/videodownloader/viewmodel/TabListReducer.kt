package com.saha.videodownloader.viewmodel

import com.saha.videodownloader.model.TabState

/**
 * Pure tab-list transitions, kept out of the ViewModel so they are
 * unit-testable on the JVM.
 *
 * Every operation is a no-op for an unknown tab id: WebView callbacks can
 * arrive after a tab is closed and must never resurrect it.
 */
object TabListReducer {

    const val MAX_TABS = 8

    data class Result(
        val tabs: List<TabState>,
        val activeId: Long?,
        /** Id of the tab that was created, or `null` when the cap was hit. */
        val openedId: Long? = null
    )

    fun canOpen(tabs: List<TabState>): Boolean = tabs.size < MAX_TABS

    /**
     * Appends a tab, or inserts it right after [openerTabId] so a
     * `window.open` child sits next to its parent in the strip.
     */
    fun open(
        tabs: List<TabState>,
        activeId: Long?,
        newId: Long,
        url: String? = null,
        openerTabId: Long? = null,
        select: Boolean = true
    ): Result {
        if (!canOpen(tabs)) return Result(tabs, activeId, null)

        val tab = TabState(
            id = newId,
            urlInput = url.orEmpty(),
            pendingLoadUrl = url,
            openerTabId = openerTabId,
            neverSelected = !select
        )
        val openerIndex = openerTabId?.let { id -> tabs.indexOfFirst { it.id == id } } ?: -1
        val insertAt = if (openerIndex >= 0) openerIndex + 1 else tabs.size
        val next = tabs.toMutableList().apply { add(insertAt, tab) }
        return Result(next, if (select) newId else activeId, newId)
    }

    /**
     * Removes [closeId]. When it was the active tab, focus falls back to its
     * opener, then the tab on its left, then the one on its right.
     */
    fun close(tabs: List<TabState>, activeId: Long?, closeId: Long): Result {
        val index = tabs.indexOfFirst { it.id == closeId }
        if (index < 0) return Result(tabs, activeId)

        val closing = tabs[index]
        val next = tabs.filterNot { it.id == closeId }
        if (next.isEmpty()) return Result(next, null)
        if (activeId != closeId) return Result(next, activeId)

        val fallback = closing.openerTabId?.takeIf { id -> next.any { it.id == id } }
            ?: next.getOrNull(index - 1)?.id
            // After removal the element now at [index] is the former right neighbour.
            ?: next.getOrNull(index)?.id
            ?: next.first().id
        return Result(next, fallback)
    }

    fun select(tabs: List<TabState>, id: Long): Long? =
        if (tabs.any { it.id == id }) id else null

    fun update(
        tabs: List<TabState>,
        id: Long,
        transform: (TabState) -> TabState
    ): List<TabState> {
        if (tabs.none { it.id == id }) return tabs
        return tabs.map { if (it.id == id) transform(it) else it }
    }
}
