package work.kumarfamilynet.cinemarchive.core.model

enum class NavigationDestination(val key: String, val label: String) {
    DISCOVER("discover", "Discover"), LIBRARY("library", "The Library"), UP_NEXT("upnext", "Up Next"),
    LEDGER("ledger", "The Ledger"), LISTS("lists", "Lists"),
}

/** Device-local preferences, matching the web's navigation settings. */
data class NavigationPreferences(
    val order: List<NavigationDestination> = NavigationDestination.entries.toList(),
    val hidden: Set<NavigationDestination> = emptySet(),
    val compact: Boolean = false,
) {
    val visible: List<NavigationDestination> get() = order.filterNot(hidden::contains)

    fun move(destination: NavigationDestination, direction: Int): NavigationPreferences {
        require(direction == -1 || direction == 1)
        val index = order.indexOf(destination)
        val target = index + direction
        if (index < 0 || target !in order.indices) return this
        return copy(order = order.toMutableList().apply { this[index] = this[target]; this[target] = destination })
    }

    fun show(destination: NavigationDestination, visible: Boolean): NavigationPreferences {
        if (!visible && destination !in hidden && this.visible.size == 1) return this
        return copy(hidden = if (visible) hidden - destination else hidden + destination)
    }

    companion object {
        fun restore(order: List<String>, hidden: Set<String>, compact: Boolean): NavigationPreferences {
            val known = NavigationDestination.entries.associateBy { it.key }
            val normalized = (order.mapNotNull(known::get) + NavigationDestination.entries).distinct()
            val hiddenItems = hidden.mapNotNull(known::get).toSet()
            return NavigationPreferences(normalized,
                if (hiddenItems.size == normalized.size) hiddenItems - normalized.first() else hiddenItems, compact)
        }
    }
}
