package dev.audaro.runemobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * UI-4: the native NPC-dialogue parchment (Android analogue of the iOS dialogueView). The shared bridge forces the
 * engine chat/dialogue into transparent-colour mode while the chat is relocated, so the engine's dialogue box has
 * no opaque background and the chat lines behind it bleed through. This view draws an OPAQUE OSRS parchment over
 * the chat region from HudVeneerBridge.getDialogueJson + the chat snapshot's dialogueActive flag, and renders the
 * name / body / continue / options natively on top (the engine dialogue underneath is simply occluded). Taps are
 * handed to the engine's own handler via HudVeneerBridge.continueDialogue / selectDialogueOption: clicking the
 * CONTINUE widget walks the player (it reads hidden, so the click lands on the scene behind the chatbox) and a
 * synthesized SPACE comes back consumed without advancing anything. No shared-bridge change, so the chat-at-top
 * look + iOS parity are untouched.
 */

class DialogueView(
    context: Context,
) : View(context) {
    companion object {
        private const val CHAT_W = 519 // engine chatbox width (canvas px)
        private const val BOX_H_CANVAS = 182 // the parchment's box: a 40-px title band + the 142-px content
        private const val PARCH = 0xFFD6C79E.toInt() // OSRS parchment tan (opaque) — iOS RGB(0.84,0.78,0.62)
        private const val BORDER = 0xFF5C4521.toInt() // iOS RGB(0.36,0.27,0.13)
        private const val NAME_COLOR = 0xFF800000.toInt() // dark red NPC/player name
        private const val TEXT_COLOR = 0xFF1A1206.toInt() // near-black body on parchment
        private const val CONT_COLOR = 0xFF00008C.toInt() // blue "Click here to continue"
        private const val OPT_COLOR = 0xFF1A1206.toInt()
    }

    /** Tap-to-continue: drive the engine's own continue handler. (Neither of the two obvious routes works: a
     *  precise click on the CONTINUE widget walks the player, and a synthesized SPACE is consumed unmoved.) */
    var onContinue: (() -> Unit)? = null

    /** An option row tapped: its published digit, with the seq of the publish the row was drawn from. */
    var onOption: ((digit: Int, seq: Long) -> Unit)? = null

    /** A prompt row was picked: press the engine's own row at this canvas point (see [isPrompt]). */
    var onPromptRow: ((canvasX: Int, canvasY: Int) -> Unit)? = null

    private var active = false
    private var boxX = 0f
    private var boxY = 0f
    private var boxW = 0f
    private var boxH = 0f
    private var name: String? = null
    private var body: String = ""
    private var hasContinue = false
    private var options: List<Pair<String, Int>> = emptyList() // (text, digit)

    // A message-layer prompt (GE item search): the rows are the engine's own result list, not numbered chat
    // options, so a pick is the ROW's op fired at its canvas point -- which is also the only thing that reaches
    // a list the engine draws underneath the Grand Exchange window. Parallel to [options], same indices.
    private var isPrompt = false
    private var promptPoints: List<Pair<Int, Int>> = emptyList() // (canvasX, canvasY) centre per row
    private val optionRects = ArrayList<RectF>()
    private var contCx = -1
    private var contCy = -1
    private var headRect: RectF? = null // engine 3D-chathead bounds (view px) — cut out of the parchment
    private var lastJson = ""
    private var viewSeq = 0L // the publish these rows were built from -- an answer must quote it, not a later one
    private var bodyLayout: StaticLayout? = null

    private val fillPaint = Paint().apply { color = PARCH }
    private val borderPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            color = BORDER
            isAntiAlias = true
        }
    private val namePaint =
        Paint().apply {
            color = NAME_COLOR
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    private val contPaint =
        Paint().apply {
            color = CONT_COLOR
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    private val bodyPaint =
        TextPaint().apply {
            color = TEXT_COLOR
            isAntiAlias = true
        }
    private val optPaint =
        Paint().apply {
            color = OPT_COLOR
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    private val dp = resources.displayMetrics.density

    private fun hide() {
        if (active) {
            active = false
            lastJson = ""
            invalidate()
        }
    }

    /** True while a dialogue parchment is being shown (so the shell can route BACK to dismiss it). */
    val isShowing: Boolean get() = active

    /** Programmatic dismiss (e.g. hardware BACK): advance a "click to continue" dialogue. No-op for an options
     *  dialogue (there's no neutral choice) and when nothing is showing. Returns true if it acted. */
    fun dismiss(): Boolean {
        if (!active || options.isNotEmpty()) return false
        onContinue?.invoke()
        return true
    }

    /** Drive each frame: [chatSnap] (for the dialogueActive/topLevel gate) + [dlg] + canvas→view scale. */
    fun update(
        chatSnap: String,
        dlg: DialoguePublish,
        sx: Float,
        sy: Float,
        show: Boolean,
    ) {
        if (!show) {
            hide()
            return
        }
        val cd =
            try {
                JSONObject(chatSnap)
            } catch (e: Exception) {
                null
            }
        if (cd == null || !cd.optBoolean("loggedIn") || cd.optInt("topLevelId") != 164 || !cd.optBoolean("dialogueActive")) {
            hide()
            return
        }
        val d =
            try {
                JSONObject(dlg.json)
            } catch (e: Exception) {
                null
            }
        if (d == null || !d.has("type")) {
            hide()
            return
        }

        boxX = cd.optInt("chatX", 0) * sx
        boxY = cd.optInt("chatY", 0) * sy
        boxW = CHAT_W * sx
        boxH = BOX_H_CANVAS * sy
        active = true

        val cont = d.optJSONObject("cont")
        if (cont != null) {
            contCx = cont.optInt("x") + cont.optInt("w") / 2
            contCy = cont.optInt("y") + cont.optInt("h") / 2
        } else {
            contCx = -1
            contCy = -1
        }

        // The engine's 3D chathead bounds (canvas px → view px). Drawn as a transparent cutout in the parchment so
        // the real head (still rendered underneath on the game surface) shows through, like desktop OSRS.
        val head = d.optJSONObject("head")
        headRect =
            if (head != null && head.optInt("w") > 0 && head.optInt("h") > 0) {
                val hx = head.optInt("x") * sx
                val hy = head.optInt("y") * sy
                RectF(hx, hy, hx + head.optInt("w") * sx, hy + head.optInt("h") * sy)
            } else {
                null
            }
        // Ignore a head rect that doesn't fall inside the dialogue box (stale/bad engine coords) so the cutout
        // can't punch outside the parchment and the text-column math can't degenerate. intersect() clamps the
        // rect to the overlap (returns false + leaves it untouched when there is none).
        headRect?.let { if (!it.intersect(boxX, boxY, boxX + boxW, boxY + boxH)) headRect = null }

        // Re-tag EVERY frame, not only when the text changes. The rows always belong to the json currently held
        // (any text change rebuilds them below in this same call), so this is simply that json's seq -- and
        // doing it unconditionally means a seq that was somehow stale cannot latch: it corrects on the next
        // frame instead of refusing every tap until the dialogue text moves.
        viewSeq = dlg.seq
        if (dlg.json != lastJson) {
            lastJson = dlg.json
            rebuild(d)
            Log.i(
                "DialogueView",
                "ui4: dialogue type=${d.optString("type")} name=${name ?: "-"} opts=${options.size} cont=($contCx,$contCy)",
            )
        }
        invalidate()
    }

    private fun rebuild(d: JSONObject) {
        name = d.optString("name", "").takeIf { it.isNotBlank() }
        body = d.optString("text", "")
        hasContinue = d.optBoolean("continue", false)
        isPrompt = d.optBoolean("prompt", false)
        val optArr = d.optJSONArray("options")
        val kept =
            if (optArr != null) {
                (0 until optArr.length()).mapNotNull { i ->
                    val o = optArr.optJSONObject(i) ?: return@mapNotNull null
                    val t = o.optString("text", "")
                    if (t.isBlank()) null else o to (t to o.optInt("digit", i + 1))
                }
            } else {
                emptyList()
            }
        options = kept.map { it.second }
        // Kept in step with [options] by being built from the same surviving rows: a blank row dropped above
        // would otherwise shift every point below it onto the wrong result.
        promptPoints =
            kept.map { (o, _) ->
                val b = o.optJSONObject("b")
                if (b == null) {
                    -1 to -1
                } else {
                    (b.optInt("x") + b.optInt("w") / 2) to (b.optInt("y") + b.optInt("h") / 2)
                }
            }
        bodyLayout = null // re-laid out in onDraw against the live box width
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!active || boxW <= 0f || boxH <= 0f) return
        val r = RectF(boxX, boxY, boxX + boxW, boxY + boxH)
        // Fill the parchment, cutting out the head rect so the engine's real 3D chathead (still rendered
        // underneath on the game surface) shows through — the "real head" mode the bridge's head bounds exist for.
        val hole = headRect
        canvas.save()
        if (hole != null) canvas.clipOutRect(hole)
        canvas.drawRoundRect(r, 5f * dp, 5f * dp, fillPaint)
        canvas.restore()
        borderPaint.strokeWidth = 2f * dp
        canvas.drawRoundRect(r, 5f * dp, 5f * dp, borderPaint)

        val padX = 12f * dp
        // Text column = the box minus the head's column (head on the left -> text to the right, and vice-versa);
        // fall back to the full inset width if the head math degenerates (mirrors iOS dialogueContentFrameForHead).
        var textLeft = r.left + padX
        var textRight = r.right - padX
        if (hole != null) {
            if (hole.centerX() < r.centerX()) textLeft = hole.right + padX else textRight = hole.left - padX
            if (textRight - textLeft < 60f * dp) {
                textLeft = r.left + padX
                textRight = r.right - padX
            }
        }
        val cx = (textLeft + textRight) / 2f
        val colW = (textRight - textLeft).coerceAtLeast(1f)
        var y = r.top + 10f * dp
        val baseSize = (boxH * 0.11f).coerceIn(11f * dp, 20f * dp)

        // Name (NPC/player), bold dark-red, centred over the text column.
        name?.let {
            namePaint.textSize = baseSize * 1.05f
            y += namePaint.textSize
            canvas.drawText(it, cx, y, namePaint)
            y += 6f * dp
        }

        if (options.isNotEmpty()) {
            // Option rows (centred, tappable). The body, if any, is the prompt above them.
            if (body.isNotBlank()) {
                bodyPaint.textSize = baseSize
                drawBody(canvas, textLeft, colW, y).also { y = it }
                y += 4f * dp
            }
            optionRects.clear()
            optPaint.textSize = baseSize * 1.02f
            val rowH = optPaint.textSize + 10f * dp
            for ((text, _) in options) {
                val rowTop = y
                // Stop at the panel's edge rather than drawing past it: a tap outside the box is refused up in
                // onTouchEvent, so a row drawn below it would look pickable and do nothing. The bridge caps the
                // list to what fits; this is the guard that keeps that cap honest if the panel ever shrinks.
                if (rowTop + rowH > r.bottom) break
                val baseline = y + optPaint.textSize + 3f * dp
                canvas.drawText(text, cx, baseline, optPaint)
                optionRects.add(RectF(r.left, rowTop, r.right, rowTop + rowH))
                y += rowH
            }
        } else {
            // Statement dialogue: body text + "Click here to continue".
            bodyPaint.textSize = baseSize
            drawBody(canvas, textLeft, colW, y)
            if (hasContinue) {
                contPaint.textSize = baseSize * 0.95f
                canvas.drawText("Click here to continue", cx, r.bottom - 9f * dp, contPaint)
            }
        }
    }

    /** Draw the wrapped body text in the column [textLeft]..[textLeft]+[colW] starting at [top]; returns the y below it. */
    private fun drawBody(
        canvas: Canvas,
        textLeft: Float,
        colW: Float,
        top: Float,
    ): Float {
        if (body.isBlank()) return top
        val w = colW.toInt().coerceAtLeast(1)
        val layout =
            StaticLayout.Builder
                .obtain(body, 0, body.length, bodyPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
        canvas.save()
        canvas.translate(textLeft, top)
        layout.draw(canvas)
        canvas.restore()
        return top + layout.height
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false
        val r = RectF(boxX, boxY, boxX + boxW, boxY + boxH)
        if (!r.contains(event.x, event.y)) return false // outside the parchment -> fall through
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (options.isNotEmpty()) {
                val idx = optionRects.indexOfFirst { it.contains(event.x, event.y) }
                if (idx >= 0) {
                    if (isPrompt) {
                        val (px, py) = promptPoints.getOrElse(idx) { -1 to -1 }
                        if (px >= 0) {
                            onPromptRow?.invoke(px, py)
                            Log.i("DialogueView", "ui4: prompt row $idx -> op at $px,$py")
                        }
                    } else {
                        val digit = options[idx].second
                        onOption?.invoke(digit, viewSeq)
                        Log.i("DialogueView", "ui4: option $idx -> digit $digit")
                    }
                }
            } else {
                onContinue?.invoke()
                Log.i("DialogueView", "ui4: continue")
            }
        }
        return true // consume taps over the parchment while a dialogue is open
    }
}

/** A dialogue snapshot and the publish it came from, which the native side hands over as one value from a
 *  single critical section -- the only way to obtain either.
 *
 *  Fetching them separately is the trap: a publish landing between the two reads pairs new json with an older
 *  seq, and because [DialogueView] re-tags its rows only when the json TEXT changes, that seq then LATCHES.
 *  Every option tap is refused by selectDialogueOption's guard until the dialogue text happens to change, so
 *  the dialogue silently stops answering. "Read the seq first and a skew fails safe" only covers the single
 *  pick made on that frame; it misses the latch entirely. */
data class DialoguePublish(
    val seq: Long,
    val json: String,
) {
    companion object {
        /** Split the native `"<seq>\n<json>"` pair. An unparseable seq becomes -1, which no publish can equal,
         *  so a pick is refused rather than answered against a dialogue we cannot identify. */
        fun fromNative(raw: String): DialoguePublish {
            val nl = raw.indexOf('\n')
            if (nl < 0) return DialoguePublish(-1L, "{}")
            return DialoguePublish(raw.substring(0, nl).toLongOrNull() ?: -1L, raw.substring(nl + 1))
        }
    }
}
