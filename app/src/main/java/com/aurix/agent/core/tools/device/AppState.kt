package com.aurix.agent.core.tools.device

/** Counts visible activities. Android only lets an app start other apps from the background if it has a visible window or overlay permission. */
object AppState {
    @Volatile var visibleActivities: Int = 0
    val inForeground: Boolean get() = visibleActivities > 0
}
