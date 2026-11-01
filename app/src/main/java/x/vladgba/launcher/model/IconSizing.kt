package x.vladgba.launcher.model

/** Power-of-two sampling keeps the decoded longest edge below twice the target. */
internal fun iconSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    require(maxEdge > 0)
    val edge = maxOf(width, height)
    var sample = 1
    while (edge / sample / 2 >= maxEdge) sample *= 2
    return sample
}
