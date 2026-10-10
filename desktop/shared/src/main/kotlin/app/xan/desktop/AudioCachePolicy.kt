package app.xan.desktop

/** Returns the least recently used cached tracks needed to make room for an incoming file. */
internal fun audioCacheEvictions(
    entries: List<CachedCatalogTrack>,
    limitBytes: Long,
    incomingBytes: Long,
): List<CachedCatalogTrack> {
    require(limitBytes >= 0 && incomingBytes >= 0)
    var usedBytes = entries.sumOf { it.sizeBytes }
    if (usedBytes + incomingBytes <= limitBytes) return emptyList()
    val removals = mutableListOf<CachedCatalogTrack>()
    entries.asSequence().sortedBy { it.lastAccessedAt }
        .forEach { entry ->
            if (usedBytes + incomingBytes > limitBytes) {
                removals += entry
                usedBytes -= entry.sizeBytes
            }
        }
    return removals
}
