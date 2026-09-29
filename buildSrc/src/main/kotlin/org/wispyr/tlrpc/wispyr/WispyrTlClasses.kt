package org.wispyr.tlrpc.wispyr

data class WispyrTlClasses(
    val classes: Set<WispyrTlClass>
) {
    val groupedByConstructorAll = classes.groupBy { it.constructor }.mapNotNull { (k, v) -> k?.let { it to v } }.toMap()
    val groupedByConstructorUnique = groupedByConstructorAll.filterValues { it.size == 1 }.mapValues { it.value.first() }
    val groupedByConstructorDuplicated = groupedByConstructorAll.filterValues { it.size != 1 }
}