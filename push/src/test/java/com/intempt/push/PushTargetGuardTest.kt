package com.intempt.push

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** INT-3893 / INT-3931. Robolectric because android.net.Uri is a stub on the plain JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PushTargetGuardTest {
    @Test
    fun deniedSchemesAreRejected() {
        listOf(
            "intent:#Intent;component=com.evil/.Main;end",
            "INTENT:#Intent;end",
            " javascript:alert(1)",
            "javascript:alert(1)",
            "file:///data/data/x/databases/db",
            "content://com.evil.provider/x",
            "android-app://com.evil",
            "",
            "   ",
            null,
            "no-scheme-at-all",
        ).forEach { assertFalse("should deny: $it", PushTargetGuard.isAllowed(it)) }
    }

    @Test
    fun webAndCustomSchemeDeepLinksAreAllowed() {
        listOf("https://example.com/a?b=1", "http://example.com", "myapp://product/42", "HTTPS://example.com")
            .forEach { assertTrue("should allow: $it", PushTargetGuard.isAllowed(it)) }
    }

    @Test
    fun deniedUrlFallsBackToLaunchIntent() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val target = PushTargetGuard.buildTarget(ctx, "intent:#Intent;component=com.evil/.Main;end")
        assertFalse(target.action == Intent.ACTION_VIEW)
        assertEquals(null, target.data)
    }

    @Test
    fun allowedUrlBecomesViewIntent() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val target = PushTargetGuard.buildTarget(ctx, "myapp://product/42")
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals("myapp://product/42", target.data.toString())
    }

    @Test
    fun viewIntentIsBrowsableAndCustomSchemeIsPinnedToTheHostPackage() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val web = PushTargetGuard.buildTarget(ctx, "https://example.com/a")
        assertTrue(web.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(null, web.`package`)
        val deep = PushTargetGuard.buildTarget(ctx, "myapp://product/42")
        assertTrue(deep.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertEquals(ctx.packageName, deep.`package`)
    }

    @Test
    fun dispatcherIgnoresAnInjectedIntentExtraAndDeniedUrl() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val evil = Intent().setClassName("com.evil", "com.evil.Main")
        val dispatcherIntent =
            Intent(ctx, NotificationDispatcherActivity::class.java)
                .putExtra("intent_test", evil)
                .putExtra(PushTargetGuard.EXTRA_TARGET_URL, "javascript:alert(1)")
        val target = PushTargetGuard.resolveDispatchTarget(ctx, dispatcherIntent)
        assertFalse(target.action == Intent.ACTION_VIEW)
        assertEquals(null, target.component?.packageName?.takeIf { it == "com.evil" })
    }

    @Test
    fun dispatcherIgnoresInjectedIntentWhenNoUrl() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val evil = Intent().setClassName("com.evil", "com.evil.Main")
        val dispatcherIntent = Intent(ctx, NotificationDispatcherActivity::class.java).putExtra("intent_test", evil)
        val target = PushTargetGuard.resolveDispatchTarget(ctx, dispatcherIntent)
        assertEquals(null, target.component?.packageName?.takeIf { it == "com.evil" })
    }
}
