package dev.exact.canvas

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Touch arbitration for the reader (LLP 1133.008 D8, LLP 1133.021 §9). Hold events behind the down's hit reply,
 * including subsequent contacts, then replay through the same dispatch path. Fields resolve before scrolling;
 * scrolling may cancel a module's accepted contact. Reply timing never changes that order.
 */
internal class Contacts(private val view: ExactView, private val scrolling: Scrolling) {
    private val module = ModuleContact()
    private val pending = ArrayDeque<MotionEvent>()
    private val candidateEvents = ArrayList<MotionEvent>()
    private var candidate: View? = null
    private var originX = 0f
    private var originY = 0f
    private var downX = 0f
    private var downY = 0f
    private var tool = 0
    private var claims = 0
    private var longPresses = 0
    private var serial = 0
    private var waiting = false
    private var stopped = false
    private var engine = false
    private var field = false
    val fieldKeeps get() = field && view.fields?.keeps == true
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val held = Runnable {
        if (!stopped && engine) {
            Exact.touched = android.os.SystemClock.uptimeMillis()
            view.forward(4, downX, downY, tool, -1L)
            if (module.owned || candidate != null) {
                val token = serial
                waiting = true
                view.ask(downX, downY) { words ->
                    if (token == serial && !stopped) {
                        val at = 3 + 11 * words.getOrElse(1) { 0 }
                        if (words.getOrElse(at + 7) { longPresses } != longPresses) {
                            dropCandidate(); module.cancel(); endEngine()
                        }
                        waiting = false
                        drain()
                    }
                }
            }
        }
    }

    fun hold(e: MotionEvent?) {
        view.removeCallbacks(held)
        if (e != null) view.postDelayed(held, (e.eventTime + ViewConfiguration.getLongPressTimeout() - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    fun resume() { stopped = false }

    fun stop() {
        stopped = true; serial++; waiting = false
        hold(null)
        for (e in pending) e.recycle()
        pending.clear(); dropCandidate(); module.clear()
        if (field) view.fields?.cancel()
        if (engine) view.forward(3, downX, downY, tool, 0L)
        engine = false; field = false
    }

    fun retired(v: View) {
        if (module.retired(v)) { scrolling.yieldToHosted(); endEngine() }
        if (candidate === v) dropCandidate()
    }

    fun scrollClaimed(x: Float, y: Float, tool: Int) {
        dropCandidate(); module.cancel(); hold(null)
        if (field || engine) view.forward(3, x, y, tool, 0L)
        field = false; engine = false
    }

    fun touch(e: MotionEvent): Boolean {
        if (stopped || !view.started) return true
        if (waiting) {
            pending.addLast(MotionEvent.obtain(e))
            // Abandon an unanswered hit with a definite recipient, including the field's held down.
            if (pending.size > 240) {
                serial++; waiting = false; hold(null)
                if (field) view.fields?.let { it.under(it.serial, 0) }
                field = false; engine = true
                dropCandidate(); module.clear()
                drain()
            }
            return true
        }
        val down = e.actionMasked == MotionEvent.ACTION_DOWN
        if (down) {
            module.clear(); dropCandidate(); hold(null)
            downX = e.x; downY = e.y; tool = e.getToolType(0)
            engine = false; field = false
            view.fingers.began(e)
            if (scrolling.touch(e)) return true
            field = view.fields?.touch(e) == true
            if (!field) { engine = true; view.fingers.relay(e) }
            val token = ++serial
            val scrollToken = scrolling.serial
            val fieldToken = view.fields?.serial ?: 0
            val first = MotionEvent.obtain(e)
            waiting = true
            view.ask(e.x, e.y) { words ->
                try {
                    if (token != serial || stopped) return@ask
                    if (field) {
                        field = view.fields?.under(fieldToken, words.getOrElse(2) { 0 }) == true
                        engine = !field
                    }
                    if (engine) hold(first)
                    scrolling.under(scrollToken, words)
                    claims = words.getOrElse(0) { 0 }
                    val at = 3 + 11 * words.getOrElse(1) { 0 }
                    longPresses = words.getOrElse(at + 7) { 0 }
                    if (!field && words.size >= at + 8) {
                        candidate = view.natives?.taker(words[at], words[at + 5], words[at + 6])
                        originX = Float.fromBits(words[at + 1]); originY = Float.fromBits(words[at + 2])
                        if (candidate != null) {
                            candidateEvents.add(MotionEvent.obtain(first))
                            if (claims == 0) admit()
                        }
                    }
                    waiting = false
                    drain()
                } finally { first.recycle() }
            }
            return true
        }
        if (module.owned && e.pointerCount > 1) scrolling.yieldToHosted()
        if (scrolling.touch(e)) { hold(null); return true }
        if (field && view.fields?.touch(e) == true) { hold(null); return true }
        if (module.owned) {
            if (e.actionMasked != MotionEvent.ACTION_MOVE || abs(e.x - downX) > slop || abs(e.y - downY) > slop) endEngine()
            module.deliver(e)
            return true
        }
        if (candidate != null) {
            if (candidateEvents.size >= 240) dropCandidate()
            else candidateEvents.add(MotionEvent.obtain(e))
            val dx = abs(e.x - downX); val dy = abs(e.y - downY)
            if (e.actionMasked == MotionEvent.ACTION_CANCEL) dropCandidate()
            else if (dx > slop || dy > slop) {
                if (claims and (if (dx > dy) 1 else 2) == 0) admit() else dropCandidate()
            } else if (e.actionMasked == MotionEvent.ACTION_UP) admit()
            if (!engine) return true
        }
        val action = view.fingers.touch(e)
        if (action < 0) return true
        if (action != 2 || abs(e.x - downX) > slop || abs(e.y - downY) > slop) hold(null)
        if (action == 1) Exact.touched = e.eventTime
        if (engine) view.fingers.forward(e, action)
        if (action == 1 || action == 3) engine = false
        return true
    }

    private fun admit() {
        val v = candidate ?: return
        val events = candidateEvents.toList()
        candidateEvents.clear(); candidate = null
        if (module.begin(v, events.first(), originX, originY)) {
            // A stationary contact still permits Exact's context menu. Movement or release settles ownership.
            if (events.size > 1) endEngine()
            for (e in events.drop(1)) {
                if (e.pointerCount > 1) scrolling.yieldToHosted()
                module.deliver(e)
            }
        }
        for (e in events) e.recycle()
    }

    private fun endEngine() {
        hold(null)
        if (engine) view.forward(3, downX, downY, tool, 0L)
        engine = false
    }

    private fun dropCandidate() {
        candidate = null
        for (e in candidateEvents) e.recycle()
        candidateEvents.clear()
    }

    private fun drain() {
        while (!waiting && !stopped && pending.isNotEmpty()) {
            val e = pending.removeFirst()
            try { touch(e) } finally { e.recycle() }
        }
    }
}
