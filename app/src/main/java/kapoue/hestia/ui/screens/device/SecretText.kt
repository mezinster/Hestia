package kapoue.hestia.ui.screens.device

/**
 * Texte secret (saisie d'un mot de passe) : comparé par valeur, mais jamais imprimé par
 * [toString] — l'état de l'écran peut ainsi finir dans un journal ou une trace sans le divulguer.
 */
class SecretText(val value: String = "") {
    fun isEmpty(): Boolean = value.isEmpty()
    fun isNotEmpty(): Boolean = value.isNotEmpty()
    override fun equals(other: Any?): Boolean = other is SecretText && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "SecretText(***)"

    companion object {
        val EMPTY = SecretText()
    }
}
