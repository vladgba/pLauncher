package x.vladgba.launcher.search

import x.vladgba.launcher.model.AppInfo

/** Pure app-search ranking, free of Android types so it is unit-testable. */
object SearchMatcher {
    /**
     * Items whose label (or package name) contains every word of [query], best first:
     * label starts with the query, then a word in the label starts with the first query
     * word, then any other match. Ties keep alphabetical order. A blank query matches nothing.
     */
    fun <T> filter(items: List<T>, query: String, label: (T) -> String, pkg: (T) -> String): List<T> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val q = words.joinToString(" ")
        return items.mapNotNull { item ->
            val l = label(item).lowercase()
            val p = pkg(item).lowercase()
            if (!words.all { it in l || it in p }) return@mapNotNull null
            val rank = when {
                l.startsWith(q) -> 0
                l.split(' ', '-', '_', '.').any { it.startsWith(words[0]) } -> 1
                words.all { it in l } -> 2
                else -> 3
            }
            Triple(rank, l, item)
        }.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    fun filterApps(apps: List<AppInfo>, query: String): List<AppInfo> =
        filter(apps, query, { it.label }, { it.packageName })
}
