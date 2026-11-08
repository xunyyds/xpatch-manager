package io.xpatch.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.collect

class ScrollStateStore {

    private val states = HashMap<String, LazyListState>()
    private val positions = HashMap<String, Pair<Int, Int>>()

    fun stateOf(key: String): LazyListState = states.getOrPut(key) {
        val saved = positions[key]
        if (saved != null) LazyListState(saved.first, saved.second) else LazyListState()
    }

    fun rememberPosition(key: String, index: Int, offset: Int) {
        positions[key] = index to offset
    }

    fun retain(liveKeys: Collection<String>) {
        val alive = liveKeys.toHashSet()
        val stateKeys = states.keys.iterator()
        while (stateKeys.hasNext()) {
            if (stateKeys.next().substringBeforeLast('#') !in alive) stateKeys.remove()
        }
        val positionKeys = positions.keys.iterator()
        while (positionKeys.hasNext()) {
            if (positionKeys.next().substringBeforeLast('#') !in alive) positionKeys.remove()
        }
    }
}

val LocalScrollStateStore = staticCompositionLocalOf<ScrollStateStore?> { null }

val LocalPageKey = compositionLocalOf { "" }

@Composable
fun rememberPageScrollState(slot: Int = 0): LazyListState {
    val store = LocalScrollStateStore.current
    val pageKey = LocalPageKey.current
    if (store == null) return remember { LazyListState() }
    val key = pageKey + "#" + slot
    val state = remember(store, key) { store.stateOf(key) }
    LaunchedEffect(store, key, state) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> store.rememberPosition(key, index, offset) }
    }
    return state
}
