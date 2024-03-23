/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.base.destination.typing_deduping

import java.util.stream.Stream

class AlterTableReport(columnsToAdd: Set<String>,
                       columnsToRemove: Set<String>,
                       columnsToChangeType: Set<String>,
                       isDestinationV2Format: Boolean) {
    val isNoOp: Boolean
        /**
         * A no-op for an AlterTableReport is when the existing table matches the expected schema
         *
         * @return whether the schema matches
         */
        get() = isDestinationV2Format && Stream.of(this.columnsToAdd, this.columnsToRemove, this.columnsToChangeType)
                .allMatch { obj: Set<String> -> obj.isEmpty() }

    val columnsToAdd: Set<String>
    val columnsToRemove: Set<String>
    val columnsToChangeType: Set<String>
    val isDestinationV2Format: Boolean

    init {
        this.transactions = transactions
        this.items = items
        this.properties = properties
        this.name = name
        this.originalName = originalName
        this.canonicalName = canonicalName
        this.finalNamespace = finalNamespace
        this.finalName = finalName
        this.rawNamespace = rawNamespace
        this.rawName = rawName
        this.originalNamespace = originalNamespace
        this.originalName = originalName
        this.id = id
        this.syncMode = syncMode
        this.destinationSyncMode = destinationSyncMode
        this.primaryKey = primaryKey
        this.cursor = cursor
        this.columns = columns
        this.streams = streams
        this.columnsToAdd = columnsToAdd
        this.columnsToRemove = columnsToRemove
        this.columnsToChangeType = columnsToChangeType
        this.isDestinationV2Format = isDestinationV2Format
    }
}
