package com.intempt.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.intempt.core.Intempt
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product

/**
 * Exercises the public API by hand. Built with plain views rather than Compose so this
 * module stays a test of the SDK and not of a UI toolchain.
 *
 * Nothing here asserts anything — assertions live in SdkRunsLocallyTest. This screen is for
 * watching the SDK work on a device: run it with `adb logcat -s Intempt IntemptQueue`.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var log: TextView

    /**
     * Android 13+ will not show a notification without this, and it is a runtime prompt rather
     * than a manifest declaration alone. The SDK contributes the POST_NOTIFICATIONS permission
     * through its own manifest, so a consuming app does not declare it — but only the app can
     * ask the user, which is why this lives here and not in the SDK.
     *
     * Below API 33 notifications are granted at install time and this is a no-op.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }

        root.addView(
            TextView(this).apply {
                text = "Intempt SDK sample"
                textSize = 20f
            },
        )

        // Autocapture reads text from input fields. The second field is a password, and it
        // must reach the wire as [masked] — that is the privacy fix this branch made, and
        // it is easier to trust when you can see both fields behave differently in logcat.
        root.addView(
            EditText(this).apply {
                hint = "Email (captured)"
                inputType = InputType.TYPE_CLASS_TEXT
            },
        )
        root.addView(
            EditText(this).apply {
                hint = "Password (must be masked)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
        )

        button(root, "track") {
            // Typed values, so the sample demonstrates the 3.0 shape a customer copies. `seats`
            // reaching the platform as a number rather than "3" is the point of the change.
            Intempt.track(
                "Sample button tapped",
                IntemptValue.mapOf(mapOf("source" to "sample-app", "seats" to 3, "trial" to false)),
            )
        }
        button(root, "identify") {
            // Deliberately the obvious call, with no eventTitle. This used to be rejected
            // and silently dropped; it now queues an event named "Identify". Leaving it in
            // this shape means the sample fails visibly if that regresses.
            Intempt.identify(
                userId = "sample-user-1",
                userAttributes = IntemptValue.mapOf(mapOf("plan" to "free")),
            )
        }
        button(root, "group") {
            // Also titleless, to cover the sibling bug: this arrived named "Identify".
            Intempt.group(
                accountId = "sample-account-1",
                accountAttributes = IntemptValue.mapOf(mapOf("tier" to "smb")),
            )
        }
        button(root, "record") {
            Intempt.record(
                eventTitle = "Sample record",
                userId = "sample-user-1",
                data = IntemptValue.mapOf(mapOf("step" to "checkout")),
            )
        }
        button(root, "productView") { Intempt.productView("sku-123") }
        button(root, "productOrdered") {
            Intempt.productOrdered(listOf(Product("sku-123", 2), Product("sku-456", 1)))
        }
        button(root, "consent accept (opts in)") {
            Intempt.consent(ConsentAction.ACCEPT, System.currentTimeMillis() + 86_400_000)
        }
        button(root, "consent reject (opts out + clears queue)") {
            Intempt.consent(ConsentAction.REJECT, System.currentTimeMillis() + 86_400_000)
        }
        button(root, "flush") {
            Intempt.flush { delivered -> runOnUiThread { log.append("  flush delivered $delivered\n") } }
        }
        button(root, "logOut (rotates profileId, keeps queue)") { Intempt.logOut() }
        button(root, "reset (rotates profileId, empties queue)") { Intempt.reset() }
        button(root, "show ids") {
            log.append("  profileId=${Intempt.getProfileId()} sessionId=${Intempt.getSessionId()}\n")
            log.append("  optedIn=${Intempt.isOptedIn()} flushInterval=${Intempt.flushInterval}s\n")
        }

        log =
            TextView(this).apply {
                text = "\nCalls made:\n"
                setPadding(0, 32, 0, 0)
            }
        root.addView(log)

        setContentView(
            ScrollView(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
    }

    private fun button(
        parent: LinearLayout,
        label: String,
        onTap: () -> Unit,
    ) {
        parent.addView(
            Button(parent.context).apply {
                text = label
                setOnClickListener {
                    // The SDK is not allowed to take the host app down with it. A sample that
                    // crashes on a bad call would hide exactly the failure worth seeing, so
                    // anything thrown is shown on screen and logged instead.
                    val outcome =
                        try {
                            onTap()
                            "ok"
                        } catch (t: Throwable) {
                            Log.e("IntemptSample", "$label threw", t)
                            "threw ${t.javaClass.simpleName}: ${t.message}"
                        }
                    log.append("$label -> $outcome\n")
                }
            },
        )
    }
}
