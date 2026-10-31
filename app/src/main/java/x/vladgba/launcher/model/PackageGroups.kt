package x.vladgba.launcher.model

/**
 * Grouping apps into folders by package name, offered on the first start.
 *
 * A group is one or more patterns such as `x.vladgba.*` or `com.android.*`; `*` matches any
 * characters. A pattern without `*` is a prefix: `com.google` matches `com.google` and
 * `com.google.anything`.
 */
object PackageGroups {

    /** Smallest number of apps a group needs to become a folder. */
    const val MIN_APPS = 2

    /** Parses "pattern, pattern" (comma or whitespace separated) into normalized patterns. */
    fun parseLine(line: String): List<String> =
        line.split(',', ' ', '\t', ';').map { it.trim() }.filter { it.isNotEmpty() && it != "*" }

    fun matches(pattern: String, packageName: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        if ('*' !in p) {
            val base = p.trimEnd('.')
            return packageName == base || packageName.startsWith("$base.")
        }
        val regex = p.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex(regex).matches(packageName)
    }

    /** Folder name for a group: last meaningful segment of its first pattern, capitalized. */
    fun titleFor(patterns: List<String>): String {
        val seg = patterns.firstOrNull().orEmpty()
            .split('.').map { it.replace("*", "") }.lastOrNull { it.isNotEmpty() }
            ?: return "Folder"
        return seg.replaceFirstChar { it.uppercase() }
    }

    /**
     * Common package prefixes (first two segments, e.g. `com.google`) shared by at least
     * [minApps] of [components], most apps first. Returned as `prefix.*` patterns with counts.
     */
    fun suggest(components: List<String>, minApps: Int = 3, limit: Int = 8): List<Pair<String, Int>> =
        components.mapNotNull { packageOf(it).split('.').takeIf { s -> s.size >= 3 }?.take(2)?.joinToString(".") }
            .groupingBy { it }.eachCount()
            .filter { it.value >= minApps }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { "${it.key}.*" to it.value }

    /**
     * Splits [components] (in display order) into folders, one per entry of [groups].
     * An app goes to the first group it matches. Groups matching fewer than [MIN_APPS]
     * apps make no folder. Returns (title, components) pairs in [groups] order.
     */
    fun assign(components: List<String>, groups: List<List<String>>): List<Pair<String, List<String>>> {
        val taken = HashSet<String>()
        val result = mutableListOf<Pair<String, List<String>>>()
        for (patterns in groups) {
            if (patterns.isEmpty()) continue
            val members = components.filter { c ->
                c !in taken && patterns.any { matches(it, packageOf(c)) }
            }
            if (members.size < MIN_APPS) continue
            taken += members
            result += titleFor(patterns) to members
        }
        return result
    }

    /** Package part of a flattened component name ("pkg/.Activity"). */
    fun packageOf(component: String) = component.substringBefore('/')
}

/**
 * First start: creates a folder per package group, waiting for placement. Called before
 * [WorkspaceModel.syncApps] so folders take the first cells and their apps aren't added again.
 * @return number of folders created
 */
fun WorkspaceModel.groupByPackage(installed: List<String>, groups: List<List<String>>): Int {
    val inFolders = folders().flatMap { it.apps }.toHashSet()
    val onPages = items.filterIsInstance<AppItem>().map { it.component }.toHashSet()
    val free = installed.filter { it !in inFolders && it !in onPages }
    val assigned = PackageGroups.assign(free, groups)
    for ((title, apps) in assigned) {
        items += FolderItem(newId(), title, apps.toMutableList()).apply { needsPlacement = true }
    }
    return assigned.size
}
