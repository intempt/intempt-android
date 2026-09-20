package com.intempt.core.autocapture.composeHitTest

import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The View-only host, which is every host that does not use Compose.
 *
 * compose-ui is `compileOnly` in `:app`, so it is NOT on this test's runtime classpath — exactly the
 * situation the resolver must survive. The first call into [ComposeSemantics] raises
 * `NoClassDefFoundError`; the resolver must swallow it and answer "not Compose" without the host
 * seeing anything. This runs at the SDK's minSdk because that is where the fallback has to hold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ComposeTargetResolverTest {
    private fun view(): View = FrameLayout(ApplicationProvider.getApplicationContext())

    @Test
    fun `without compose on the classpath no view is a compose root`() {
        assertFalse(ComposeTargetResolver.isComposeRoot(view()))
    }

    @Test
    fun `without compose on the classpath no tag is resolved and nothing is thrown`() {
        val v = view()

        assertNull(ComposeTargetResolver.testTagAt(v, 10, 10))
        // A second call after the first LinkageError must be equally quiet.
        assertNull(ComposeTargetResolver.testTagAt(v, 10, 10))
        assertFalse(ComposeTargetResolver.isComposeRoot(v))
    }
}
