package com.example.doclens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Field

@RunWith(AndroidJUnit4::class)
class LiveSigningTest {

    @Test
    fun signatureIsVisibleWhileFingerIsStillDown() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bytes = InstrumentationRegistry.getInstrumentation()
                    .context.assets.open("doclens-test.pdf").use { it.readBytes() }
                val open = MainActivity::class.java
                    .getDeclaredMethod("openBytes", ByteArray::class.java, String::class.java)
                open.isAccessible = true
                open.invoke(activity, bytes, "live-sign.pdf")
            }
            Thread.sleep(500)

            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                view.setTool(PdfPageView.Tool.SIGNATURE)

                val start = SystemClock.uptimeMillis()
                dispatch(view, MotionEvent.ACTION_DOWN, 300f, 600f, start, start)
                dispatch(view, MotionEvent.ACTION_MOVE, 380f, 540f, start, start + 40)
                dispatch(view, MotionEvent.ACTION_MOVE, 470f, 620f, start, start + 80)
                dispatch(view, MotionEvent.ACTION_MOVE, 560f, 530f, start, start + 120)

                val field: Field = PdfPageView::class.java.getDeclaredField("active")
                field.isAccessible = true
                val active = field.get(view) as Annotation?
                assertNotNull("path must be in progress before finger lift", active)
                assertTrue("path must contain live points", (active?.points?.size ?: 0) >= 3)

                val bitmap = Bitmap.createBitmap(
                    Math.max(1, view.width), Math.max(1, view.height), Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))

                var bluePixels = 0
                for (y in 400 until Math.min(900, bitmap.height) step 2) {
                    for (x in 200 until Math.min(700, bitmap.width) step 2) {
                        val c = bitmap.getPixel(x, y)
                        if (Color.blue(c) > 180 && Color.red(c) < 90 && Color.green(c) < 150) {
                            bluePixels++
                        }
                    }
                }
                bitmap.recycle()
                assertTrue("signature must be drawn before finger lift, found $bluePixels pixels",
                    bluePixels > 0)
            }
        }
    }

    private fun dispatch(view: View, action: Int, x: Float, y: Float,
                         downTime: Long, eventTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }
}
