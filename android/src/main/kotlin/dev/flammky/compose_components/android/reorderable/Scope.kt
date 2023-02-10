package dev.flammky.compose_components.android.reorderable

import android.util.Log
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import dev.flammky.compose_components.core.SnapshotRead
import dev.flammky.compose_components.core.exhaustedStateException
import java.util.*

interface ReorderableLazyListScope {

    fun item(
        // as of now key is a must, there will be non-key variant in the future (or maybe not)
        key: Any,
        content: @Composable ReorderableLazyItemScope.() -> Unit
    ) = items(1, { key }) { content() }

    fun items(
        count: Int,
        // as of now key is a must, there will be non-key variant in the future (or maybe not)
        key: (Int) -> Any,
        content: @Composable ReorderableLazyItemScope.(Int) -> Unit
    )
}

internal interface InternalReorderableLazyListScope : ReorderableLazyListScope {
    val state: ReorderableLazyListState
    val intervals: List<ReorderableLazyInterval>
    fun indexOfKey(key: Any): Int
    fun itemOfIndex(index: Int): ReorderableLazyIntervalItem?
    fun isAppliedContentEqual(other: InternalReorderableLazyListScope): Boolean
    fun onContentApplied()
}

/**
 * Interface Scope of an Reorderable Lazy Item
 */
interface ReorderableLazyItemScope : LazyItemScope {

    /**
     * Info about the item
     */
    val info: ItemInfo

    /**
     * install reorder input Modifier,
     *
     * any drag gesture starting on the composable with this modifier will be interpreted as start
     * of reordering event
     */
    fun Modifier.reorderInput(): Modifier

    /**
     * install reorder input Modifier with timeout,
     *
     * any down gesture on the composable that last for at least the specified [timeMs] will be
     * interpreted as start of reordering event
     *
     * defaults to the device `Long Press` duration config, normally it's 400ms
     */
    fun Modifier.reorderLongInput(timeMs: Long? = null): Modifier

    /**
     * install visual modifiers such as `zIndex` and `graphicsLayer` for the Item to float over other item,
     *
     * this is optional and is Not applied by default
     */
    @SnapshotRead
    fun Modifier.reorderingItemVisualModifiers(): Modifier

    /**
     * Info about the item
     */
    interface ItemInfo {
        val dragging: Boolean
            @SnapshotRead get
        val cancelling: Boolean
            @SnapshotRead get
        val key: Any
        val indexInParent: Int
        val indexInBatch: Int
    }
}

internal interface InternalReorderableLazyItemScope : ReorderableLazyItemScope {
    @Composable
    fun ComposeContent()
}

@Composable
internal fun LazyItemScope.rememberInternalReorderableLazyItemScope(
    composition: InternalReorderableLazyListScope,
    displayIndex: Int,
): InternalReorderableLazyItemScope {
    return remember(composition, displayIndex) {
        val compositionItem = composition.itemOfIndex(displayIndex)
            ?: internalReorderableError("Missing Index=$displayIndex in composition")
        RealReorderableLazyItemScope(
            base = this,
            parentOrientation = if (composition.state.isVerticalScroll)
                Orientation.Vertical
            else if (composition.state.isHorizontalScroll)
                Orientation.Horizontal
            else exhaustedStateException(),
            positionInParent = ItemPosition(compositionItem.indexInParent, compositionItem.key),
            positionInBatch = ItemPosition(compositionItem.indexInInterval, compositionItem.key),
            currentDraggingItemDelta = composition.state::draggingItemDelta,
            currentDraggingItemPositionInParent = composition.state::draggingItemPosition,
            currentCancellingItemDelta = composition.state::cancellingItemDelta,
            currentCancellingItemPositionInParent = composition.state::cancellingItemPosition,
            onReorderInput = { pid, offset ->
                Log.d(
                    "Reorderable_DEBUG",
                    "onReorderInput trySend $pid $offset)"
                )
                composition.state.childReorderStartChannel
                    .trySend(ReorderDragStart(pid, offset, compositionItem.indexInParent, compositionItem.key))
            },
            content = compositionItem.content
        )
    }
}

internal class RealReorderableLazyListScope(
    override val state: ReorderableLazyListState
) : InternalReorderableLazyListScope {

    private var locked: Boolean = false
    private val indexToKeyMapping = mutableMapOf<Int, Any>()
    private val indexToItemMapping = mutableMapOf<Int, ReorderableLazyIntervalItem>()
    private val keyToIndexMapping = mutableMapOf<Any, Int>()
    private val _intervals = mutableListOf<ReorderableLazyInterval>()
    private var itemsLastIndex = 0

    override val intervals: List<ReorderableLazyInterval> = _intervals

    override fun item(
        key: Any,
        content: @Composable ReorderableLazyItemScope.() -> Unit
    ) {
        if (locked) return
        items(1, { key }) { content() }
    }

    override fun items(
        count: Int,
        key: (Int) -> Any,
        content: @Composable ReorderableLazyItemScope.(Int) -> Unit
    ) {
        if (locked) return
        val intervalIndex = intervals.size
        val intervalStartIndexInParent = itemsLastIndex
        itemsLastIndex += count
        val interval = ReorderableLazyInterval(
            startIndex = intervalIndex,
            itemStartIndex = intervalStartIndexInParent,
            items = mutableListOf<ReorderableLazyIntervalItem>()
                .apply {
                    repeat(count) { i ->
                        val iKey = key(i)
                        val item = ReorderableLazyIntervalItem(
                            indexInInterval = i,
                            indexInParent = intervalStartIndexInParent + i,
                            key = iKey,
                            type = null,
                            content = content
                        )
                        indexToKeyMapping[intervalStartIndexInParent + i] = iKey
                        indexToItemMapping[intervalStartIndexInParent + i] = item
                        keyToIndexMapping[iKey] = intervalStartIndexInParent + i
                        add(item)
                    }
                }
        )
        _intervals.add(interval)
        repeat(count) { i ->
            val iKey = key(i)
            indexToKeyMapping[intervalStartIndexInParent + i] = iKey
            keyToIndexMapping[iKey] = intervalStartIndexInParent + i
        }
    }

    override fun indexOfKey(key: Any): Int = keyToIndexMapping.getOrElse(key) { -1 }
    override fun itemOfIndex(index: Int): ReorderableLazyIntervalItem? = indexToItemMapping.getOrElse(index) { null }
    override fun isAppliedContentEqual(other: InternalReorderableLazyListScope): Boolean {
        return (_intervals == other.intervals)
    }
    override fun onContentApplied() {
        locked = true
    }
}

/*internal class MaskedReorderableLazyListScope(
    private val base: InternalReorderableLazyListScope
) : InternalReorderableLazyListScope {
    private var locked: Boolean = false
    private val indexToKeyMapping = mutableMapOf<Int, Any>()
    private val indexToItemMapping = mutableMapOf<Int, ReorderableLazyIntervalItem>()
    private val keyToIndexMapping = mutableMapOf<Any, Int>()
    private val _intervals = mutableListOf<ReorderableLazyInterval>()
    private var itemsLastIndex = 0

    override val intervals: List<ReorderableLazyInterval> = _intervals

    override val state: ReorderableLazyListState
        get() = base.state

    override val applier: ReorderableLazyListApplier
        get() = base.applier

    override fun item(
        key: Any,
        content: @Composable ReorderableLazyItemScope.() -> Unit
    ) {
        if (locked) return
        items(1, { key }) { content() }
    }

    override fun items(
        count: Int,
        key: (Int) -> Any,
        content: @Composable ReorderableLazyItemScope.(Int) -> Unit
    ) {
        if (locked) return
        val intervalIndex = intervals.size
        val intervalStartIndexInParent = itemsLastIndex
        itemsLastIndex += count
        val interval = ReorderableLazyInterval(
            startIndex = intervalIndex,
            itemStartIndex = intervalStartIndexInParent,
            items = mutableListOf<ReorderableLazyIntervalItem>()
                .apply {
                    repeat(count) { i ->
                        val iKey = key(i)
                        val item = ReorderableLazyIntervalItem(
                            indexInInterval = i,
                            indexInParent = intervalStartIndexInParent + i,
                            key = iKey,
                            type = null,
                            content = content
                        )
                        indexToKeyMapping[intervalStartIndexInParent + i] = iKey
                        indexToItemMapping[intervalStartIndexInParent + i] = item
                        keyToIndexMapping[iKey] = intervalStartIndexInParent + i
                        add(item)
                    }
                }
        )
        _intervals.add(interval)
        repeat(count) { i ->
            val iKey = key(i)
            indexToKeyMapping[intervalStartIndexInParent + i] = iKey
            keyToIndexMapping[iKey] = intervalStartIndexInParent + i
        }
    }

    override fun indexOfKey(key: Any): Int = keyToIndexMapping.getOrElse(key) { -1 }
    override fun itemOfIndex(index: Int): ReorderableLazyIntervalItem? = indexToItemMapping.getOrElse(index) { null }
    override fun isAppliedContentEqual(other: InternalReorderableLazyListScope): Boolean {
        return (_intervals == other.intervals)
    }
    override fun onContentApplied() {
        locked = true
    }

    override fun applyToLayout(
        scope: LazyListScope,
        latestComposition: @SnapshotRead () -> InternalReorderableLazyListScope?
    ) {
        intervals.fastForEach { interval ->
            scope.items(
                count = interval.items.size,
                key = { i -> interval.items[i].key },
            ) { i ->
                val composition = latestComposition()
                    ?: return@items
                rememberInternalReorderableLazyItemScope(
                    composition = composition,
                    displayIndex = i
                ).run {
                    ComposeContent()
                }
            }
        }
    }
}*/

internal class RealReorderableLazyItemScope(
    private val base: LazyItemScope,
    private val parentOrientation: Orientation,
    private val positionInParent: ItemPosition,
    private val positionInBatch: ItemPosition,
    private val currentDraggingItemPositionInParent: @SnapshotRead () -> ItemPosition?,
    private val currentDraggingItemDelta: @SnapshotRead () -> Offset,
    private val currentCancellingItemPositionInParent: @SnapshotRead () -> ItemPosition?,
    private val currentCancellingItemDelta: @SnapshotRead () -> Offset,
    private val onReorderInput: (pointerId: PointerId, offset: Offset) -> Unit,
    private val content: @Composable ReorderableLazyItemScope.(Int) -> Unit
) : InternalReorderableLazyItemScope {

    // TODO: Make things as lazy as possible
    override val info = object : ReorderableLazyItemScope.ItemInfo {
        override val dragging: Boolean by derivedStateOf(policy = structuralEqualityPolicy()) {
            currentDraggingItemPositionInParent()?.takeIf { it.key == key } != null
        }
        override val cancelling: Boolean by derivedStateOf(policy = structuralEqualityPolicy()) {
            currentCancellingItemPositionInParent()?.takeIf { it.key == key } != null
        }
        override val key: Any = positionInParent.key
        override val indexInParent: Int = positionInParent.index
        override val indexInBatch: Int = positionInBatch.index
    }

    @ExperimentalFoundationApi
    override fun Modifier.animateItemPlacement(animationSpec: FiniteAnimationSpec<IntOffset>): Modifier {
        return with(base) {
            then(animateItemPlacement(animationSpec))
        }

    }

    override fun Modifier.fillParentMaxHeight(fraction: Float): Modifier {
        return with(base) {
            then(fillParentMaxHeight(fraction))
        }
    }

    override fun Modifier.fillParentMaxSize(fraction: Float): Modifier {
        return with(base) {
            then(fillMaxSize(fraction))
        }
    }

    override fun Modifier.fillParentMaxWidth(fraction: Float): Modifier {
        return with(base) {
            then(fillParentMaxWidth(fraction))
        }
    }

    override fun Modifier.reorderInput(): Modifier {
        return this.then(
            // install suspending pointer input filter to the composable
            Modifier.pointerInput(this@RealReorderableLazyItemScope) {
                // for each possible gesture, install handle
                forEachGesture {
                    // put pointer filter
                    awaitPointerEventScope {
                        // await first down / press
                        awaitFirstDown().let { firstDown ->
                            // await for pointer slop (a distance in pixel before a gesture is considered a movement)
                            val awaitSlop = when (parentOrientation) {
                                Orientation.Vertical -> {
                                    awaitVerticalPointerSlopOrCancellation(
                                        pointerId = firstDown.id,
                                        pointerType = firstDown.type
                                    ) { change, _ ->
                                        change.consume()
                                    }
                                }
                                Orientation.Horizontal -> {
                                    awaitHorizontalPointerSlopOrCancellation(
                                        pointerId = firstDown.id,
                                        pointerType = firstDown.type
                                    ) { change, _ ->
                                        change.consume()
                                    }
                                }
                                else -> exhaustedStateException()
                            }
                            if (awaitSlop != null) {
                                check(awaitSlop.id == firstDown.id)
                                // if the slop is reached from the down event then it's a Drag Start
                                onReorderInput(awaitSlop.id, awaitSlop.position - firstDown.position)
                            }
                        }
                    }
                }
            }
        )
    }

    override fun Modifier.reorderLongInput(timeMs: Long?): Modifier {
        return this.then(
            // install pointer-input filter to the composable
            Modifier.pointerInput(this@RealReorderableLazyItemScope) {
                // for each new gesture, install handle
                forEachGesture {
                    // put pointerEvent filter
                    awaitPointerEventScope {
                        // await first down / gesture
                        awaitFirstDown()
                            .let { firstDown ->
                                // await the down event for the specified time
                                if (awaitLongPressOrCancellation(firstDown.id, timeMs) != null) {
                                    // was pressed long enough without interruption then it's a Drag Start
                                    onReorderInput(firstDown.id, Offset.Zero)
                                }
                            }
                    }
                }
            }
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    @SnapshotRead
    override fun Modifier.reorderingItemVisualModifiers(): Modifier {
        val other = if (info.dragging) {
            Modifier
                .zIndex(1f)
                .graphicsLayer {
                    when (parentOrientation) {
                        Orientation.Horizontal -> {
                            translationX = currentDraggingItemDelta().x
                        }
                        Orientation.Vertical -> {
                            translationY = currentDraggingItemDelta().y
                        }
                    }
                    Log.d("Reorderable_DEBUG", "draggingItemDelta = ($translationX, $translationY)")
                }
        } else if (info.cancelling) {
            Modifier
                .zIndex(1f)
                .graphicsLayer {
                    when (parentOrientation) {
                        Orientation.Horizontal -> {
                            translationX = currentCancellingItemDelta().x
                        }
                        Orientation.Vertical -> {
                            translationY = currentCancellingItemDelta().y
                        }
                    }
                }
        } else {
            Modifier.animateItemPlacement()
        }
        return this then other
    }

    @Composable
    override fun ComposeContent() = content(positionInParent.index)
}

internal class ReorderableLazyInterval(
    val startIndex: Int,
    val itemStartIndex: Int,
    val items: List<ReorderableLazyIntervalItem>
) {
    override fun equals(other: Any?): Boolean {
        return other is ReorderableLazyInterval && other.items == this.items
    }

    override fun hashCode(): Int {
        return items.hashCode()
    }
}

internal class ReorderableLazyIntervalItem(
    val indexInInterval: Int,
    val indexInParent: Int,
    val key: Any,
    val type: Any?,
    val content: @Composable ReorderableLazyItemScope.(index: Int) -> Unit
) {

    override fun equals(other: Any?): Boolean {
        return other is ReorderableLazyIntervalItem &&
                other.indexInInterval == this.indexInInterval &&
                other.indexInParent == this.indexInParent &&
                other.key == this.key &&
                other.type == this.type
    }

    override fun hashCode(): Int {
        return Objects.hash(indexInInterval, indexInParent, key, type)
    }
}