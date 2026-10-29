package streamix.core

import kotlinx.coroutines.CoroutineDispatcher

interface StreamixDispatchers {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
}
