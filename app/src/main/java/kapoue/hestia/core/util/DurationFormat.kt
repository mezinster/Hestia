package kapoue.hestia.core.util

/** Formate une durée en secondes pour un compte à rebours : « M:SS » ou « H:MM:SS ». */
fun formatCountdown(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
