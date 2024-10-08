/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.task

import io.airbyte.cdk.command.DestinationCatalog
import io.airbyte.cdk.command.DestinationStream
import io.airbyte.cdk.command.MockDestinationCatalogFactory.Companion.stream1
import io.airbyte.cdk.state.SyncManager
import io.airbyte.cdk.task.implementor.FailStreamTask
import io.airbyte.cdk.task.implementor.FailStreamTaskFactory
import io.airbyte.cdk.task.implementor.FailSyncTask
import io.airbyte.cdk.task.implementor.FailSyncTaskFactory
import io.airbyte.cdk.test.util.CoroutineTestUtils
import io.micronaut.context.annotation.Primary
import io.micronaut.context.annotation.Requires
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@MicronautTest(
    rebuildContext = true,
    environments =
        [
            "DestinationTaskLauncherExceptionHandlerTest",
            "MockDestinationCatalog",
            "MockScopeProvider"
        ]
)
class DestinationTaskExceptionHandlerTest {
    @Inject lateinit var exceptionHandler: DestinationTaskExceptionHandler<*>

    @Singleton
    @Primary
    @Requires(env = ["DestinationTaskLauncherExceptionHandlerTest"])
    class MockFailStreamTaskFactory : FailStreamTaskFactory {
        val didRunFor = Channel<Triple<DestinationStream, Exception, Boolean>>(Channel.UNLIMITED)

        override fun make(
            exceptionHandler: DestinationTaskExceptionHandler<*>,
            exception: Exception,
            stream: DestinationStream,
            kill: Boolean
        ): FailStreamTask {
            return object : FailStreamTask {
                override suspend fun execute() {
                    didRunFor.send(Triple(stream, exception, kill))
                }
            }
        }
    }

    @Singleton
    @Primary
    @Requires(env = ["DestinationTaskLauncherExceptionHandlerTest"])
    class MockFailSyncTaskFactory : FailSyncTaskFactory {
        val didRunWith = Channel<Exception>(Channel.UNLIMITED)

        override fun make(
            exceptionHandler: DestinationTaskExceptionHandler<*>,
            exception: Exception
        ): FailSyncTask {
            return object : FailSyncTask {
                override suspend fun execute() {
                    didRunWith.send(exception)
                }
            }
        }
    }

    /**
     * Validate that the wrapper directs failures in
     * - StreamTask(s) to handleStreamFailure (and to an injected Mock FailStreamTask)
     * - SyncTask(s) to handleSyncFailure (and to an injected Mock FailSyncTask)
     */
    @Test
    fun testHandleStreamTaskException(mockFailStreamTaskFactory: MockFailStreamTaskFactory) =
        runTest {
            val mockTask =
                object : StreamTask {
                    override val stream: DestinationStream = stream1

                    override suspend fun execute() {
                        throw RuntimeException("StreamTask failure")
                    }
                }

            val wrappedTask = exceptionHandler.withExceptionHandling(mockTask)
            wrappedTask.execute()
            val (stream, exception) = mockFailStreamTaskFactory.didRunFor.receive()
            Assertions.assertEquals(stream1, stream)
            Assertions.assertTrue(exception is RuntimeException)
        }

    @Test
    fun testHandleSyncTaskException(
        mockFailStreamTaskFactory: MockFailStreamTaskFactory,
        catalog: DestinationCatalog
    ) = runTest {
        val mockTask =
            object : SyncTask {
                override suspend fun execute() {
                    throw RuntimeException("SyncTask failure")
                }
            }

        val wrappedTask = exceptionHandler.withExceptionHandling(mockTask)
        wrappedTask.execute()
        mockFailStreamTaskFactory.didRunFor.close()
        val streamResults =
            mockFailStreamTaskFactory.didRunFor.consumeAsFlow().toList().associate {
                (stream, exception, kill) ->
                stream to Pair(exception, kill)
            }
        println(streamResults)
        catalog.streams.forEach { stream ->
            Assertions.assertTrue(streamResults[stream]!!.first is RuntimeException)
            Assertions.assertTrue(streamResults[stream]!!.second)
        }
    }

    @Test
    fun testSyncFailureBlocksSyncTasks(
        mockFailSyncTaskFactory: MockFailSyncTaskFactory,
        syncManager: SyncManager,
        catalog: DestinationCatalog
    ) = runTest {
        val innerTaskRan = Channel<Boolean>(Channel.UNLIMITED)
        val mockTask =
            object : SyncTask {
                override suspend fun execute() {
                    innerTaskRan.send(true)
                }
            }

        val wrappedTask = exceptionHandler.withExceptionHandling(mockTask)
        catalog.streams.forEach {
            syncManager.getStreamManager(it.descriptor).markFailed(RuntimeException("dummy"))
        }
        syncManager.markFailed(RuntimeException("dummy failure"))
        wrappedTask.execute()
        delay(1000)
        Assertions.assertTrue(mockFailSyncTaskFactory.didRunWith.tryReceive().isFailure)
        Assertions.assertTrue(innerTaskRan.tryReceive().isFailure)
    }

    @Test
    fun testSyncFailureAfterSuccessThrows(syncManager: SyncManager, catalog: DestinationCatalog) =
        runTest {
            val mockTask =
                object : SyncTask {
                    override suspend fun execute() {
                        // do nothing
                    }
                }

            val wrappedTask = exceptionHandler.withExceptionHandling(mockTask)

            for (stream in catalog.streams) {
                val manager = syncManager.getStreamManager(stream.descriptor)
                manager.markEndOfStream()
                manager.markSucceeded()
            }
            syncManager.markSucceeded()
            CoroutineTestUtils.assertThrows(IllegalStateException::class) { wrappedTask.execute() }
        }

    @Test
    fun testStreamFailureBlocksStreamTasks(
        mockFailStreamTaskFactory: MockFailStreamTaskFactory,
        syncManager: SyncManager
    ) = runTest {
        val innerTaskRan = Channel<Boolean>(Channel.UNLIMITED)
        val mockTask =
            object : StreamTask {
                override val stream: DestinationStream = stream1

                override suspend fun execute() {
                    innerTaskRan.send(true)
                }
            }

        val wrappedTask = exceptionHandler.withExceptionHandling(mockTask)
        val manager = syncManager.getStreamManager(stream1.descriptor)
        manager.markEndOfStream()
        manager.markFailed(RuntimeException("dummy failure"))
        launch { wrappedTask.execute() }
        delay(1000)
        Assertions.assertTrue(mockFailStreamTaskFactory.didRunFor.tryReceive().isFailure)
        Assertions.assertTrue(innerTaskRan.tryReceive().isFailure)
    }

    @Test
    fun testStreamFailureAfterSuccessThrows(
        mockFailStreamTaskFactory: MockFailStreamTaskFactory,
        syncManager: SyncManager,
        catalog: DestinationCatalog
    ) = runTest {
        val mockTask =
            object : StreamTask {
                override val stream: DestinationStream = stream1

                override suspend fun execute() {
                    // do nothing
                }
            }

        val wrappedTask = exceptionHandler.withExceptionHandling(mockTask)

        val manager = syncManager.getStreamManager(stream1.descriptor)
        manager.markEndOfStream()
        manager.markSucceeded()

        // This won't throw, because the sync wrapper will catch it and call it a sync error
        CoroutineTestUtils.assertDoesNotThrow { wrappedTask.execute() }
        mockFailStreamTaskFactory.didRunFor.close()
        val results = mockFailStreamTaskFactory.didRunFor.consumeAsFlow().toList()
        Assertions.assertEquals(catalog.streams.size, results.size)
    }

    @Test
    fun testHandleTeardownComplete(scopeProvider: MockScopeProvider) = runTest {
        exceptionHandler.handleTeardownComplete()
        Assertions.assertTrue(scopeProvider.didClose)
    }
}