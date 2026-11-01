import x.vladgba.launcher.model.iconSampleSize

fun iconSizingChecks() {
    check(iconSampleSize(64, 64, 192) == 1)
    check(iconSampleSize(384, 384, 192) == 2)
    check(iconSampleSize(4096, 2048, 192) == 16)
    check(iconSampleSize(1, 8192, 192) == 32)
    check(iconSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 1) == 1 shl 30)
    for (edge in listOf(1, 63, 192, 383, 384, 512, 4096, 8192)) {
        for (target in listOf(96, 192, 288, 384)) {
            val sample = iconSampleSize(edge, edge, target)
            check(sample > 0 && sample and (sample - 1) == 0)
            check(edge / sample < target * 2)
            if (sample > 1) check(edge / sample >= target)
        }
    }
    check(runCatching { iconSampleSize(100, 100, 0) }.isFailure)
    println("Icon sizing checks passed")
}
