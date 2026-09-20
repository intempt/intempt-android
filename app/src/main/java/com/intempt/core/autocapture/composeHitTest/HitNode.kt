package com.intempt.core.autocapture.composeHitTest

/**
 * The shape of a semantics tree, reduced to what the hit-test needs.
 *
 * Deliberately not a Compose type. `SemanticsNode` lives in compose-ui, which this SDK sees as
 * `compileOnly` and which the `:mutation` module cannot compile at all — so the one decision in the
 * Compose hit-test (which node supplies `targetId`) is written against this interface and tested
 * with hand-built trees, while `ComposeSemantics` adapts the real tree to it in a few lines.
 */
internal interface HitNode {
    /** The node's `testTag`, or null when it has none. */
    val testTag: String?

    /** Whether ([x], [y]) — in the tree root's coordinate space — falls inside this node. */
    fun contains(
        x: Float,
        y: Float,
    ): Boolean

    /** Direct children in layout order: a later sibling is drawn on top of an earlier one. */
    val children: List<HitNode>
}

/**
 * The `testTag` autocapture reports for a touch at ([x], [y]): the DEEPEST tagged node whose bounds
 * contain the point, preferring the sibling drawn on top when several overlap.
 *
 * Why deepest: an integrator tags the thing they want to see in the event. A `Button` with a tag
 * wraps a `Text` without one — the Button's tag is the answer, and the Text contributes nothing.
 * But if the Text also carries a tag, the integrator said something more specific, and that wins.
 *
 * Why the walk does not stop at an untagged ancestor whose bounds miss the point: Compose does not
 * clip children to their parent's semantics bounds, so a child can be hit while its parent is not.
 * Every subtree is visited and only a node's OWN bounds decide whether its OWN tag is a candidate.
 *
 * A blank tag is treated as absent — `Modifier.testTag("")` is a mistake, not an identifier, and an
 * empty `targetId` would be indistinguishable from a field the SDK forgot to fill.
 */
internal fun deepestTestTagAt(
    node: HitNode,
    x: Float,
    y: Float,
): String? {
    for (child in node.children.asReversed()) {
        val fromChild = deepestTestTagAt(child, x, y)
        if (fromChild != null) return fromChild
    }
    return if (node.contains(x, y)) node.testTag?.takeIf { it.isNotBlank() } else null
}
