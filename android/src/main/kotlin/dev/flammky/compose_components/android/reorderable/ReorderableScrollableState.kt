package dev.flammky.compose_components.android.reorderable

import androidx.compose.runtime.snapshotFlow
import dev.flammky.compose_components.core.SnapshotRead
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

abstract class ReorderableScrollableState <ScrollableItemInfo> internal constructor(

) : ReorderableState<ScrollableItemInfo>() {

    abstract val isVerticalScroll: Boolean
        @SnapshotRead get

    abstract val reverseLayout: Boolean
        @SnapshotRead get

    abstract val visibleItemsInfo: List<ScrollableItemInfo>
        @SnapshotRead get

    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun observeVisibleItemInfo(): Flow<List<ScrollableItemInfo>> {
        return snapshotFlow { draggingItemIndex != null }
            .flatMapLatest { dragging ->
                if (dragging) snapshotFlow { visibleItemsInfo } else flowOf(null)
            }
            .filterNotNull()
            .distinctUntilChanged { old, new ->
                // distinct the visible scroll layout
                old.firstOrNull()?.itemIndex == new.firstOrNull()?.itemIndex &&
                        old.count() == new.count()
            }
    }
}