package com.signallens.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.signallens.app.engine.Level
import com.signallens.app.engine.Profile
import com.signallens.app.engine.Shape

// 덱과 같은 검정 + 파랑
val Ink = Color(0xFF04060C)
val Panel = Color(0xFF0B1A3A)
val PanelHi = Color(0xFF13285A)
val Line = Color(0xFF24407A)
val Text1 = Color(0xFFE6F0FF)
val Text2 = Color(0xFFA8BCDB)
val Cyan = Color(0xFF00D1FF)

/**
 * 글자 크기. 2560×1440 · 160dpi 화면은 1sp = 1px이라 폰 기준 크기면 작아 보인다.
 * 그래서 기본을 크게 잡고, 고령 프로필은 1.45배.
 */
class Type(scale: Float, val compact: Boolean = false) {
    /** 버튼 최소 높이. 가로로 납작한 창(Pleos 가로 창)에서는 낮게 */
    val btnH: Dp = if (compact) 60.dp else 88.dp
    val hero: TextUnit = (64 * scale).sp
    val title: TextUnit = (44 * scale).sp
    val body: TextUnit = (32 * scale).sp
    val small: TextUnit = (26 * scale).sp
    val button: TextUnit = (32 * scale).sp
    val gap: Dp = (20 * scale).dp
}

/** compact = 높이가 낮은 가로 창. 글자를 0.62배로 줄여 한 화면에 들어오게 한다. */
fun typeFor(p: Profile, compact: Boolean = false) = Type(p.textScale * (if (compact) 0.62f else 1f), compact)

/**
 * 알림 색. 색 구분 프로필은 빨강/초록 대신 파랑/노랑/흰색.
 * 어떤 프로필이든 모양(○ △ □)과 글자(안내 · 주의 · 바로 확인)가 같이 나온다.
 */
fun levelColor(level: Level, p: Profile): Color = if (p == Profile.COLOR) {
    when (level) {
        Level.INFO -> Color(0xFF4DA3FF)
        Level.CAUTION -> Color(0xFFFFD23F)
        Level.STOP -> Color(0xFFFFFFFF)
    }
} else {
    when (level) {
        Level.INFO -> Cyan
        Level.CAUTION -> Color(0xFFFFA14A)
        Level.STOP -> Color(0xFFFF4D5E)
    }
}

@Composable
fun T(
    text: String,
    size: TextUnit,
    color: Color = Text1,
    bold: Boolean = false,
    modifier: Modifier = Modifier,
    center: Boolean = false,
) {
    Text(
        text = text,
        fontSize = size,
        lineHeight = size * 1.3f,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = if (center) TextAlign.Center else null,
        modifier = modifier,
    )
}

/** ○ 안내 · △ 주의 · □ 바로 확인 */
@Composable
fun ShapeIcon(shape: Shape, color: Color, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            when (shape) {
                Shape.CIRCLE -> drawCircle(color, radius = w / 2)
                Shape.SQUARE -> drawRect(color, topLeft = Offset(w * 0.06f, h * 0.06f), size = Size(w * 0.88f, h * 0.88f))
                Shape.TRIANGLE -> {
                    val path = Path().apply {
                        moveTo(w / 2, 0f)
                        lineTo(w, h * 0.92f)
                        lineTo(0f, h * 0.92f)
                        close()
                    }
                    drawPath(path, color)
                }
            }
        }
        val mark = when (shape) {
            Shape.CIRCLE -> "i"
            Shape.TRIANGLE -> "!"
            Shape.SQUARE -> "!!"
        }
        Text(
            mark,
            color = Ink,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.42f).sp,
            modifier = if (shape == Shape.TRIANGLE) Modifier.padding(top = size * 0.22f) else Modifier,
        )
    }
}

@Composable
fun BigButton(
    label: String,
    t: Type,
    modifier: Modifier = Modifier,
    color: Color = Cyan,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val pad = if (t.compact) PaddingValues(horizontal = 14.dp, vertical = 8.dp) else PaddingValues(horizontal = 24.dp, vertical = 18.dp)
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = pad,
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Ink),
            modifier = modifier.heightIn(min = t.btnH),
        ) { T(label, t.button, Ink, bold = true, center = true) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            contentPadding = pad,
            border = BorderStroke(3.dp, if (enabled) color else Line),
            modifier = modifier.heightIn(min = t.btnH),
        ) { T(label, t.button, if (enabled) color else Text2, bold = true, center = true) }
    }
}

/** 시연 패널용 작은 토글 */
@Composable
fun Chip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    OutlinedButton(
        onClick = onClick,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        border = BorderStroke(2.dp, if (on) Cyan else Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (on) PanelHi else Color.Transparent),
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Text(
            (if (on) "● " else "") + label,
            color = if (on) Cyan else Text2,
            fontSize = 24.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        )
    }
}

/** FlowRow(실험 API) 대신 n개씩 끊어서 줄 세우기 */
@Composable
fun ChipGrid(items: List<Triple<String, Boolean, () -> Unit>>, perRow: Int = 3) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (label, on, click) -> Chip(label, on, Modifier.weight(1f), click) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, border: Color = Line, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(24.dp))
            .border(3.dp, border, RoundedCornerShape(24.dp))
            .padding(28.dp)
    ) { content() }
}

@Composable
fun Pill(text: String, color: Color = Text2) {
    Box(
        Modifier
            .border(2.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) { Text(text, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun RowScope.Gap(w: Dp = 12.dp) = Spacer(Modifier.width(w))
