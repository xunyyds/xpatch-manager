package io.xpatch.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 按页面 key 保存滚动位置。页面仍在导航栈中时保留，
 * 完全离开（被出栈）时由 [retain] 清理，回到列表即从顶部开始。
 */
class ScrollStateStore {

    private val states = HashMap<String, LazyListState>()

    fun stateOf(key: String): LazyListState = states.getOrPut(key) { LazyListState() }

    fun retain(liveKeys: Collection<String>) {
        val alive = liveKeys.toHashSet()
        states.keys.retainAll(alive)
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
    return remember(store, key) { store.stateOf(key) }
}
