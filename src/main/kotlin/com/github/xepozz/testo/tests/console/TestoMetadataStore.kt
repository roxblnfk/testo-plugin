package com.github.xepozz.testo.tests.console

/**
 * The [type] TeamCity gives a `testMetadata` value. Testo emits `number|text|link|image|artifact`; `video` is in the
 * TeamCity spec and kept for completeness. Anything unrecognised is read as [TEXT] rather than dropped.
 *
 * @see <a href="https://www.jetbrains.com/help/teamcity/reporting-test-metadata.html">Reporting test metadata</a>
 */
enum class TestoMetadataType(val wire: String) {
    NUMBER("number"),
    TEXT("text"),
    LINK("link"),
    IMAGE("image"),
    VIDEO("video"),
    ARTIFACT("artifact");

    companion object {
        fun fromWire(wire: String?): TestoMetadataType =
            entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: TEXT
    }
}

/** One `##teamcity[testMetadata …]` datum: a named value under one test, carrying the type it was declared with. */
data class TestoMetadataEntry(val name: String, val type: TestoMetadataType, val value: String)

/**
 * The `testMetadata` a run reports, kept per test in arrival order. Standard TeamCity, but the platform's PHP-built
 * console does nothing with it — so the converter consumes the message and files it here instead of letting it echo
 * into the output stream (see [TestoOutputToGeneralEventsConverter]).
 *
 * Keyed by the same per-test key as [ChannelOutputStore] ([ChannelOutputStore.keyFor]), so the two always agree on
 * which test a datum belongs to. Written off the process's reader thread, read on the EDT — hence the lock.
 */
class TestoMetadataStore {
    private val lock = Any()
    private val byTest = LinkedHashMap<String, MutableList<TestoMetadataEntry>>()

    fun append(testKey: String, entry: TestoMetadataEntry) {
        synchronized(lock) { byTest.getOrPut(testKey) { mutableListOf() }.add(entry) }
    }

    fun entriesFor(testKey: String): List<TestoMetadataEntry> =
        synchronized(lock) { byTest[testKey]?.toList() ?: emptyList() }

    /** Every datum of the run, across all tests — for the archiver, which captures the local files they point at. */
    fun allEntries(): List<TestoMetadataEntry> =
        synchronized(lock) { byTest.values.flatten() }

    fun hasEntries(testKey: String): Boolean =
        synchronized(lock) { byTest[testKey]?.isNotEmpty() == true }

    fun clear() {
        synchronized(lock) { byTest.clear() }
    }
}
