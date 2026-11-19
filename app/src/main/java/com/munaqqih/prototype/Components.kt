package com.munaqqih.prototype

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- الهيدر: الاسم + التبويبات + الحالة ----------
@Composable
fun AppHeader(status: String, busy: Boolean, isError: Boolean, tab: Int, onTab: (Int) -> Unit) {
    val dot = when {
        isError -> Color(0xFFFF8A75)
        busy -> Color(0xFFF2C14E)
        else -> Color(0xFF6FD3B0)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(Palette.Ink)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("منقّح", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TabPill("التحليل", tab == 0) { onTab(0) }
                TabPill("قياس الأداء", tab == 1) { onTab(1) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(10.dp))
            Text(
                status, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (isError) Color(0xFFFFB4A6) else Color.White.copy(alpha = 0.9f)
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxSize(), color = Color(0xFF6FD3B0), trackColor = Palette.InkSoft)
        }
    }
}

@Composable
fun TabPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) Color.White else Color.Transparent)
            .border(1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.3f), shape)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, modifier = Modifier.padding(horizontal = 16.dp), fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Palette.Ink else Color.White.copy(alpha = 0.85f)
        )
    }
}

// ---------- بطاقة قسم ----------
@Composable
fun Section(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Palette.Card).border(1.dp, Palette.Mist, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Text, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, fontSize = 13.sp, color = Palette.Muted)
        }
        content()
    }
}

// ---------- أرقام رئيسية: (القيمة to التسمية) ----------
@Composable
fun StatRow(stats: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        stats.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.fillMaxHeight().width(1.dp).background(Palette.Mist))
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, style = Tabular, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
                Text(label, fontSize = 12.sp, color = Palette.Muted, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------- زر رئيسي / ثانوي مع سطر شرح اختياري ----------
@Composable
fun ActionButton(
    title: String, enabled: Boolean = true, subtitle: String? = null, primary: Boolean = true, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    val label: @Composable RowScope.() -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal,
                    color = LocalContentColor.current.copy(alpha = 0.85f))
            }
        }
    }
    if (primary) {
        Button(
            onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.Sage, contentColor = Color.White,
                disabledContainerColor = Palette.Mist, disabledContentColor = Palette.Muted
            ),
            content = label
        )
    } else {
        OutlinedButton(
            onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            border = BorderStroke(1.5.dp, if (enabled) Palette.Sage else Palette.Mist),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Sage, disabledContentColor = Palette.Muted),
            content = label
        )
    }
}

// ---------- شريط المفقود لعمود ----------
@Composable
fun MissingBar(column: String, percent: Double) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val target = (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
    val fraction by animateFloatAsState(if (shown) target else 0f, tween(700), label = "missing")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(column, Modifier.weight(1f), fontSize = 14.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (percent > 0) "${fmt(percent, 2)}%" else "سليم", style = Tabular, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, color = if (percent > 0) Palette.Marker else Palette.Sage
            )
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Paper)) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Palette.Marker))
        }
    }
}

// ---------- جدول يتمرر أفقيا بدل ما يضغط الخلايا ----------
@Composable
fun DataTable(headers: List<String>, rows: List<List<String>>, firstWidth: Dp = 84.dp, cellWidth: Dp = 72.dp) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).border(1.dp, Palette.Mist, shape)) {
        // لو الجدول أضيق من الشاشة نوزع الباقي على الأعمدة
        val fill = if (headers.size > 1) (maxWidth - firstWidth) / (headers.size - 1) else cellWidth
        val w = if (fill > cellWidth) fill else cellWidth
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            TableLine(headers, firstWidth, w, header = true, shaded = false)
            rows.forEachIndexed { i, r -> TableLine(r, firstWidth, w, header = false, shaded = i % 2 == 1) }
        }
    }
}

@Composable
private fun TableLine(cells: List<String>, firstWidth: Dp, cellWidth: Dp, header: Boolean, shaded: Boolean) {
    Row(
        Modifier
            .background(if (header) Palette.SageTint else if (shaded) Palette.Paper else Palette.Card)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEachIndexed { i, c ->
            Text(
                c, modifier = Modifier.width(if (i == 0) firstWidth else cellWidth).padding(horizontal = 6.dp),
                style = Tabular, fontSize = if (header) 12.sp else 13.sp, lineHeight = 16.sp,
                fontWeight = if (header || i == 0) FontWeight.Bold else FontWeight.Normal,
                color = if (header) Palette.Ink else Palette.Text,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.Center
            )
        }
    }
}

// ---------- ملاحظة بشريط لون جانبي (اللون = الحالة) ----------
@Composable
fun Callout(text: String, tint: Color, accent: Color, bold: Boolean = false, size: Int = 13) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tint).height(IntrinsicSize.Min)) {
        Box(Modifier.fillMaxHeight().width(4.dp).background(accent))
        Text(
            text, Modifier.padding(12.dp), fontSize = size.sp, lineHeight = (size + 8).sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, color = Palette.Text
        )
    }
}

// ---------- اختيار العمود ----------
@Composable
fun ColumnPicker(columns: List<String>, selected: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box {
        Row(
            Modifier
                .fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(Palette.Paper)
                .border(1.dp, Palette.Mist, shape)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("العمود", fontSize = 12.sp, color = Palette.Muted)
                Text(selected ?: "-", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val arrow = Palette.Muted
            Canvas(Modifier.size(10.dp, 6.dp)) {
                val p = Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close() }
                drawPath(p, arrow)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            columns.forEach { col -> DropdownMenuItem(text = { Text(col) }, onClick = { onSelect(col); open = false }) }
        }
    }
}

// ---------- سطر مواصفات: التسمية يمين، القيمة (لاتينية) يسار ----------
@Composable
fun SpecRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, fontSize = 14.sp, color = Palette.Muted)
        Text(
            value, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.Text,
            textAlign = TextAlign.Left, style = TextStyle(textDirection = TextDirection.Ltr)
        )
    }
}
