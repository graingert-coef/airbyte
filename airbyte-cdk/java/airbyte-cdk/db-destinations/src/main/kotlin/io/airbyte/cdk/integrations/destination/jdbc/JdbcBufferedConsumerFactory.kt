/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.cdk.integrations.destination.jdbc

import com.fasterxml.jackson.databind.JsonNode
import com.google.common.base.Preconditions
import io.airbyte.cdk.core.command.option.AirbyteConfiguredCatalog
import io.airbyte.cdk.core.command.option.ConnectorConfiguration
import io.airbyte.cdk.db.jdbc.JdbcDatabase
import io.airbyte.cdk.db.jdbc.JdbcUtils
import io.airbyte.cdk.integrations.base.JavaBaseConstants
import io.airbyte.cdk.integrations.base.SerializedAirbyteMessageConsumer
import io.airbyte.cdk.integrations.base.TypingAndDedupingFlag.getRawNamespaceOverride
import io.airbyte.cdk.integrations.base.TypingAndDedupingFlag.isDestinationV2
import io.airbyte.cdk.integrations.destination.NamingConventionTransformer
import io.airbyte.cdk.integrations.destination.StreamSyncSummary
import io.airbyte.cdk.integrations.destination.async.AirbyteFileUtils
import io.airbyte.cdk.integrations.destination.async.AsyncStreamConsumer
import io.airbyte.cdk.integrations.destination.async.DetectStreamToFlush
import io.airbyte.cdk.integrations.destination.async.FlushWorkers
import io.airbyte.cdk.integrations.destination.async.GlobalMemoryManager
import io.airbyte.cdk.integrations.destination.async.RunningFlushWorkers
import io.airbyte.cdk.integrations.destination.async.StreamDescriptorUtils
import io.airbyte.cdk.integrations.destination.async.buffers.AsyncBuffers
import io.airbyte.cdk.integrations.destination.async.buffers.BufferDequeue
import io.airbyte.cdk.integrations.destination.async.buffers.BufferEnqueue
import io.airbyte.cdk.integrations.destination.async.buffers.BufferMemory
import io.airbyte.cdk.integrations.destination.async.deser.DeserializationUtil
import io.airbyte.cdk.integrations.destination.async.deser.IdentityDataTransformer
import io.airbyte.cdk.integrations.destination.async.deser.StreamAwareDataTransformer
import io.airbyte.cdk.integrations.destination.async.function.DestinationFlushFunction
import io.airbyte.cdk.integrations.destination.async.model.PartialAirbyteMessage
import io.airbyte.cdk.integrations.destination.async.state.FlushFailure
import io.airbyte.cdk.integrations.destination.async.state.GlobalAsyncStateManager
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.OnCloseFunction
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.OnStartFunction
import io.airbyte.cdk.integrations.destination.buffered_stream_consumer.RecordWriter
import io.airbyte.commons.json.Jsons
import io.airbyte.integrations.base.destination.typing_deduping.StreamId.Companion.concatenateRawTableName
import io.airbyte.integrations.base.destination.typing_deduping.TyperDeduper
import io.airbyte.protocol.models.v0.*
import io.micronaut.scheduling.ScheduledExecutorTaskScheduler
import io.micronaut.scheduling.instrument.InstrumentedExecutorService
import java.util.*
import java.util.concurrent.Executors
import java.util.function.Consumer
import java.util.function.Function
import java.util.stream.Collectors
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Strategy:
 *
 * 1. Create a final table for each stream
 *
 * 2. Accumulate records in a buffer. One buffer per stream
 *
 * 3. As records accumulate write them in batch to the database. We set a minimum numbers of records
 * before writing to avoid wasteful record-wise writes. In the case with slow syncs this will be
 * superseded with a periodic record flush from
 * [io.airbyte.cdk.integrations.destination.buffered_stream_consumer.BufferedStreamConsumer.periodicBufferFlush]
 *
 * 4. Once all records have been written to buffer, flush the buffer and write any remaining records
 * to the database (regardless of how few are left)
 */
object JdbcBufferedConsumerFactory {
    private val LOGGER: Logger = LoggerFactory.getLogger(JdbcBufferedConsumerFactory::class.java)

    @JvmOverloads
    fun createAsync(
        outputRecordCollector: Consumer<AirbyteMessage>,
        database: JdbcDatabase,
        sqlOperations: SqlOperations,
        namingResolver: NamingConventionTransformer,
        config: JsonNode,
        catalog: ConfiguredAirbyteCatalog,
        defaultNamespace: String?,
        typerDeduper: TyperDeduper,
        dataTransformer: StreamAwareDataTransformer = IdentityDataTransformer()
    ): SerializedAirbyteMessageConsumer {
        val writeConfigs =
            createWriteConfigs(namingResolver, config, catalog, sqlOperations.isSchemaRequired)
        val asyncBuffers = AsyncBuffers()
        val bufferMemory: BufferMemory =
            object : BufferMemory() {
                override fun getMemoryLimit(): Long {
                    return ((Runtime.getRuntime().maxMemory() * 0.2).toLong())
                }
            }
        val memoryManager = GlobalMemoryManager(bufferMemory)
        val stateManager = GlobalAsyncStateManager(memoryManager)
        val bufferEnqueue = BufferEnqueue(memoryManager, stateManager, asyncBuffers)
        val bufferDequeue = BufferDequeue(memoryManager, stateManager, asyncBuffers)
        val runningFlushWorkers = RunningFlushWorkers()
        val destinationFlushFunction: DestinationFlushFunction =
            JdbcInsertFlushFunction(
                recordWriterFunction(
                    database,
                    sqlOperations,
                    writeConfigs,
                    catalog,
                ),
            )
        val airbyteFileUtils = AirbyteFileUtils()
        val detectStreamToFlush =
            DetectStreamToFlush(
                bufferDequeue,
                runningFlushWorkers,
                destinationFlushFunction,
                airbyteFileUtils,
                Optional.empty(),
            )
        val flushFailure = FlushFailure()
        val flushWorkers =
            FlushWorkers(
                stateManager,
                bufferDequeue,
                destinationFlushFunction,
                outputRecordCollector,
                InstrumentedExecutorService {
                    Executors.newScheduledThreadPool(
                        2,
                    )
                },
                ScheduledExecutorTaskScheduler(Executors.newScheduledThreadPool(2)),
                detectStreamToFlush,
                runningFlushWorkers,
                flushFailure,
                airbyteFileUtils,
            )
        val configuration: ConnectorConfiguration =
            object : ConnectorConfiguration {
                override fun getDefaultNamespace(): Optional<String> {
                    return Optional.ofNullable(defaultNamespace)
                }

                override fun getRawNamespace(): Optional<String> {
                    return Optional.empty()
                }
            }
        val airbyteConfiguredCatalog: AirbyteConfiguredCatalog =
            object : AirbyteConfiguredCatalog() {
                override fun getConfiguredCatalog(): ConfiguredAirbyteCatalog {
                    return catalog
                }
            }
        return AsyncStreamConsumer(
            onStartFunction(database, sqlOperations, writeConfigs, typerDeduper),
            onCloseFunction(typerDeduper),
            configuration,
            airbyteConfiguredCatalog,
            bufferEnqueue,
            flushWorkers,
            flushFailure,
            dataTransformer,
            DeserializationUtil(),
            StreamDescriptorUtils(),
        )
    }

    private fun createWriteConfigs(
        namingResolver: NamingConventionTransformer,
        config: JsonNode,
        catalog: ConfiguredAirbyteCatalog?,
        schemaRequired: Boolean
    ): List<WriteConfig> {
        if (schemaRequired) {
            Preconditions.checkState(
                config.has("schema"),
                "jdbc destinations must specify a schema.",
            )
        }
        return catalog!!
            .streams
            .stream()
            .map(toWriteConfig(namingResolver, config, schemaRequired))
            .collect(Collectors.toList())
    }

    private fun toWriteConfig(
        namingResolver: NamingConventionTransformer,
        config: JsonNode,
        schemaRequired: Boolean
    ): Function<ConfiguredAirbyteStream, WriteConfig> {
        return Function { stream: ConfiguredAirbyteStream ->
            Preconditions.checkNotNull(
                stream.destinationSyncMode,
                "Undefined destination sync mode",
            )
            val abStream = stream.stream

            val defaultSchemaName =
                if (schemaRequired) namingResolver.getIdentifier(config["schema"].asText())
                else namingResolver.getIdentifier(config[JdbcUtils.DATABASE_KEY].asText())
            // Method checks for v2
            val outputSchema = getOutputSchema(abStream, defaultSchemaName, namingResolver)
            val streamName = abStream.name
            val tableName: String
            val tmpTableName: String
            // TODO: Should this be injected from outside ?
            if (isDestinationV2) {
                val finalSchema = Optional.ofNullable(abStream.namespace).orElse(defaultSchemaName)
                val rawName = concatenateRawTableName(finalSchema, streamName)
                tableName = namingResolver.convertStreamName(rawName)
                tmpTableName = namingResolver.getTmpTableName(rawName)
            } else {
                tableName = namingResolver.getRawTableName(streamName)
                tmpTableName = namingResolver.getTmpTableName(streamName)
            }
            val syncMode = stream.destinationSyncMode

            val writeConfig =
                WriteConfig(
                    streamName,
                    abStream.namespace,
                    outputSchema,
                    tmpTableName,
                    tableName,
                    syncMode,
                )
            LOGGER.info("Write config: {}", writeConfig)
            writeConfig
        }
    }

    /**
     * Defer to the [AirbyteStream]'s namespace. If this is not set, use the destination's default
     * schema. This namespace is source-provided, and can be potentially empty.
     *
     * The logic here matches the logic in the catalog_process.py for Normalization. Any
     * modifications need to be reflected there and vice versa.
     */
    private fun getOutputSchema(
        stream: AirbyteStream,
        defaultDestSchema: String,
        namingResolver: NamingConventionTransformer
    ): String {
        return if (isDestinationV2) {
            namingResolver.getNamespace(
                getRawNamespaceOverride(AbstractJdbcDestination.Companion.RAW_SCHEMA_OVERRIDE)
                    .orElse(JavaBaseConstants.DEFAULT_AIRBYTE_INTERNAL_NAMESPACE),
            )
        } else {
            namingResolver.getNamespace(
                Optional.ofNullable<String>(stream.namespace).orElse(defaultDestSchema),
            )
        }
    }

    /**
     * Sets up destination storage through:
     *
     * 1. Creates Schema (if not exists)
     *
     * 2. Creates airybte_raw table (if not exists)
     *
     * 3. <Optional>Truncates table if sync mode is in OVERWRITE
     *
     * @param database JDBC database to connect to
     * @param sqlOperations interface for execution SQL queries
     * @param writeConfigs settings for each stream </Optional>
     */
    private fun onStartFunction(
        database: JdbcDatabase,
        sqlOperations: SqlOperations,
        writeConfigs: Collection<WriteConfig>,
        typerDeduper: TyperDeduper
    ): OnStartFunction {
        return OnStartFunction {
            typerDeduper.prepareSchemasAndRunMigrations()
            LOGGER.info(
                "Preparing raw tables in destination started for {} streams",
                writeConfigs.size,
            )
            val queryList: MutableList<String> = ArrayList()
            for (writeConfig in writeConfigs) {
                val schemaName = writeConfig.outputSchemaName
                val dstTableName = writeConfig.outputTableName
                LOGGER.info(
                    "Preparing raw table in destination started for stream {}. schema: {}, table name: {}",
                    writeConfig.streamName,
                    schemaName,
                    dstTableName,
                )
                sqlOperations.createSchemaIfNotExists(database, schemaName)
                sqlOperations.createTableIfNotExists(database, schemaName, dstTableName)
                when (writeConfig.syncMode) {
                    DestinationSyncMode.OVERWRITE ->
                        queryList.add(
                            sqlOperations.truncateTableQuery(database, schemaName, dstTableName),
                        )
                    DestinationSyncMode.APPEND,
                    DestinationSyncMode.APPEND_DEDUP -> {}
                    else ->
                        throw IllegalStateException(
                            "Unrecognized sync mode: " + writeConfig.syncMode,
                        )
                }
            }
            sqlOperations.executeTransaction(database, queryList)
            LOGGER.info("Preparing raw tables in destination completed.")
            typerDeduper.prepareFinalTables()
        }
    }

    /**
     * Writes [AirbyteRecordMessage] to JDBC database's airbyte_raw table
     *
     * @param database JDBC database to connect to
     * @param sqlOperations interface of SQL queries to execute
     * @param writeConfigs settings for each stream
     * @param catalog catalog of all streams to sync
     */
    private fun recordWriterFunction(
        database: JdbcDatabase,
        sqlOperations: SqlOperations,
        writeConfigs: List<WriteConfig>,
        catalog: ConfiguredAirbyteCatalog?
    ): RecordWriter<PartialAirbyteMessage> {
        val pairToWriteConfig: Map<AirbyteStreamNameNamespacePair, WriteConfig> =
            writeConfigs.associateBy { toNameNamespacePair(it) }

        return RecordWriter {
            pair: AirbyteStreamNameNamespacePair,
            records: List<PartialAirbyteMessage> ->
            require(pairToWriteConfig.containsKey(pair)) {
                String.format(
                    "Message contained record from a stream that was not in the catalog. \ncatalog: %s",
                    Jsons.serialize(catalog),
                )
            }
            val writeConfig = pairToWriteConfig.getValue(pair)
            sqlOperations.insertRecords(
                database,
                ArrayList(records),
                writeConfig.outputSchemaName,
                writeConfig.outputTableName,
            )
        }
    }

    /** Tear down functionality */
    private fun onCloseFunction(typerDeduper: TyperDeduper): OnCloseFunction {
        return OnCloseFunction {
            _: Boolean,
            streamSyncSummaries: Map<StreamDescriptor, StreamSyncSummary> ->
            try {
                typerDeduper.typeAndDedupe(streamSyncSummaries)
                typerDeduper.commitFinalTables()
                typerDeduper.cleanup()
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
    }

    private fun toNameNamespacePair(config: WriteConfig): AirbyteStreamNameNamespacePair {
        return AirbyteStreamNameNamespacePair(config.streamName, config.namespace)
    }
}
