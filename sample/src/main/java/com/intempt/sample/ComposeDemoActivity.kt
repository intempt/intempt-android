package com.intempt.sample

import android.graphics.Rect
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * One Compose screen, so the SDK's Compose hit-test has something real to hit.
 *
 * The screen is an untagged column holding a tagged button whose only child is an UNTAGGED label.
 * That is the shape the hit-test has to get right: the touch lands on the label, and the event
 * must carry the BUTTON's tag — the deepest tagged node — not `"unknown"` and not nothing.
 *
 * [ctaBounds] is the button's on-screen rectangle, published so the on-device test can aim a real
 * `MotionEvent` at its centre rather than guess pixel coordinates per device.
 */
class ComposeDemoActivity : AppCompatActivity() {
    @Volatile
    var ctaBounds: Rect? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText("Compose autocapture demo")
                Box(
                    modifier =
                        Modifier
                            .padding(top = 24.dp)
                            .size(width = 240.dp, height = 64.dp)
                            .background(Color(0xFF3F51B5))
                            .testTag(CTA_TAG)
                            .clickable { }
                            .onGloballyPositioned { coords ->
                                val b = coords.boundsInWindow()
                                ctaBounds = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("Tap me")
                }
            }
        }
    }

    companion object {
        const val CTA_TAG = "compose_cta"
    }
}
