package dev.exact.canvas

import android.view.MotionEvent
import android.view.View

/** A hosted view's contact (LLP 1133.008 D8): Android's down result admits the whole sequence. */
internal class ModuleContact {
    private var target: View? = null
    private var x = 0f
    private var y = 0f
    private var last: MotionEvent? = null
    var owned = false
        private set

    fun begin(view: View, down: MotionEvent, x: Float, y: Float): Boolean {
        this.x = x; this.y = y
        view.translationX = x; view.translationY = y
        if (!send(view, down)) return false
        target = view; owned = true
        remember(down)
        return true
    }

    fun deliver(e: MotionEvent) {
        target?.let { send(it, e) }
        remember(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            target = null; last?.recycle(); last = null; owned = false
        }
    }

    /** Retirement consumes the remaining sequence, even if the same View is lent to another element. */
    fun retired(view: View): Boolean {
        if (target !== view) return false
        cancel()
        return true
    }

    fun cancel() {
        val v = target
        val e = last
        if (v != null && e != null) {
            val cancel = MotionEvent.obtain(e)
            cancel.action = MotionEvent.ACTION_CANCEL
            send(v, cancel)
            cancel.recycle()
        }
        target = null
        last?.recycle(); last = null
    }

    fun clear() { cancel(); owned = false }

    private fun remember(e: MotionEvent) { last?.recycle(); last = MotionEvent.obtain(e) }

    private fun send(view: View, e: MotionEvent): Boolean {
        val own = MotionEvent.obtain(e)
        own.offsetLocation(-x, -y)
        return try { view.dispatchTouchEvent(own) } finally { own.recycle() }
    }
}
