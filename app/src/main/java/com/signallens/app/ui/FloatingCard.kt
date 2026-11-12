package com.signallens.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.signallens.app.MainActivity
import com.signallens.app.engine.Level
import com.signallens.app.engine.Profile
import com.signallens.app.engine.Shape

/** 지도 위에 띄울 내용 */
data class FloatModel(
    val level: Level,
    val label: String,   // 안내 · 주의 · 바로 확인 · 답변
    val title: String,
    val sub: String?,
    val sticky: Boolean, // true면 상황이 끝날 때까지 유지 (바로 확인 등급)
)

/**
 * 지도(내비) 위에 둥둥 떠 있는 작은 알림 카드. 재생 중인 음악 카드처럼 경로를 가리지 않게
 * 지도 왼쪽 위 구석에만 뜬다. 다른 앱 위에 그리기 권한(SYSTEM_ALERT_WINDOW)이 필요하다.
 *
 * 카드 누르기 → 앱 열기 / 🎤 누르기 → 바로 말하기
 */
class FloatingCard(private val ctx: Context, private val onMic: () -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var hideTask: Runnable? = null

    // 위치 · 크기 (2560×1440 Pleos 화면 기준 px, 지도 왼쪽 위)
    var x = 880
    var y = 150
    var widthPx = 900

    fun canShow(): Boolean = Settings.canDrawOverlays(ctx)

    fun show(m: FloatModel, profile: Profile) {
        main.post { showNow(m, profile) }
    }

    fun hide() {
        main.post { removeNow() }
    }

    @SuppressLint("SetTextI18n")
    private fun showNow(m: FloatModel, profile: Profile) {
        if (!canShow()) return
        removeNow()
        val color = levelColor(m.level, profile).toArgb()
        val scale = if (profile == Profile.ELDER) 1.3f else 1f
        val d = ctx.resources.displayMetrics.density

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * d).toInt(), (18 * d).toInt(), (18 * d).toInt(), (18 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 28 * d
                setColor(0xEE0B1A3A.toInt())
                setStroke((4 * d).toInt(), color)
            }
            setOnClickListener {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
        val iconSize = (64 * d * scale).toInt()
        card.addView(ShapeView(ctx, m.level.shape, color), LinearLayout.LayoutParams(iconSize, iconSize).apply {
            marginEnd = (20 * d).toInt()
        })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = m.label
            setTextColor(color)
            textSize = 22f * scale
            typeface = Typeface.DEFAULT_BOLD
        })
        texts.addView(TextView(ctx).apply {
            text = m.title
            setTextColor(0xFFE6F0FF.toInt())
            textSize = 34f * scale
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        m.sub?.let { s ->
            texts.addView(TextView(ctx).apply {
                text = s
                setTextColor(0xFFA8BCDB.toInt())
                textSize = 24f * scale
                maxLines = 2
            })
        }
        card.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(ctx).apply {
            text = "🎤"
            textSize = 36f
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), 0, (8 * d).toInt(), 0)
            setOnClickListener { onMic() }
        })

        val lp = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = this@FloatingCard.x
            y = this@FloatingCard.y
        }
        runCatching { wm.addView(card, lp) }.onSuccess { root = card }

        if (!m.sticky) {
            val task = Runnable { removeNow() }
            hideTask = task
            main.postDelayed(task, 12_000)
        }
    }

    private fun removeNow() {
        hideTask?.let { main.removeCallbacks(it) }
        hideTask = null
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    /** ○ △ □ 모양 아이콘 */
    private class ShapeView(ctx: Context, private val shape: Shape, private val color: Int) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@ShapeView.color }
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFF04060C.toInt()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            when (shape) {
                Shape.CIRCLE -> c.drawCircle(w / 2, h / 2, w / 2, fill)
                Shape.SQUARE -> c.drawRect(w * 0.06f, h * 0.06f, w * 0.94f, h * 0.94f, fill)
                Shape.TRIANGLE -> c.drawPath(Path().apply {
                    moveTo(w / 2, 0f); lineTo(w, h * 0.92f); lineTo(0f, h * 0.92f); close()
                }, fill)
            }
            mark.textSize = w * 0.42f
            val text = when (shape) {
                Shape.CIRCLE -> "i"
                Shape.TRIANGLE -> "!"
                Shape.SQUARE -> "!!"
            }
            val cy = if (shape == Shape.TRIANGLE) h * 0.78f else h / 2 + mark.textSize * 0.35f
            c.drawText(text, w / 2, cy, mark)
        }
    }
}
