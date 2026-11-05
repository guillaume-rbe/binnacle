package com.shilapi.xcertplay.airplay

/**
 * Store of paired controllers keyed by their long-term Ed25519 public key.
 *
 * Holds at most [maxPairings]: pair-setup uses a fixed PIN, so without a cap every client that
 * completes it would be remembered for good. Saving a pairing makes it the newest; past the cap
 * the oldest is dropped and reported to [onEvict] so it can be removed from storage too.
 */
class PairingStore(
    private val onSave: ((String, ByteArray) -> Unit)? = null,
    private val onEvict: ((String) -> Unit)? = null,
    private val maxPairings: Int = DEFAULT_MAX_PAIRINGS,
) {
    // insertion order = oldest first
    private val entries = LinkedHashMap<String, ByteArray>()

    init {
        require(maxPairings > 0) { "maxPairings must be positive" }
    }

    @Synchronized
    fun save(identifier: String, longTermPublicKey: ByteArray) {
        val copy = longTermPublicKey.copyOf()
        entries.remove(identifier)
        entries[identifier] = copy
        onSave?.invoke(identifier, copy)
        while (entries.size > maxPairings) {
            val oldest = entries.keys.first()
            entries.remove(oldest)
            onEvict?.invoke(oldest)
        }
    }

    @Synchronized
    fun get(identifier: String): ByteArray? = entries[identifier]?.copyOf()

    /** Identifiers, oldest first. */
    @Synchronized
    fun identifiers(): List<String> = entries.keys.toList()

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_MAX_PAIRINGS = 8
    }
}
