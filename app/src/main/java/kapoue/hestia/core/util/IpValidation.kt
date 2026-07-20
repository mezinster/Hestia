package kapoue.hestia.core.util

/** Valide une adresse IPv4 « a.b.c.d » avec chaque octet dans 0..255. */
fun isValidIpv4(value: String): Boolean {
    val parts = value.trim().split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() &&
            part.length <= 3 &&
            part.all(Char::isDigit) &&
            part.toInt() in 0..255
    }
}
