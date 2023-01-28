package dev.flammky.compose_components.core

import androidx.compose.runtime.snapshots.Snapshot

/**
 * Denote that the annotated target is a `[Snapshot] Read`.
 * meaning it will read a SnapShot target and will notify any ReadObserver that execute the block.
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
@Retention(AnnotationRetention.SOURCE)
annotation class SnapshotRead()

/**
 * Denote that the annotated target is a `[Snapshot] Reader`.
 * meaning any read of a Snapshot target within the block may cause it to produce something new.
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.TYPE,
    AnnotationTarget.TYPE_PARAMETER,
)
@Retention(AnnotationRetention.SOURCE)
annotation class SnapshotReader()

