package com.aurix.agent.core.tools.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class Snapshot(val pkg: String, val lines: List<String>, val more: Boolean) {
    fun text(): String = "app: ${pkg.ifEmpty { "unknown" }}\n" + (if (lines.isEmpty()) "(no readable elements)" else lines.joinToString("\n")) + if (more) "\n…(more; scroll or read again)" else ""
}

/**
 * The phone-control engine: reads the screen as a compact element list and performs taps, typing, scrolling,
 * swipes and global actions. Elements are addressed by [id] from the last snapshot or by visible text.
 */
class AurixAccessibilityService : AccessibilityService() {
    @Volatile private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { lastEventAt = System.currentTimeMillis() }
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun foregroundPackage(): String = rootInActiveWindow?.packageName?.toString().orEmpty()

    // ------------------------------------------------------------------ reading
    private fun labelOf(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return "••••(password)"
        val t = n.text?.toString()?.trim().orEmpty()
        val d = n.contentDescription?.toString()?.trim().orEmpty()
        val h = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim().orEmpty() else ""
        return (t.ifEmpty { d.ifEmpty { h } }).replace(Regex("\\s+"), " ").take(70)
    }

    private fun clickableUp(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var i = 0
        while (cur != null && i < 6) { if (cur.isClickable) return cur; cur = cur.parent; i++ }
        return null
    }

    fun snapshot(max: Int = 45): Snapshot {
        val root = rootInActiveWindow ?: return Snapshot("", emptyList(), false)
        val keep = ArrayList<AccessibilityNodeInfo>()
        val lines = ArrayList<String>()
        var more = false
        var lastLabel = ""
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30) return
            if (keep.size >= max) { more = true; return }
            if (n.isVisibleToUser) {
                val label = labelOf(n)
                if (label.isNotEmpty() || n.isEditable || n.isScrollable) {
                    if (label != lastLabel || n.isEditable) {
                        val cls = n.className?.toString().orEmpty()
                        val role = when {
                            n.isEditable || cls.contains("EditText") -> "input"
                            cls.contains("Button") -> "btn"
                            cls.contains("CheckBox") || cls.contains("Switch") || cls.contains("Radio") || cls.contains("Toggle") -> "toggle"
                            cls.contains("Image") -> "img"
                            else -> "text"
                        }
                        val flags = buildString {
                            if (clickableUp(n) != null) append('c')
                            if (n.isEditable) append('e')
                            if (n.isScrollable) append('s')
                            if (n.isChecked) append('k')
                            if (n.isFocused) append('f')
                        }
                        keep += n
                        lines += "[${keep.size}] $role \"$label\"" + if (flags.isEmpty()) "" else " $flags"
                        lastLabel = label
                    }
                }
            }
            for (i in 0 until n.childCount) { val ch = n.getChild(i) ?: continue; walk(ch, depth + 1) }
        }
        walk(root, 0)
        nodes = keep
        return Snapshot(root.packageName?.toString().orEmpty(), lines, more)
    }

    fun snapshotText(max: Int = 30): String = snapshot(max).text()

    // ------------------------------------------------------------------ finding
    fun nodeById(id: Int): AccessibilityNodeInfo? = nodes.getOrNull(id - 1)?.takeIf { it.refresh() }

    fun labelFor(id: Int): String = nodes.getOrNull(id - 1)?.let { labelOf(it) }.orEmpty()

    fun findByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val list = if (text.contains(":id/")) root.findAccessibilityNodeInfosByViewId(text) else root.findAccessibilityNodeInfosByText(text)
        val visible = list.filter { it.isVisibleToUser }
        return visible.firstOrNull { labelOf(it).equals(text, true) } ?: visible.firstOrNull() ?: list.firstOrNull()
    }

    fun findFirstWithDescription(fragment: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        fun walk(n: AccessibilityNodeInfo, d: Int): AccessibilityNodeInfo? {
            if (d > 30) return null
            if (n.isVisibleToUser && n.contentDescription?.toString()?.contains(fragment, true) == true) return n
            for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; walk(c, d + 1)?.let { return it } }
            return null
        }
        return walk(root, 0)
    }

    private fun findScrollable(): AccessibilityNodeInfo? {
        nodes.firstOrNull { it.isScrollable }?.let { return it }
        val root = rootInActiveWindow ?: return null
        fun walk(n: AccessibilityNodeInfo, d: Int): AccessibilityNodeInfo? {
            if (d > 30) return null
            if (n.isScrollable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; walk(c, d + 1)?.let { return it } }
            return null
        }
        return walk(root, 0)
    }

    // ------------------------------------------------------------------ acting
    suspend fun clickNode(node: AccessibilityNodeInfo, long: Boolean = false): String {
        val label = labelOf(node).ifEmpty { "element" }
        val target = clickableUp(node)
        if (target != null && target.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK))
            return (if (long) "long-pressed" else "clicked") + " \"$label\""
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.isEmpty) throw ToolException(ToolErrorType.TOOL_ERROR, "\"$label\" is not tappable")
        val ok = tap(r.centerX().toFloat(), r.centerY().toFloat(), if (long) 700 else 60)
        if (!ok) throw ToolException(ToolErrorType.TOOL_ERROR, "Tap on \"$label\" failed")
        return (if (long) "long-pressed" else "tapped") + " \"$label\" at (${r.centerX()},${r.centerY()})"
    }

    private suspend fun dispatch(g: GestureDescription): Boolean = suspendCancellableCoroutine { c ->
        val ok = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, null)
        if (!ok && c.isActive) c.resume(false)
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durationMs)).build())
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatch(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durationMs)).build())
    }

    /** direction = which way to move through the content: down/up/left/right. */
    suspend fun scroll(direction: String): String {
        val fwd = direction == "down" || direction == "right"
        val target = findScrollable()
        if (target != null && target.performAction(if (fwd) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
            return "scrolled $direction"
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val ok = when (direction) {
            "down" -> swipe(w / 2, h * 0.75f, w / 2, h * 0.30f)
            "up" -> swipe(w / 2, h * 0.30f, w / 2, h * 0.75f)
            "right" -> swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
            else -> swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
        }
        if (!ok) throw ToolException(ToolErrorType.TOOL_ERROR, "Scroll gesture failed")
        return "swiped $direction"
    }

    fun typeText(text: String, id: Int?, append: Boolean): String {
        var node: AccessibilityNodeInfo? = if (id != null) nodeById(id) else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable) node = nodes.firstOrNull { it.isEditable && it.refresh() }
        val field = node ?: throw ToolException(ToolErrorType.INVALID_INPUT, "No text field on screen. Tap a field first, then SCREEN_TYPE.")
        if (field.isPassword) throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Refusing to type into a password field. The user must enter passwords.")
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val base = if (append) field.text?.toString().orEmpty() else ""
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text) }
        if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "typed ${text.length} chars"
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("AURIX", text))
        if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return "pasted ${text.length} chars"
        throw ToolException(ToolErrorType.TOOL_ERROR, "This field does not accept text input")
    }

    fun pressKey(key: String): String {
        val ok = when (key) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "screenshot" -> Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            "enter" -> Build.VERSION.SDK_INT >= 30 &&
                (rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true)
            else -> throw ToolException(ToolErrorType.INVALID_INPUT, "key must be back, home, recents, notifications, quick_settings, lock, screenshot or enter")
        }
        if (!ok) throw ToolException(ToolErrorType.TOOL_ERROR, "Key '$key' could not be performed here" + if (key == "enter") " (tap the search/send button instead)" else "")
        return "pressed $key"
    }

    companion object {
        @Volatile var instance: AurixAccessibilityService? = null
        @Volatile var lastEventAt: Long = 0

        fun enabledInSettings(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return s.contains(ctx.packageName) && s.contains(AurixAccessibilityService::class.java.simpleName)
        }
    }
}
