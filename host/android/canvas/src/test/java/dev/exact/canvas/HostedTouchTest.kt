package dev.exact.canvas

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, shadows = [HostedTouchTest.Engine::class],
    instrumentedPackages = ["dev.exact.canvas.Native"])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HostedTouchTest {
    @Implements(Native::class)
    class Engine {
        companion object {
            var reply = intArrayOf()
            var beforeReply: (() -> Unit)? = null
            val touches = ArrayList<Int>()
            val scrolls = ArrayList<Float>()
            var offset = 0f
            var takesLongPress = false
            @Implementation @JvmStatic fun __staticInitializer__() {}
            @Implementation @JvmStatic fun under(x: Float, y: Float): IntArray {
                val answer = reply.copyOf()
                beforeReply?.also { beforeReply = null }?.invoke()
                return answer
            }
            @Implementation @JvmStatic fun touch(action: Int, x: Float, y: Float, tool: Int) {
                touches.add(action)
                if (action == 4 && takesLongPress) reply[reply.lastIndex]++
            }
            @Implementation @JvmStatic fun scrolled(id: Int, x: Float, y: Float, rev: Int) { scrolls.add(y); offset += y }
            @Implementation @JvmStatic fun scrollers(): IntArray? =
                if (scrolls.isEmpty()) null else intArrayOf(9, 2, 0, 0, offset.toBits(), 0, 0, 0, 500f.toBits())
            @Implementation @JvmStatic fun nextDue() = -1.0
        }
    }

    private class Fixture(val accepts: Boolean = true, scroller: Boolean = false, claims: Int = 0) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        val events = ArrayList<Int>()
        val points = ArrayList<Pair<Float, Float>>()
        val pointers = ArrayList<List<Int>>()
        val native = object : NativeModule {
            override fun create(context: Context, props: JSONObject): View = object : View(context) {
                override fun onTouchEvent(e: MotionEvent): Boolean {
                    events.add(e.actionMasked); points.add(e.x to e.y)
                    pointers.add((0 until e.pointerCount).map { e.getPointerId(it) })
                    return accepts
                }
            }
            override fun update(view: View, props: JSONObject) {}
        }
        val natives = Natives(activity, File("."), mapOf("test-map" to native))
        val view = ExactView(activity, false) {}.also { it.started = true; it.natives = natives }
        val parent = FrameLayout(activity).also { it.addView(natives); it.addView(view) }
        init {
            Engine.touches.clear(); Engine.scrolls.clear(); Engine.offset = 0f; Engine.takesLongPress = false; Engine.beforeReply = null
            natives.ensure(7, 2, 200, 300, "{\"module\":\"test-map\",\"slot\":2,\"generation\":3}", 0)
            Engine.reply = intArrayOf(claims, if (scroller) 1 else 0, 0) +
                (if (scroller) intArrayOf(9, 2, 0, 0, 0, 0, 500f.toBits(), 0, 0, 300f.toBits(), 600f.toBits()) else intArrayOf()) +
                intArrayOf(7, 10f.toBits(), 20f.toBits(), 200f.toBits(), 300f.toBits(), 2, 3, 0)
        }
        fun fingers(action: Int, ids: IntArray, ys: FloatArray, time: Long) {
            val properties = ids.map { id -> MotionEvent.PointerProperties().also { it.id = id; it.toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val coords = ys.map { y -> MotionEvent.PointerCoords().also { it.x = 100f; it.y = y; it.pressure = 1f; it.size = 1f } }.toTypedArray()
            val e = MotionEvent.obtain(100, time, action, ids.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            assertTrue(view.dispatchTouchEvent(e)); e.recycle()
        }
        fun touch(action: Int, x: Float = 100f, y: Float = 100f, time: Long = 100L) {
            val e = MotionEvent.obtain(100L, time, action, x, y, 0)
            assertTrue(view.dispatchTouchEvent(e)); e.recycle()
        }
    }

    private fun field(f: Fixture, multiline: Boolean = false): Fields {
        val fields = Fields(f.activity).also { it.adopted(f.view); f.view.fields = it; f.parent.addView(it) }
        val value = (if (multiline) (1..40).joinToString("\n") { "line $it" } else "").toByteArray()
        val words = IntArray((value.size + 3) / 4)
        value.forEachIndexed { i, b -> words[i / 4] = words[i / 4] or ((b.toInt() and 255) shl (8 * (i % 4))) }
        val look = IntArray(18).also { it[0] = if (multiline) 1 else 0; it[4] = -1; it[11] = 16f.toBits() }
        fields.told(intArrayOf(1, 8, 1, 1, 0, 220f.toBits(), 20f.toBits(), 200f.toBits(), 200f.toBits(),
            0, 0, (-1f).toBits(), 0, 0, value.size) + words + look + IntArray(4))
        Engine.reply[2] = 8
        return fields
    }

    @Test fun verticalDragScrollsThePageWithoutScrollingMultilineText() {
        val f = Fixture(scroller = true)
        val fields = field(f, multiline = true)
        val text = fields.text(8)!!
        // Use Android's movement method and editable text, not a consuming touch listener.
        f.controller.setup()
        f.activity.setContentView(f.parent)
        f.parent.layout(0, 0, 600, 800)
        assertTrue(text.requestFocus())
        text.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        text.layout(0, 0, 200, 200)
        assertTrue(text.length() > 100)
        assertTrue(text.layout.height > text.height)
        val before = text.scrollY
        f.touch(0, x = 250f, y = 180f)
        f.touch(2, x = 250f, y = 130f, time = 120)
        f.touch(2, x = 250f, y = 80f, time = 140)
        f.touch(1, x = 250f, y = 80f, time = 160)
        assertEquals("the field must not apply the intercepted move", before, text.scrollY)
        assertEquals(listOf(50f), Engine.scrolls)
        f.view.running(false, false)
        f.controller.pause().stop().destroy()
    }

    @Test fun disallowOnTheCrossingMoveIsTooLateToKeepAFieldDrag() {
        val f = Fixture(scroller = true)
        val fields = field(f)
        val heard = ArrayList<Int>()
        fields.text(8)!!.setOnTouchListener { _, e ->
            heard.add(e.actionMasked)
            if (e.actionMasked == 2) fields.requestDisallowInterceptTouchEvent(true)
            true
        }
        f.touch(0, x = 250f)
        f.touch(2, x = 250f, y = 60f, time = 120)
        f.touch(2, x = 250f, y = 20f, time = 140)
        f.touch(1, x = 250f, y = 20f, time = 160)
        assertEquals(listOf(0, 3), heard)
        assertEquals(listOf(40f), Engine.scrolls)
        f.view.running(false, false)
    }

    @Test fun aPinchWithBothFingersMovingStaysWithTheMap() {
        for (late in listOf(false, true)) {
            val f = Fixture(scroller = true)
            val pinch = {
                f.fingers(5 or (1 shl 8), intArrayOf(0, 1), floatArrayOf(100f, 120f), 110)
                f.fingers(2, intArrayOf(0, 1), floatArrayOf(60f, 160f), 130)
                f.fingers(2, intArrayOf(0, 1), floatArrayOf(20f, 200f), 150)
                f.fingers(6 or (1 shl 8), intArrayOf(0, 1), floatArrayOf(20f, 200f), 170)
                f.touch(2, y = 0f, time = 190)
                f.touch(1, y = 0f, time = 210)
            }
            if (late) Engine.beforeReply = pinch
            f.touch(0)
            if (!late) pinch()
            assertEquals(listOf(0, 5, 2, 2, 6, 2, 1), f.events)
            assertTrue(Engine.scrolls.isEmpty())
        }
    }

    @Test fun aSecondPointerDoesNotTakeAnEstablishedScroll() {
        val f = Fixture(scroller = true)
        f.touch(0)
        f.touch(2, y = 60f, time = 110)
        f.fingers(5 or (1 shl 8), intArrayOf(0, 1), floatArrayOf(60f, 100f), 130)
        f.fingers(2, intArrayOf(0, 1), floatArrayOf(20f, 60f), 150)
        f.fingers(6 or (1 shl 8), intArrayOf(0, 1), floatArrayOf(20f, 60f), 170)
        f.touch(2, y = 0f, time = 190)
        f.touch(1, y = 0f, time = 210)
        assertEquals(listOf(0, 3), f.events)
        assertEquals(listOf(40f, 20f), Engine.scrolls)
        f.view.running(false, false)
    }

    @Test fun anOverflowReleasesAHeldFieldToExactAndIgnoresTheLateReply() {
        val f = Fixture(scroller = true)
        val fields = field(f)
        val heard = ArrayList<Int>()
        fields.text(8)!!.setOnTouchListener { _, e -> heard.add(e.actionMasked); true }
        Engine.beforeReply = {
            repeat(241) { f.touch(2, x = 250f, y = 100f - it, time = 110L + it) }
            assertEquals(listOf(0) + List(241) { 2 }, Engine.touches)
        }
        f.touch(0, x = 250f)
        f.touch(1, x = 250f, y = -140f, time = 400)
        assertEquals(listOf(0) + List(241) { 2 } + listOf(1), Engine.touches)
        assertTrue(heard.isEmpty())
        val held = Fields::class.java.getDeclaredField("held").also { it.isAccessible = true }.get(fields) as Collection<*>
        assertTrue("no field events remain retained", held.isEmpty())
        val contacts = ExactView::class.java.getDeclaredField("contacts").also { it.isAccessible = true }.get(f.view)
        val pending = Contacts::class.java.getDeclaredField("pending").also { it.isAccessible = true }.get(contacts) as Collection<*>
        assertTrue("no contact events remain retained", pending.isEmpty())
    }

    @Test fun aHostedViewReceivesAPanInItsOwnCoordinates() {
        val f = Fixture()
        f.touch(MotionEvent.ACTION_DOWN)
        f.touch(MotionEvent.ACTION_MOVE, y = 50f, time = 120)
        f.touch(MotionEvent.ACTION_UP, y = 50f, time = 140)
        assertEquals(listOf(0, 2, 1), f.events)
        assertEquals(90f to 80f, f.points.first())
        assertEquals(listOf(0, 3), Engine.touches)
    }
    @Test fun aTapAndACancelReachTheViewOnce() {
        for (end in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            val f = Fixture()
            f.touch(0); f.touch(end, time = 120)
            assertEquals(listOf(0, end), f.events)
            assertEquals(listOf(0, 3), Engine.touches)
        }
    }

    @Test fun aViewDecliningDownHearsNothingElse() {
        val f = Fixture(accepts = false)
        f.touch(0); f.touch(2, y = 50f, time = 120); f.touch(1, y = 50f, time = 140)
        assertEquals(listOf(0), f.events)
        assertEquals(listOf(0, 2, 1), Engine.touches)
    }

    @Test fun verticalScrollCancelsTheMapWithEarlyOrLateHitReplies() {
        for (late in listOf(false, true)) {
            val f = Fixture(scroller = true)
            val moves = {
                f.touch(2, y = 70f, time = 120)
                f.touch(2, y = 20f, time = 140)
                f.touch(2, y = 40f, time = 160)
                f.touch(1, y = 0f, time = 180)
            }
            if (late) Engine.beforeReply = moves
            f.touch(0)
            if (!late) moves()
            assertEquals(listOf(0, 3), f.events)
            // The crossing sample chooses the axis; subsequent moves are ordered, and UP adds no travel.
            assertEquals(listOf(50f, -20f), Engine.scrolls)
            assertEquals(listOf(0, 3), Engine.touches)
            f.view.running(false, false)
        }
    }

    @Test fun horizontalPanAndSecondPointerStayWithTheMapInAVerticalScroller() {
        for (late in listOf(false, true)) {
            val f = Fixture(scroller = true)
            val moves = {
                f.touch(2, x = 150f, time = 120)
                f.fingers(5 or (1 shl 8), intArrayOf(0, 1), floatArrayOf(100f, 120f), 140)
                f.fingers(2, intArrayOf(0, 1), floatArrayOf(100f, 140f), 160)
                f.fingers(6, intArrayOf(0, 1), floatArrayOf(100f, 140f), 180)
                f.fingers(2, intArrayOf(1), floatArrayOf(150f), 200)
                f.fingers(1, intArrayOf(1), floatArrayOf(150f), 220)
            }
            if (late) Engine.beforeReply = moves
            f.touch(0)
            if (!late) moves()
            assertEquals(listOf(0, 2, 5, 2, 6, 2, 1), f.events)
            assertEquals(listOf(0, 1), f.pointers[3])
            assertEquals(listOf(1), f.pointers[5])
            assertTrue(Engine.scrolls.isEmpty())
        }
    }

    @Test fun aClaimedExactAxisKeepsTheGestureAndAnUnclaimedAxisReachesTheMap() {
        for (axis in listOf(1, 2)) {
            val f = Fixture(claims = axis)
            f.touch(0); f.touch(2, x = 150f, time = 120); f.touch(1, x = 150f, time = 140)
            assertEquals(if (axis == 1) emptyList<Int>() else listOf(0, 2, 1), f.events)
            assertEquals(if (axis == 1) listOf(0, 2, 1) else listOf(0, 3), Engine.touches)
        }
    }

    @Test fun aStopDiscardsPendingContactsAndCancelsAnAcceptedOne() {
        for (late in listOf(false, true)) {
            val f = Fixture(scroller = true)
            val stop = {
                f.touch(2, y = 70f, time = 120)
                f.touch(1, y = 70f, time = 140)
                f.view.running(false, false)
            }
            if (late) Engine.beforeReply = stop
            f.touch(0)
            if (!late) f.view.running(false, false)
            assertEquals(if (late) emptyList<Int>() else listOf(0, 3), f.events)
            f.touch(2, y = 0f, time = 160)
            assertTrue(Engine.scrolls.isEmpty())
        }
    }

    @Test fun aRetiredAllocationCannotReceiveOrScrollTheRestOfItsContact() {
        val f = Fixture(scroller = true)
        f.touch(0)
        f.natives.unrowed(emptySet())
        f.touch(2, y = 60f, time = 120)
        f.touch(2, y = 20f, time = 140)
        f.touch(1, y = 20f, time = 160)
        assertEquals(listOf(0, 3), f.events)
        assertEquals(listOf(0, 3), Engine.touches)
        assertTrue("retirement must swallow the rest of the drag", Engine.scrolls.isEmpty())
        f.touch(0, time = 180)
        f.touch(2, y = 60f, time = 200)
        f.touch(2, y = 20f, time = 220)
        f.touch(1, y = 20f, time = 240)
        assertEquals("a new contact may scroll", listOf(40f), Engine.scrolls)
        f.view.running(false, false)
    }

    @Test fun twoCompletedTapsBehindOneReplyRemainWholeAndOrdered() {
        val f = Fixture()
        Engine.beforeReply = {
            f.touch(1, time = 120)
            f.touch(0, time = 160)
            f.touch(1, time = 180)
        }
        f.touch(0)
        assertEquals(listOf(0, 1, 0, 1), f.events)
        assertEquals(listOf(0, 3, 0, 3), Engine.touches)
    }

    @Test fun aFieldBesideTheMapKeepsItsSelectionGestureAfterALateReply() {
        val f = Fixture(scroller = true)
        val fields = Fields(f.activity).also { it.adopted(f.view); f.view.fields = it; f.parent.addView(it) }
        // Field 8 occupies the neighbouring box. Empty value, default look, four empty strings.
        val row = intArrayOf(1, 8, 1, 1, 0, 220f.toBits(), 20f.toBits(), 200f.toBits(), 200f.toBits(),
            0, 0, (-1f).toBits(), 0, 0, 0) + IntArray(18) + IntArray(4)
        fields.told(row)
        val heard = ArrayList<Int>()
        fields.text(8)!!.setOnTouchListener { _, e ->
            heard.add(e.actionMasked)
            if (e.actionMasked == 0) fields.requestDisallowInterceptTouchEvent(true)
            true
        }
        Engine.reply[2] = 8
        Engine.beforeReply = {
            f.touch(2, x = 250f, y = 50f, time = 120)
            f.touch(1, x = 250f, y = 50f, time = 140)
        }
        f.touch(0, x = 250f)
        assertEquals(listOf(0, 2, 1), heard)
        assertTrue(f.events.isEmpty())
        assertTrue(Engine.scrolls.isEmpty())
        assertTrue(Engine.touches.isEmpty())
    }

    @Test fun anExactContextMenuCancelsTheNativeTapButAnUntakenLongPressDoesNot() {
        for (takes in listOf(false, true)) {
            val f = Fixture()
            Engine.takesLongPress = takes
            f.controller.setup()
            f.activity.setContentView(f.parent)
            f.touch(0)
            // Run the system's timeout, not a wall-clock wait; the hit answer still crosses Native.under.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(
                java.time.Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout().toLong()))
            f.touch(1, time = 800)
            assertEquals(if (takes) listOf(0, 3) else listOf(0, 1), f.events)
            assertEquals(listOf(0, 4, 3), Engine.touches)
            f.controller.pause().stop().destroy()
        }
    }

    @Test fun aFirstPointerLiftBeforeSlopCannotStartADragFromTheRemainingFinger() {
        val f = Fixture(scroller = true)
        Engine.beforeReply = {
            f.fingers(5 or (1 shl 8), intArrayOf(0, 1), floatArrayOf(100f, 100f), 110)
            f.fingers(6, intArrayOf(0, 1), floatArrayOf(100f, 100f), 120)
            f.fingers(2, intArrayOf(1), floatArrayOf(20f), 140)
            f.fingers(1, intArrayOf(1), floatArrayOf(20f), 160)
        }
        f.touch(0)
        assertEquals(listOf(0, 5, 6, 2, 1), f.events)
        assertTrue(Engine.scrolls.isEmpty())
    }

    @Test fun aCancelBeforeTheReplyRemainsACancelAndAnOldAllocationGetsNoDown() {
        val f = Fixture()
        Engine.beforeReply = { f.touch(3, time = 120) }
        f.touch(0)
        assertEquals(listOf(0, 3), f.events)
        f.events.clear()
        Engine.beforeReply = {
            f.natives.ensure(7, 2, 200, 300, "{\"module\":\"test-map\",\"slot\":2,\"generation\":4}", 0)
            f.touch(1, time = 200)
        }
        f.touch(0, time = 180)
        assertTrue(f.events.isEmpty())
    }

}
