package com.example.doclens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Method

@RunWith(AndroidJUnit4::class)
class TextSelectionTest {

    private fun openDocument(activity: MainActivity) {
        val bytes = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("doclens-test.pdf").use { it.readBytes() }
        val method: Method = MainActivity::class.java
            .getDeclaredMethod("openBytes", ByteArray::class.java, String::class.java)
        method.isAccessible = true
        method.invoke(activity, bytes, "selection-test.pdf")
    }

    private fun field(target: Any, name: String): Any {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun pageRect(view: PdfPageView, index: Int): android.graphics.RectF {
        val method: Method = PdfPageView::class.java
            .getDeclaredMethod("pageRect", Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(view, index) as android.graphics.RectF
    }

    private fun textLines(view: PdfPageView): List<PdfPageView.TextLineBox> {
        @Suppress("UNCHECKED_CAST")
        return field(view, "textLines") as List<PdfPageView.TextLineBox>
    }

    private fun screenPoint(view: PdfPageView, word: PdfPageView.WordBox): FloatArray {
        val rect = pageRect(view, 0)
        val pdfWidth = field(view, "pdfWidth") as Float
        val pxPerPoint = rect.width() / pdfWidth
        return floatArrayOf(
            rect.left + (word.left + word.right) / 2f * pxPerPoint,
            rect.top + (word.top + word.bottom) / 2f * pxPerPoint,
        )
    }

    private fun firstWord(view: PdfPageView): PdfPageView.WordBox {
        val line = textLines(view).firstOrNull { line ->
            line.pageIndex == 0 && line.words.any { it.text.trim().length >= 3 }
        } ?: throw AssertionError("expected indexed words on page 1")
        return line.words.first { it.text.trim().length >= 3 }
    }

    private fun waitForText(scenario: ActivityScenario<MainActivity>) {
        repeat(50) {
            var count = 0
            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                count = textLines(view).size
            }
            if (count > 0) return
            Thread.sleep(200)
        }
        throw AssertionError("text index did not load")
    }

    @Test
    fun longPressSelectsWordAndCopiesToClipboard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            waitForText(scenario)

            lateinit var point: FloatArray
            lateinit var expected: String
            var downTime = 0L

            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                val word = firstWord(view)
                expected = word.text.trim()
                point = screenPoint(view, word)
                downTime = SystemClock.uptimeMillis()
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("clear", ""))
                val down = MotionEvent.obtain(
                    downTime, downTime, MotionEvent.ACTION_DOWN,
                    point[0], point[1], 0)
                view.dispatchTouchEvent(down)
                down.recycle()
            }

            Thread.sleep(700)

            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                val now = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(
                    downTime, now, MotionEvent.ACTION_UP,
                    point[0], point[1], 0)
                view.dispatchTouchEvent(up)
                up.recycle()

                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                assertTrue("expected '$expected' in clipboard, got '$copied'",
                    copied.contains(expected))
            }
        }
    }

    @Test
    fun selectionCanSpanMultipleTextChunks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { openDocument(it) }
            Thread.sleep(700)

            scenario.onActivity { activity ->
                val view: PdfPageView = activity.findViewById(R.id.page_image)
                val first = PdfPageView.WordBox("Hello", 30f, 100f, 80f, 114f, 0)
                val second = PdfPageView.WordBox("world", 86f, 100f, 150f, 114f, 0)
                val third = PdfPageView.WordBox("again", 30f, 130f, 100f, 144f, 1)
                view.setTextLines(listOf(
                    PdfPageView.TextLineBox(0, 30f, 100f, 150f, 114f,
                        "Hello world", listOf(first, second)),
                    PdfPageView.TextLineBox(0, 30f, 130f, 100f, 144f,
                        "again", listOf(third)),
                ))

                val firstPoint = screenPoint(view, first)
                val secondPoint = screenPoint(view, second)
                val thirdPoint = screenPoint(view, third)

                val begin: Method = PdfPageView::class.java
                    .getDeclaredMethod("beginSelection",
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                begin.isAccessible = true
                begin.invoke(view, firstPoint[0], firstPoint[1])

                val update: Method = PdfPageView::class.java
                    .getDeclaredMethod("updateSelection",
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                update.isAccessible = true
                update.invoke(view, secondPoint[0], secondPoint[1])
                update.invoke(view, thirdPoint[0], thirdPoint[1])

                val selected: Method = PdfPageView::class.java
                    .getDeclaredMethod("selectedText")
                selected.isAccessible = true
                val text = selected.invoke(view) as String
                assertTrue("selection must include all chunks: $text",
                    text.contains("Hello") && text.contains("world") && text.contains("again"))
                assertTrue("line breaks must separate text chunks: $text", text.contains('\n'))
            }
        }
    }
}
