/*
 * Portions of this file are derived from google/artemis (https://github.com/google/artemis, commit 351ca842),
 * Copyright 2026 Google LLC, licensed under the Apache License, Version 2.0: artemis/utils/ui_filter.py (screen clipping,
 * minimum size, merging a clickable row's complementary text children, parent/child redundancy),
 * artemis/utils/visualization.py format_minimal_list_with_elements (indexed list with 0-1000 bounds, duplicate
 * suppression) and artemis/utils/element_hit_test.py (smallest covering element). Rewritten in Kotlin and changed for
 * Blaze. See THIRD_PARTY_NOTICES.md.
 */
package com.blaze.assistant.phone.flash

import com.blaze.assistant.phone.ScreenTree

/**
 * 0.46.3 flash engine: the model's view of the screen. A compact, indexed list of the elements that matter, each with its
 * label, role, state (on/off, disabled, selected) and bounds on a 0-1000 grid, so the model acts by index and can fall back
 * to coordinates. Pure Kotlin (generic over the node type), so it is unit tested on fake trees (FlashTreeTest).
 */
data class UiNode(
    val index: Int,
    val role: String,
    val label: String,
    val checked: Boolean? = null,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val l: Int = 0, val t: Int = 0, val r: Int = 0, val b: Int = 0,
    /** The label is the field's hint (an empty field). */
    val hint: Boolean = false,
    /** Found on the screenshot (visual locate), not in the accessibility tree. */
    val visual: Boolean = false,
    val viewId: String = "",
) {
    val cx: Int get() = (l + r) / 2
    val cy: Int get() = (t + b) / 2
    val area: Long get() = maxOf(1, r - l).toLong() * maxOf(1, b - t).toLong()
    fun contains(x: Int, y: Int) = x in l..r && y in t..b
}

/** A node walker that also knows where a node is (screen px) and its view id. */
interface NodeAccess<N> : ScreenTree.Access<N> {
    /** left, top, right, bottom in screen px. */
    fun bounds(n: N): IntArray
    fun viewId(n: N): String = ""
}

object FlashTree {
    const val MAX_NODES = 150
    const val MAX_DEPTH = 40
    /** Elements this small (px) on either side are left out (Artemis min-size filter). */
    const val MIN_SIZE = 2
    /** Two elements with the same label whose centres are closer than this (px) are one. */
    const val DUP_PX = 8

    /**
     * Walks [root] and returns the indexed elements (index from 1) with the node each came from. Off-screen and fully
     * clipped parts are skipped; a clickable row without its own label takes its text children's words (and the state of
     * a switch inside it); a child that repeats its parent's label is dropped; duplicates are suppressed.
     */
    fun <N : Any> build(root: N?, a: NodeAccess<N>, width: Int, height: Int, max: Int = MAX_NODES): List<Pair<N, UiNode>> {
        val out = ArrayList<Pair<N, UiNode>>()
        if (root == null) return out
        walk(root, a, intArrayOf(0, 0, width, height), 0, out, max, null)
        return out
    }

    private fun clip(b: IntArray, c: IntArray): IntArray? {
        val l = maxOf(b[0], c[0]); val t = maxOf(b[1], c[1]); val r = minOf(b[2], c[2]); val bt = minOf(b[3], c[3])
        return if (l >= r || t >= bt) null else intArrayOf(l, t, r, bt)
    }

    private fun <N : Any> labelOf(a: NodeAccess<N>, n: N): Pair<String, Boolean> {
        val text = a.text(n).trim(); val desc = a.desc(n).trim()
        val main = when {
            text.isNotEmpty() && desc.isNotEmpty() && desc != text -> "$text ($desc)"
            text.isNotEmpty() -> text
            else -> desc
        }
        if (main.isNotEmpty()) return main.take(120) to false
        val hint = a.hint(n).trim()
        return if (hint.isNotEmpty() && a.editable(n)) hint.take(80) to true else "" to false
    }

    /** Text of plain (non-clickable, non-form) descendants of a labelless clickable row, up to 3 levels down. */
    private fun <N : Any> complementary(a: NodeAccess<N>, n: N, depth: Int, words: MutableList<String>, sw: MutableList<N>): Boolean {
        if (depth > 3) return true
        for (i in 0 until a.childCount(n)) {
            val c = a.child(n, i) ?: continue
            if (!a.visible(c)) continue
            if (a.editable(c) || a.scrollable(c)) return false
            if (a.checkable(c)) { if (a.clickable(c)) return false; sw.add(c); continue }
            if (a.clickable(c)) return false
            val (lab, _) = labelOf(a, c)
            if (lab.isNotEmpty() && lab !in words) words.add(lab)
            if (!complementary(a, c, depth + 1, words, sw)) return false
        }
        return true
    }

    private fun <N : Any> walk(n: N, a: NodeAccess<N>, clipTo: IntArray, depth: Int, out: MutableList<Pair<N, UiNode>>, max: Int, parentLabel: String?) {
        if (out.size >= max || depth > MAX_DEPTH || !a.visible(n)) return
        val b = clip(a.bounds(n), clipTo) ?: return
        var (label, isHint) = labelOf(a, n)
        val clickable = a.clickable(n); val editable = a.editable(n); val scrollable = a.scrollable(n); val checkable = a.checkable(n)
        var checked: Boolean? = if (checkable) a.checked(n) else null
        var absorbed = false
        // a labelless clickable row: its plain text children (and a non-clickable switch inside) become its label/state
        if (label.isEmpty() && clickable && !editable && !scrollable && !checkable) {
            val words = ArrayList<String>(); val sw = ArrayList<N>()
            if (complementary(a, n, 0, words, sw) && (words.isNotEmpty() || sw.size == 1)) {
                label = words.take(3).joinToString(" · ").take(160)
                if (sw.size == 1) checked = a.checked(sw[0])
                absorbed = true
            }
        }
        val big = (b[2] - b[0]) > MIN_SIZE && (b[3] - b[1]) > MIN_SIZE
        val semantic = label.isNotEmpty() || clickable || editable || checkable || (scrollable && depth > 0)
        val redundant = parentLabel != null && label.isNotEmpty() && label == parentLabel && !clickable && !editable && !checkable
        var emitted: String? = null
        if (semantic && big && !redundant) {
            val cx = (b[0] + b[2]) / 2; val cy = (b[1] + b[3]) / 2
            val dup = label.isNotEmpty() && out.any { (_, u) -> u.label == label && kotlin.math.abs(u.cx - cx) < DUP_PX && kotlin.math.abs(u.cy - cy) < DUP_PX }
            if (!dup) {
                val role = ScreenTree.role(a.cls(n), editable, clickable).let { if (checkable && it == "item") "switch" else it }
                out.add(n to UiNode(out.size + 1, role, label, checked, a.enabled(n), a.selected(n), clickable, editable, scrollable,
                    b[0], b[1], b[2], b[3], isHint, false, a.viewId(n)))
                emitted = label
            }
        }
        if (absorbed) return
        for (i in 0 until a.childCount(n)) {
            if (out.size >= max) return
            val c = a.child(n, i) ?: continue
            walk(c, a, b, depth + 1, out, max, emitted ?: parentLabel)
        }
    }

    /** A px rectangle on the 0-1000 grid (what the model sees). */
    fun norm(v: Int, size: Int): Int = if (size <= 0) 0 else (v.toLong() * 1000 / size).toInt().coerceIn(0, 1000)

    /** 0-1000 grid back to px. */
    fun px(v: Int, size: Int): Int = (v.coerceIn(0, 1000).toLong() * size / 1000).toInt().coerceIn(0, maxOf(0, size - 1))

    /** One element as the model reads it: `[3] switch "Wi-Fi" on [40,120][960,180]`. */
    fun line(u: UiNode, w: Int, h: Int): String {
        val sb = StringBuilder("[").append(u.index).append("] ").append(u.role)
        if (u.label.isNotEmpty()) sb.append(if (u.hint) " hint \"" else " \"").append(u.label).append('"') else if (u.clickable) sb.append(" (no label)")
        u.checked?.let { sb.append(if (it) " on" else " off") }
        if (u.selected) sb.append(" selected")
        if (!u.enabled) sb.append(" disabled")
        if (u.scrollable) sb.append(" scrolls")
        if (u.clickable && (u.role == "text" || u.role == "img")) sb.append(" tappable")
        if (u.visual) sb.append(" (seen on screenshot)")
        sb.append(" [").append(norm(u.l, w)).append(',').append(norm(u.t, h)).append("][").append(norm(u.r, w)).append(',').append(norm(u.b, h)).append(']')
        return sb.toString()
    }

    /** The whole screen view. */
    fun format(app: String, pkg: String, nodes: List<UiNode>, w: Int, h: Int, shot: Boolean = false): String {
        val sb = StringBuilder("App: ").append(app.ifBlank { pkg.ifBlank { "unknown" } })
        if (pkg.isNotBlank() && pkg != app) sb.append(" (").append(pkg).append(')')
        sb.append(" · ").append(nodes.size).append(" elements\n")
        if (nodes.isEmpty()) sb.append(if (shot) "(The accessibility tree is empty here. A screenshot is attached: aim with target [x,y] on a 0-1000 grid.)\n"
            else "(nothing readable on screen)\n")
        nodes.forEach { sb.append(line(it, w, h)).append('\n') }
        return sb.toString()
    }

    /** The smallest element covering (x, y) in px; tree elements before visual ones (Artemis hit test). */
    fun hitTest(nodes: List<UiNode>, x: Int, y: Int): UiNode? =
        nodes.filter { !it.visual && it.contains(x, y) }.minByOrNull { it.area } ?: nodes.filter { it.visual && it.contains(x, y) }.minByOrNull { it.area }

    /**
     * What the screen is, for "did it change": app, labels, states and coarse positions (a scroll moves rows, so the
     * first rows' tops count, rounded to 1/40 of the screen).
     */
    fun signature(pkg: String, nodes: List<UiNode>, h: Int): Long {
        var x = 1125899906842597L
        fun mix(s: String) { for (c in s) x = 31 * x + c.code }
        mix(pkg)
        nodes.forEachIndexed { i, u ->
            mix(u.role); mix(u.label); mix(if (u.checked == true) "1" else if (u.checked == false) "0" else "-"); mix(if (u.enabled) "e" else "d"); mix(if (u.selected) "s" else "")
            if (i < 12) mix((norm(u.t, h) / 25).toString())
        }
        return x
    }
}

/** dHash of a small grey image (Artemis image_hash idea): 64 bits, compared by Hamming distance. Pure. */
object FlashHash {
    /** [grey] = w*h luminance 0..255, row-major. Downsamples to 9x8 and compares neighbours. */
    fun dhash(grey: IntArray, w: Int, h: Int): Long {
        if (w <= 0 || h <= 0 || grey.size < w * h) return 0L
        val s = IntArray(9 * 8)
        for (yy in 0 until 8) for (xx in 0 until 9) {
            val x0 = xx * w / 9; val x1 = maxOf(x0 + 1, (xx + 1) * w / 9)
            val y0 = yy * h / 8; val y1 = maxOf(y0 + 1, (yy + 1) * h / 8)
            var sum = 0L; var n = 0
            var y = y0
            while (y < y1 && y < h) { var x = x0; while (x < x1 && x < w) { sum += grey[y * w + x]; n++; x++ }; y++ }
            s[yy * 9 + xx] = if (n == 0) 0 else (sum / n).toInt()
        }
        var bits = 0L
        for (yy in 0 until 8) for (xx in 0 until 8) { bits = bits shl 1; if (s[yy * 9 + xx] > s[yy * 9 + xx + 1]) bits = bits or 1L }
        return bits
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Screens this far apart (bits of 64) count as changed. */
    const val CHANGED_BITS = 3
}
