package com.baseprovider.streamix.jvm

import com.baseprovider.streamix.StreamixDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** JVM scheduling adapter; deliberately has no Android Main dispatcher. */
object JvmStreamixDispatchers : StreamixDispatchers {
    override val main: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
}
