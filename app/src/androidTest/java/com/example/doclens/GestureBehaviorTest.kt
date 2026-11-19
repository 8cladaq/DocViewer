package com.example.doclens

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Method

@RunWith(AndroidJUnit4::class)
class GestureBehaviorTest {

    private fun openDocument(activity: MainActivity) {
        val bytes = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("doclens-test.pdf").use { it.readBytes() }
        val method: Method = MainActivity::class.java
            .getDeclaredMethod("openBytes", ByteArray::class.java, String::class.java)
        method.isAccessible = true
        method.invoke(activity, bytes, "gesture-test.pdf")
    }

    private fun zoom(activity: MainActivity, level: Float) {
        val view: PdfPageView = activity.findViewById(R.id.page_image)
        val method: Method = PdfPageView::class.java.getDeclaredMethod(
            "setZoom", Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(view, level, 500f, 900f)
    }

    private fun zoomLevel(activity: MainActivity): Float {
        val view: PdfPageView = activity.findViewById(R.id.page_image)
        val field = PdfPageView::class.java.getDeclaredField("zoom")
        field.isAccessible = true
        return field.get(view) as Float
    }

    private fun tap(activity: MainActivity, atTime: Long, x: Float, y: Float) {
        val view: PdfPageView = activity.findViewById(R.id.page_image)
        val down = MotionEvent.obtain(atTime, atTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(atTime, atTime + 40, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    @Test
    fun textLinesAreIndexedForHighlightSnapping() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(1200)

            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                val field = PdfPageView::class.java.getDeclaredField("textLines")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val lines = field.get(view) as List<PdfPageView.TextLineBox>
                assertTrue("expected detected text lines, got ${lines.size}", lines.isNotEmpty())
            }
        }
    }

    @Test
    fun doubleTapZoomsInAndHidesTools() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(400)

            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.tools_panel).visibility = View.VISIBLE
                val now = SystemClock.uptimeMillis()
                tap(activity, now, 500f, 900f)
                tap(activity, now + 120, 500f, 900f)
            }
            Thread.sleep(500)

            scenario.onActivity { activity ->
                assertTrue("double tap must zoom in", zoomLevel(activity) > 1.5f)
                assertEquals("tools must hide when zoomed in",
                    View.GONE, activity.findViewById<View>(R.id.tools_panel).visibility)
            }
        }
    }

    @Test
    fun singleTapDoesNotToggleToolsWhileZoomedIn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(400)

            scenario.onActivity { activity ->
                zoom(activity, 2.5f)
                activity.findViewById<View>(R.id.tools_panel).visibility = View.VISIBLE
                tap(activity, SystemClock.uptimeMillis(), 500f, 900f)
            }
            Thread.sleep(500)

            scenario.onActivity { activity ->
                assertTrue(zoomLevel(activity) > 1.5f)
                assertEquals("single tap must be ignored while zoomed in",
                    View.VISIBLE, activity.findViewById<View>(R.id.tools_panel).visibility)
            }
        }
    }

    @Test
    fun singleTapWorksAgainAfterZoomingOut() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(400)

            scenario.onActivity { activity ->
                zoom(activity, 2.5f)
                activity.findViewById<View>(R.id.tools_panel).visibility = View.VISIBLE
                zoom(activity, 1f)
                tap(activity, SystemClock.uptimeMillis(), 500f, 900f)
            }
            Thread.sleep(500)

            scenario.onActivity { activity ->
                assertEquals("single tap must toggle tools after zooming out",
                    View.GONE, activity.findViewById<View>(R.id.tools_panel).visibility)
            }
        }
    }
}
