package com.example.doclens

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
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
class ContinuousScrollTest {

    private fun openDocument(activity: MainActivity) {
        val bytes = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("doclens-test.pdf").use { it.readBytes() }
        val method: Method = MainActivity::class.java
            .getDeclaredMethod("openBytes", ByteArray::class.java, String::class.java)
        method.isAccessible = true
        method.invoke(activity, bytes, "scroll-test.pdf")
    }

    private fun field(target: Any, name: String): Any {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun pageRect(view: PdfPageView, index: Int): RectF {
        val method: Method = PdfPageView::class.java
            .getDeclaredMethod("pageRect", Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(view, index) as RectF
    }

    private fun tap(view: PdfPageView, atTime: Long, x: Float, y: Float) {
        val down = MotionEvent.obtain(atTime, atTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(atTime, atTime + 40, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun drag(view: PdfPageView, fromX: Float, toX: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, fromX, y, 0)
        view.dispatchTouchEvent(down)
        down.recycle()
        for (step in 1..8) {
            val x = fromX + (toX - fromX) * step / 8f
            val move = MotionEvent.obtain(now, now + step * 16L,
                MotionEvent.ACTION_MOVE, x, y, 0)
            view.dispatchTouchEvent(move)
            move.recycle()
        }
        val up = MotionEvent.obtain(now, now + 150L, MotionEvent.ACTION_UP, toX, y, 0)
        view.dispatchTouchEvent(up)
        up.recycle()
    }

    private fun zoomTo(view: PdfPageView, level: Float) {
        val method: Method = PdfPageView::class.java.getDeclaredMethod(
            "setZoom", Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(view, level, 800f, 1200f)
    }

    @Test
    fun pagesFormOneContinuousStrip() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(700)

            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                val pageCount = field(view, "pageCount") as Int
                assertTrue("multi-page fixture expected, got $pageCount", pageCount >= 2)
                assertEquals(0f, field(view, "scrollY") as Float, 0.01f)

                val first = pageRect(view, 0)
                val second = pageRect(view, 1)
                assertEquals("first page must start at the top edge", 0f, first.top, 0.01f)
                assertTrue("page 2 must be laid out below page 1",
                    second.top >= first.bottom)
                assertEquals("pages must share one horizontal alignment",
                    first.left, second.left, 0.01f)

                val gapMethod: Method = PdfPageView::class.java.getDeclaredMethod("gapPx")
                gapMethod.isAccessible = true
                val gap = gapMethod.invoke(view) as Float
                assertTrue("page gap must stay slim, got ${gap}px",
                    gap <= 8f * view.resources.displayMetrics.density)
            }
        }
    }

    @Test
    fun scrollingToPageUpdatesViewportAndPageIndicator() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(700)

            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                view.scrollToPage(1)
            }
            Thread.sleep(900)

            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                assertEquals(1, field(view, "currentPage") as Int)
                assertTrue("viewport must scroll down", (field(view, "scrollY") as Float) > 0f)
                val label: android.widget.TextView = activity.findViewById(R.id.page_label)
                assertTrue("page label must follow scrolling: ${label.text}",
                    label.text.toString().startsWith("2"))
            }
        }
    }

    @Test
    fun singleTapRevealsTopActionsAndCenteredTray() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(700)

            scenario.onActivity { activity ->
                assertEquals(View.GONE,
                    activity.findViewById<View>(R.id.top_bar).visibility)
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                tap(view, SystemClock.uptimeMillis(), 500f, 900f)
            }
            Thread.sleep(500)

            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.top_bar).visibility)
                assertEquals(View.VISIBLE,
                    activity.findViewById<View>(R.id.tools_panel).visibility)
                assertTrue("redo must be present in the top bar",
                    activity.findViewById<View>(R.id.redo_button) != null)
            }
        }
    }

    @Test
    fun zoomedPageCanBePannedHorizontally() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(700)

            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                zoomTo(view, 2.5f)
                val before = field(view, "panX") as Float
                drag(view, 850f, 350f, 1200f)
                val after = field(view, "panX") as Float
                assertTrue("horizontal drag must pan the zoomed page: $before -> $after",
                    after < before - 100f)
            }
        }
    }

    @Test
    fun undoAndRedoRestoreAnnotationHistory() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(300)

            scenario.onActivity { activity ->
                val add: Method = MainActivity::class.java
                    .getDeclaredMethod("addAnnotation", Annotation::class.java)
                add.isAccessible = true
                val annotation = Annotation.path(Annotation.Type.PEN, 0, Color.RED, 2f)
                annotation.points.add(PointF(10f, 10f))
                annotation.points.add(PointF(20f, 20f))
                add.invoke(activity, annotation)

                val annotations = field(activity, "annotations") as MutableList<*>
                assertEquals(1, annotations.size)

                invoke(activity, "undo")
                assertEquals(0, (field(activity, "annotations") as List<*>).size)

                invoke(activity, "redo")
                assertEquals(1, (field(activity, "annotations") as List<*>).size)
            }
        }
    }

    private fun invoke(activity: MainActivity, name: String) {
        val method: Method = MainActivity::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(activity)
    }
}
