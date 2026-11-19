package com.munaqqih.prototype

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

const val CHAQUOPY_VERSION = "17.1.0"
private const val MISSING_PREVIEW = 12

data class FileInfo(
    val rows: Int, val cols: Int, val sizeMb: Double,
    val loadTime: Double, val peakMb: Double, val numericColumns: List<String>
)

data class BenchRow(
    val rows: Int, val sizeMb: Double, val load: Double,
    val missing: Double, val ml: Double, val peakMb: Double
)

data class OutlierResult(
    val column: String, val outliers: Int, val total: Int, val time: Double, val peakMb: Double
) {
    val pct: Double get() = if (total > 0) outliers * 100.0 / total else 0.0
}

fun fmt(x: Double, digits: Int = 3) = String.format(Locale.US, "%.${digits}f", x)

// أسماء عربية لسطور معلومات الجهاز (النص الأصلي الإنجليزي يبقى هو اللي يتنسخ في التقرير)
private val ENV_LABELS = mapOf(
    "Device model" to "الجهاز",
    "RAM" to "الذاكرة",
    "Android version" to "أندرويد",
    "Chaquopy version" to "Chaquopy",
    "Python version" to "Python",
    "Pandas / NumPy / scikit-learn" to "المكتبات"
)

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("جاري تشغيل Python...") }
    var envText by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    val analysisScroll = rememberScrollState()
    val benchScroll = rememberScrollState()

    var fileInfo by remember { mutableStateOf<FileInfo?>(null) }
    var missing by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var missingTime by remember { mutableStateOf(0.0) }
    var showAllMissing by remember { mutableStateOf(false) }
    var selectedColumn by remember { mutableStateOf<String?>(null) }
    var outlier by remember { mutableStateOf<OutlierResult?>(null) }

    // نتايج القياس تنحفظ في ملف أول بأول، عشان لو التطبيق انقتل (الذاكرة خلصت) نرجعها
    val benchLog = remember { BenchLog(context) }
    var benchLines by remember { mutableStateOf<List<String>>(emptyList()) }
    val view = LocalView.current

    // أول تشغيل: نحمّل المكتبات مرة وحدة عشان وقت الاستيراد ما يدخل في القياس
    LaunchedEffect(Unit) {
        envText = withContext(Dispatchers.Default) { buildEnvText(context) }
        // لو المرة الماضية انقتل وهو يقيس، نسجّل الانهيار ونعرض النتايج المحفوظة
        val crashed = withContext(Dispatchers.IO) {
            // ملفات القياس الكبيرة اللي بقت بعد الانهيار نمسحها عشان ما تاكل التخزين
            context.cacheDir.listFiles { f -> f.name.startsWith("bench_") }?.forEach { it.delete() }
            benchLog.recoverCrash()
        }
        benchLines = withContext(Dispatchers.IO) { benchLog.lines() }
        status = if (crashed) "التطبيق انقفل أثناء القياس المرة الماضية - النتايج المحفوظة تحت" else "جاهز"
        busy = false
    }

    // أثناء القياس الطويل نخلي الشاشة شغالة عشان الجوال ما ينام
    LaunchedEffect(busy) { view.keepScreenOn = busy }

    fun startBench(points: List<BenchPoint>, runs: Int) {
        scope.launch {
            busy = true
            try {
                val finished = runBenchmark(
                    context, benchLog, points, runs,
                    onStatus = { status = it },
                    onProgress = { benchLines = benchLog.lines() }
                )
                status = if (finished) "انتهى القياس" else "انتهى القياس، وفيه نقطة فشلت - شوف الملخص"
            } catch (e: Exception) {
                status = "خطأ: ${e.message}"
            }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "جاري قراءة الملف..."
            try {
                val result = withContext(Dispatchers.Default) {
                    val local = copyToCache(context, uri)
                    val sampler = MemorySampler()
                    sampler.start()
                    val info = PythonBridge.loadFile(local.absolutePath)
                    val miss = PythonBridge.analyzeMissing()
                    val peak = sampler.stop()
                    Triple(info, miss, peak)
                }
                val (info, miss, peak) = result
                val nums = info.getJSONArray("numeric_columns")
                fileInfo = FileInfo(
                    rows = info.getInt("rows"),
                    cols = info.getInt("cols"),
                    sizeMb = info.getDouble("size_mb"),
                    loadTime = info.getDouble("load_time"),
                    peakMb = peak,
                    numericColumns = List(nums.length()) { nums.getString(it) }
                )
                val table = miss.getJSONArray("table")
                missing = List(table.length()) {
                    val o = table.getJSONObject(it)
                    o.getString("column") to o.getDouble("percent")
                }
                missingTime = miss.getDouble("time")
                showAllMissing = false
                selectedColumn = fileInfo!!.numericColumns.firstOrNull()
                outlier = null
                status = "تم تحميل الملف"
            } catch (e: Exception) {
                status = "خطأ: ${e.message}"
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.Paper)) {
        AppHeader(status, busy, status.startsWith("خطأ"), tab) { tab = it }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(if (tab == 0) analysisScroll else benchScroll)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (tab == 0) {
                // ---------- ١) الملف ----------
                Section("الملف") {
                    if (fileInfo == null) {
                        Text(
                            "افتح ملف CSV عشان نحسب نسبة المفقود في كل عمود ونكشف القيم الشاذة. كل شي يشتغل على جوالك.",
                            fontSize = 14.sp, lineHeight = 22.sp, color = Palette.Muted
                        )
                    }
                    ActionButton("اختر ملف CSV", enabled = !busy) { picker.launch(arrayOf("*/*")) }
                    fileInfo?.let { f ->
                        StatRow(listOf(
                            fmtInt(f.rows) to "صف", f.cols.toString() to "عمود", "${fmt(f.sizeMb, 2)} MB" to "الحجم"
                        ))
                        HorizontalDivider(color = Palette.Mist)
                        StatRow(listOf(
                            "${fmt(f.loadTime, 4)} ث" to "وقت التحميل",
                            "${fmt(missingTime, 4)} ث" to "تحليل المفقود",
                            "${fmt(f.peakMb, 1)} MB" to "ذروة الذاكرة"
                        ))
                    }
                }

                // ---------- ٢) المفقود: شريط لكل عمود، الأسوأ أول ----------
                if (missing.isNotEmpty()) {
                    val sorted = missing.sortedByDescending { it.second }
                    val shown = if (showAllMissing || sorted.size <= MISSING_PREVIEW) sorted else sorted.take(MISSING_PREVIEW)
                    val withMissing = missing.count { it.second > 0 }
                    Section(
                        "القيم المفقودة",
                        trailing = if (withMissing == 0) "ما فيه مفقود" else "$withMissing من ${missing.size} فيها مفقود"
                    ) {
                        shown.forEach { (col, pct) -> MissingBar(col, pct) }
                        if (sorted.size > MISSING_PREVIEW) {
                            TextButton(onClick = { showAllMissing = !showAllMissing }) {
                                Text(
                                    if (showAllMissing) "عرض أقل" else "عرض كل الأعمدة (${sorted.size})",
                                    color = Palette.Sage, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // ---------- ٣) القيم الشاذة ----------
                fileInfo?.let { f ->
                    Section("القيم الشاذة") {
                        if (f.numericColumns.isEmpty()) {
                            Text("ما فيه أعمدة رقمية في الملف", fontSize = 14.sp, color = Palette.Muted)
                        } else {
                            ColumnPicker(f.numericColumns, selectedColumn) { selectedColumn = it }
                            ActionButton("شغّل Isolation Forest", enabled = !busy && selectedColumn != null) {
                                val col = selectedColumn
                                if (col != null) scope.launch {
                                    busy = true
                                    status = "جاري تشغيل Isolation Forest..."
                                    try {
                                        val (r, peak) = withContext(Dispatchers.Default) {
                                            val sampler = MemorySampler()
                                            sampler.start()
                                            val r = PythonBridge.runIsolationForest(col)
                                            r to sampler.stop()
                                        }
                                        outlier = OutlierResult(col, r.getInt("outliers"), r.getInt("rows"), r.getDouble("time"), peak)
                                        status = "تم"
                                    } catch (e: Exception) {
                                        status = "خطأ: ${e.message}"
                                    }
                                    busy = false
                                }
                            }
                            outlier?.let { o ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("${fmt(o.pct, 2)}%", style = Tabular, fontSize = 40.sp,
                                        fontWeight = FontWeight.ExtraBold, color = Palette.Marker)
                                    Text("لقى ${fmtInt(o.outliers)} صف شاذ من ${fmtInt(o.total)} في عمود ${o.column}",
                                        fontSize = 14.sp, lineHeight = 22.sp, color = Palette.Text)
                                }
                                StatRow(listOf("${fmt(o.time, 4)} ث" to "الوقت", "${fmt(o.peakMb, 1)} MB" to "ذروة الذاكرة"))
                            }
                        }
                    }
                }
            } else {
                // ---------- ٤) قياس الأداء ----------
                val normalDone = isFinished(benchLines, NORMAL_POINTS, BENCH_RUNS)
                val stressDone = isFinished(benchLines, STRESS_POINTS, 1)

                Section("قياس الأداء") {
                    if (benchLines.isEmpty()) {
                        Text(
                            "القياس الكامل ممكن ياخذ ساعة تقريبا. شغّل الجوال على الشاحن، والنتايج تنحفظ أول بأول.",
                            fontSize = 14.sp, lineHeight = 22.sp, color = Palette.Muted
                        )
                    }
                    ActionButton(
                        if (benchLines.isEmpty()) "ابدأ القياس" else "كمّل القياس",
                        enabled = !busy && !normalDone,
                        subtitle = if (benchLines.isEmpty()) "صفوف لين مليون + أعمدة لين 500" else null
                    ) { startBench(NORMAL_POINTS, BENCH_RUNS) }
                    ActionButton(
                        "ابحث عن الحد الأقصى", enabled = !busy && normalDone && !stressDone,
                        subtitle = "بعد المليون صف / بعد 500 عمود", primary = false
                    ) { startBench(STRESS_POINTS, 1) }
                }

                if (benchLines.isNotEmpty()) {
                    val summary = benchSummary(benchLines)
                    if (summary.isNotEmpty()) {
                        val failed = summary.contains("انقفل") || summary.contains("فشل")
                        Callout(summary, if (failed) Palette.MarkerTint else Palette.SageTint,
                            if (failed) Palette.Marker else Palette.Sage, bold = true, size = 14)
                    }

                    BenchTables(benchLines, 'R')
                    BenchTables(benchLines, 'C')

                    Callout(OUTLIER_NOTE, Color(0xFFE9EEEB), Palette.Muted, size = 12)

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(buildReportText(benchLines, envText)))
                                status = "تم نسخ الجدول"
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.5.dp, Palette.Sage),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Sage)
                        ) { Text("نسخ الجدول", fontWeight = FontWeight.Bold) }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { benchLog.clear(); benchLines = emptyList(); status = "تم مسح النتايج" },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.5.dp, if (!busy) Palette.Marker else Palette.Mist),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Marker, disabledContentColor = Palette.Muted)
                        ) { Text("مسح النتايج", fontWeight = FontWeight.Bold) }
                    }
                }

                // ---------- ٥) بيئة الاختبار ----------
                if (envText.isNotEmpty()) {
                    val specs = envText.lines().mapNotNull { line ->
                        val i = line.indexOf(": ")
                        if (i > 0) line.substring(0, i) to line.substring(i + 2) else null
                    }
                    Section("معلومات الجهاز") {
                        Column(Modifier.fillMaxWidth()) {
                            specs.forEachIndexed { i, (k, v) ->
                                if (i > 0) HorizontalDivider(color = Palette.Mist)
                                SpecRow(ENV_LABELS[k] ?: k, v)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

// جدول الأداء + جدول نسب القيم الشاذة لتجربة وحدة (R = صفوف، C = أعمدة)
@Composable
fun BenchTables(lines: List<String>, kind: Char) {
    val rows = buildBenchRows(lines, kind)
    if (rows.isEmpty()) return
    val head = if (kind == 'R') "الصفوف" else "الأعمدة"
    val title = if (kind == 'R') "اختبار الصفوف" else "اختبار الأعمدة"
    val sub = if (kind == 'R') {
        "6 أعمدة، Isolation Forest على عمود الراتب"
    } else {
        "${fmtInt(COLUMN_TEST_ROWS)} صف، Isolation Forest على كل الأعمدة الرقمية"
    }
    Section(title) {
        Text(sub, fontSize = 13.sp, color = Palette.Muted)
        DataTable(
            listOf(head, "الحجم MB", "التحميل ث", "المفقود ث", "IF ث", "الذاكرة MB"),
            rows.map { b ->
                listOf(fmtInt(b.rows), fmt(b.sizeMb, 2), fmt(b.load, 4), fmt(b.missing, 4), fmt(b.ml, 4), fmt(b.peakMb, 1))
            }
        )
        Text("المتوسط من المحاولات الناجحة (3 لكل نقطة، وبعد الأساسي محاولة وحدة)", fontSize = 12.sp, color = Palette.Muted)
        Text("نسب القيم الشاذة", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        DataTable(
            listOf(head, "شاذ %", "محقون %", "استرجاع %", "دقة %"),
            buildOutlierRows(lines, kind).map { o ->
                listOf(fmtInt(o.n), fmt(o.flaggedPct, 2), fmt(o.injectedPct, 2), fmt(o.recallPct, 1), fmt(o.precisionPct, 1))
            },
            cellWidth = 66.dp
        )
    }
}

// ينسخ الملف المختار لمجلد الكاش عشان pandas يقدر يقراه بمسار عادي
fun copyToCache(context: Context, uri: Uri): File {
    val out = File(context.cacheDir, "input.csv")
    context.contentResolver.openInputStream(uri).use { input ->
        out.outputStream().use { output -> input!!.copyTo(output) }
    }
    return out
}

fun buildEnvText(context: Context): String {
    val v = PythonBridge.versions()  // هذا أول استدعاء: يستورد pandas وsklearn
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val ramGb = mem.totalMem / (1024.0 * 1024 * 1024)
    return "Device model: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "RAM: ${fmt(ramGb, 1)} GB (as reported by Android)\n" +
            "Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Chaquopy version: $CHAQUOPY_VERSION\n" +
            "Python version: ${v.getString("python")}\n" +
            "Pandas / NumPy / scikit-learn: ${v.getString("pandas")} / ${v.getString("numpy")} / ${v.getString("sklearn")}"
}
