import x.vladgba.launcher.model.AppItem
import x.vladgba.launcher.model.FolderItem
import x.vladgba.launcher.model.PackageGroups
import x.vladgba.launcher.model.WorkspaceModel
import x.vladgba.launcher.model.groupByPackage

/** First-start "group apps by package" rules. */
fun packageGroupChecks() {
    val before = checks
    val m = PackageGroups::matches
    verify(m("x.vladgba.*", "x.vladgba.app"), "glob matches child package")
    verify(!m("x.vladgba.*", "x.vladgba"), "glob with dot needs a child")
    verify(!m("x.vladgba.*", "x.vladgbax.app"), "glob respects segment boundary")
    verify(m("com.google", "com.google") && m("com.google", "com.google.maps"), "bare prefix")
    verify(!m("com.google", "com.googlex"), "bare prefix respects segment boundary")
    verify(m("com.*.camera", "com.sec.camera") && !m("com.*.camera", "com.sec.camera2"), "inner wildcard")
    verify(m("com.(x)+", "com.(x)+") && !m("com.(x)+", "com.xx"), "regex characters are literal")
    verify(!m("", "a.b"), "empty pattern")

    verify(PackageGroups.parseLine(" a.*, b.* ;c ") == listOf("a.*", "b.*", "c"), "parse line")
    verify(PackageGroups.parseLine("*").isEmpty(), "lone * ignored")
    verify(PackageGroups.titleFor(listOf("x.vladgba.*")) == "Vladgba", "title from pattern")
    verify(PackageGroups.titleFor(listOf("*")) == "Folder", "title fallback")

    val apps = listOf(
        "com.android.chrome/.Main", "com.android.settings/.Settings", "com.android.camera/.Cam",
        "x.vladgba.notes/.N", "x.vladgba.timer/.T", "x.vladgba.timer/.T2", "org.solo/.A", "com.google.maps/.M",
    )
    val sugg = PackageGroups.suggest(apps)
    verify(sugg == listOf("com.android.*" to 3, "x.vladgba.*" to 3), "suggestions: $sugg")

    val groups = PackageGroups.assign(apps, listOf(listOf("x.vladgba.*"), listOf("com.*"), listOf("org.*")))
    verify(groups.size == 2, "single-app group makes no folder")
    verify(groups[0].second == apps.subList(3, 6), "first group keeps display order")
    verify(groups[1].second.size == 4 && "com.google.maps/.M" in groups[1].second, "second group")
    val first = PackageGroups.assign(apps, listOf(listOf("com.android.*"), listOf("com.*")))
    verify(first[0].second.size == 3 && first.size == 1, "an app goes to the first group only")

    val model = WorkspaceModel()
    verify(model.groupByPackage(apps, listOf(listOf("x.vladgba.*"), listOf("com.android.*"))) == 2, "folders created")
    model.syncApps(apps, 400f, 600f)
    val folders = model.folders()
    verify(model.items.filterIsInstance<AppItem>().map { it.component }.toSet() ==
        setOf("org.solo/.A", "com.google.maps/.M"), "grouped apps aren't added again")
    verify(folders.all { f -> !f.needsPlacement && f.pageX == 0 && f.pageY == 0 }, "folders placed on home page")
    verify(folders.map { it.cellX to it.cellY } == listOf(0 to 0, 1 to 0), "folders take the first cells")
    verify(model.items.filterIsInstance<FolderItem>().map { it.title } == listOf("Vladgba", "Android"), "titles")
    println("Package group checks passed: ${checks - before}")
}
