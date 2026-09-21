package com.intempt.core.autocapture.composeHitTest

import android.view.View
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * The ONLY file in the SDK that names a Compose type.
 *
 * compose-ui is `compileOnly`, so in a View-based host none of these classes exist at runtime and
 * loading this object throws a [LinkageError]. That is the contract: nothing may call into here
 * except [ComposeTargetResolver], which catches that error once and never tries again. Do not
 * import anything from `androidx.compose` anywhere else in `:app`.
 *
 * `AndroidComposeView` — the single View Compose renders a hierarchy into — is `internal` to
 * compose-ui, but it implements the public [ViewRootForTest], which exposes the `SemanticsOwner`.
 * The UNMERGED tree is walked: on the merged tree a clickable swallows its descendants' semantics,
 * so a tag placed on a `Text` inside a `Button` would be invisible.
 */
internal object ComposeSemantics {
    fun isComposeRoot(view: View): Boolean = view is ViewRootForTest

    /** ([localX], [localY]) are relative to [view]'s top-left, which is the semantics root's origin. */
    fun testTagAt(
        view: View,
        localX: Float,
        localY: Float,
    ): String? {
        val root = (view as? ViewRootForTest)?.semanticsOwner?.unmergedRootSemanticsNode ?: return null
        return deepestTestTagAt(SemanticsHitNode(root), localX, localY)
    }

    private class SemanticsHitNode(private val node: SemanticsNode) : HitNode {
        override val testTag: String?
            get() = node.config.getOrNull(SemanticsProperties.TestTag)

        override fun contains(
            x: Float,
            y: Float,
        ): Boolean {
            val b = node.boundsInRoot
            return x >= b.left && x < b.right && y >= b.top && y < b.bottom
        }

        override val children: List<HitNode>
            get() = node.children.map { SemanticsHitNode(it) }
    }
}
