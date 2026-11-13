package dev.fughetta.dshcompose.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Collect a state source in the owner's coroutine scope and project it into this store. */
fun <T, V> MutableStateFlow<T>.bind(
    scope: CoroutineScope,
    source: Flow<V>,
    reduce: (T, V) -> T,
    effect: (V) -> Unit = {},
) {
    scope.launch {
        source.collect { value ->
            update { current -> reduce(current, value) }
            effect(value)
        }
    }
}
