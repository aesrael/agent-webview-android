package com.terminus.agentwebview

/**
 * Key name tables shared by the input backends. Android key codes and Windows
 * virtual-key codes (used by CDP) are plain integers, so this stays JVM-testable.
 */
object KeyTable {
    data class Key(val androidKeyCode: Int, val windowsVk: Int, val text: String?)

    private val KEYS = mapOf(
        "Enter" to Key(66, 13, "\r"),
        "Tab" to Key(61, 9, null),
        "Escape" to Key(111, 27, null),
        "Backspace" to Key(67, 8, null),
        "Delete" to Key(112, 46, null),
        "ArrowUp" to Key(19, 38, null),
        "ArrowDown" to Key(20, 40, null),
        "ArrowLeft" to Key(21, 37, null),
        "ArrowRight" to Key(22, 39, null),
        "Home" to Key(122, 36, null),
        "End" to Key(123, 35, null),
        "PageUp" to Key(92, 33, null),
        "PageDown" to Key(93, 34, null),
        "Space" to Key(62, 32, " "),
    )

    fun lookup(name: String): Key? = KEYS[name]

    // android.view.KeyEvent meta state bits
    private const val META_SHIFT_ON = 0x1
    private const val META_ALT_ON = 0x2
    private const val META_CTRL_ON = 0x1000
    private const val META_META_ON = 0x10000

    fun androidMetaState(modifiers: Set<String>): Int {
        var m = 0
        for (mod in modifiers) m = m or when (mod) {
            "Shift" -> META_SHIFT_ON
            "Alt" -> META_ALT_ON
            "Control" -> META_CTRL_ON
            "Meta" -> META_META_ON
            else -> 0
        }
        return m
    }

    /** CDP Input.dispatchKeyEvent `modifiers` bitmask: Alt=1, Ctrl=2, Meta=4, Shift=8. */
    fun cdpModifiers(modifiers: Set<String>): Int {
        var m = 0
        for (mod in modifiers) m = m or when (mod) {
            "Alt" -> 1
            "Control" -> 2
            "Meta" -> 4
            "Shift" -> 8
            else -> 0
        }
        return m
    }
}
