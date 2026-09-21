package com.intempt.core.autocapture.composeHitTest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Compose hit-test's one decision, over hand-built trees.
 *
 * Shared by `:app` and `:mutation` (see mutation/build.gradle.kts): `deepestTestTagAt` names no
 * Compose type precisely so PIT can mutate it. Each test below goes red if the line it pins is
 * inverted or deleted — the sibling order, the blank check, the containment test and the
 * child-before-self preference are each covered by exactly one assertion that only they satisfy.
 */
class SemanticsHitTest {
    /** Axis-aligned bounds, left/top inclusive, right/bottom exclusive — as Compose's Rect is used. */
    private class Node(
        override val testTag: String?,
        private val left: Float,
        private val top: Float,
        private val right: Float,
        private val bottom: Float,
        override val children: List<HitNode> = emptyList(),
    ) : HitNode {
        override fun contains(
            x: Float,
            y: Float,
        ): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    private fun root(vararg children: HitNode) = Node(null, 0f, 0f, 1000f, 2000f, children.toList())

    @Test
    fun `an untagged tree yields null`() {
        assertNull(deepestTestTagAt(root(Node(null, 0f, 0f, 100f, 100f)), 50f, 50f))
    }

    @Test
    fun `a tagged leaf under the point is reported`() {
        val tree = root(Node("cta", 100f, 100f, 300f, 200f))

        assertEquals("cta", deepestTestTagAt(tree, 150f, 150f))
    }

    @Test
    fun `a tagged node the point misses is not reported`() {
        val tree = root(Node("cta", 100f, 100f, 300f, 200f))

        assertNull(deepestTestTagAt(tree, 350f, 150f))
    }

    /** The Button-wraps-Text case: the tag is on the parent, the touch lands on the untagged child. */
    @Test
    fun `an untagged child falls back to its tagged ancestor`() {
        val tree = root(Node("button", 0f, 0f, 400f, 100f, listOf(Node(null, 10f, 10f, 200f, 90f))))

        assertEquals("button", deepestTestTagAt(tree, 50f, 50f))
    }

    /** Both tagged: the more specific one, deeper in the tree, wins. */
    @Test
    fun `a tagged child beats its tagged ancestor`() {
        val tree = root(Node("button", 0f, 0f, 400f, 100f, listOf(Node("label", 10f, 10f, 200f, 90f))))

        assertEquals("label", deepestTestTagAt(tree, 50f, 50f))
    }

    /** Overlapping siblings: the LATER one is drawn on top and is what the finger touched. */
    @Test
    fun `the last overlapping sibling wins`() {
        val tree = root(Node("under", 0f, 0f, 400f, 400f), Node("over", 100f, 100f, 300f, 300f))

        assertEquals("over", deepestTestTagAt(tree, 200f, 200f))
        assertEquals("under", deepestTestTagAt(tree, 50f, 50f))
    }

    /** A child outside its parent's bounds is still hit-tested — Compose semantics do not clip. */
    @Test
    fun `a child is tested even when its parent misses the point`() {
        val tree = root(Node("parent", 0f, 0f, 100f, 100f, listOf(Node("child", 500f, 500f, 600f, 600f))))

        assertEquals("child", deepestTestTagAt(tree, 550f, 550f))
    }

    @Test
    fun `a blank tag is treated as no tag`() {
        val tree = root(Node("outer", 0f, 0f, 400f, 400f, listOf(Node("   ", 0f, 0f, 400f, 400f))))

        assertEquals("outer", deepestTestTagAt(tree, 10f, 10f))
        assertNull(deepestTestTagAt(root(Node("", 0f, 0f, 400f, 400f)), 10f, 10f))
    }
}
