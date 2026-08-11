package com.intempt.sample

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
import com.intempt.core.Intempt

/**
 * Exercises the public API by hand. Built with plain views rather than Compose so this
 * module stays a test of the SDK and not of a UI toolchain.
 *
 * Nothing here asserts anything — assertions live in SdkRunsLocallyTest. This screen is for
 * watching the SDK work on a device: run it with `adb logcat -s Intempt IntemptQueue`.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            Intempt.track("Sample button tapped", mapOf("source" to "sample-app"))
        }
        button(root, "identify") {
            Intempt.identify(
                userId = "sample-user-1",
                userAttributes = mapOf("plan" to "free"),
            )
        }
        button(root, "record") {
            Intempt.record(
                eventTitle = "Sample record",
                userId = "sample-user-1",
                data = mapOf("step" to "checkout"),
            )
        }
        button(root, "productView") { Intempt.productView("sku-123") }
        button(root, "logOut (rotates profileId)") { Intempt.logOut() }

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
