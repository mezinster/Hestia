package kapoue.hestia.data.rpc

/**
 * Source des épingles de certificat enregistrées, par adresse d'appareil. Elles sont toujours lues
 * en base, jamais supposées : c'est ce qui permet au registre d'accès de les connaître avant le
 * tout premier appel, même sans qu'aucun écran n'ait encore lu la liste des appareils.
 */
fun interface SavedPinSource {
    suspend fun load(): Map<String, String>
}
