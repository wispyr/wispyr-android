package org.wispyr.tlrpc.wispyr

data class WispyrTlClass(
    val constructor: UInt?,

    val packageName: String,
    val fullName: String,
    val name: String,

    val canSerialize: Boolean,
    val canDeserialize: Boolean,
    val canReadResponse: Boolean,

    val canStaticDeserialize: Boolean,
    val staticDeserializeCreations: List<String>
) {
    override fun toString(): String {
        return "$packageName.$fullName #${constructor?.toString(16)?.padStart(8, '0') ?: ""}"
    }
}