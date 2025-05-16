package org.kotlinlsp.index.queries

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.Declaration

@OptIn(ExperimentalSerializationApi::class)
fun Index.getCompletions(prefix: String): Sequence<Declaration> = query {
    it.declarationsDb.prefixSearch(prefix)
        .map { ProtoBuf.decodeFromByteArray<Declaration>(it.second) }
}
