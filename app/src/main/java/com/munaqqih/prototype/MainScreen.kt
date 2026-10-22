package com.munaqqih.prototype

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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

data class FileInfo(
    val rows: Int, val cols: Int, val sizeMb: Double,
    val loadTime: Double, val peakMb: Double, val numericColumns: List<String>
)

data class BenchRow(
    val rows: Int, val sizeMb: Double, val load: Double,
    val missing: Double, val ml: Double, val peakMb: Double
)

fun fmt(x: Double, digits: Int = 3) = String.format(Locale.US, "%.${digits}f", x)

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("جاري تشغيل Python...") }
    var envText by remember { mutableStateOf("") }

    var fileInfo by remember { mutableStateOf<FileInfo?>(null) }
    var missing by remember { mutableStateOf<List<Pair<String, Double>>>(emptyList()) }
    var missingTime by remember { mutableStateOf(0.0) }
    var selectedColumn by remember { mutableStateOf<String?>(null) }
    var mlText by remember { mutableStateOf("") }

    var benchRows by remember { mutableStateOf<List<BenchRow>>(emptyList()) }
    var rawRuns by remember { mutableStateOf("") }

    // أول تشغيل: نحمّل المكتبات مرة وحدة عشان وقت الاستيراد ما يدخل في القياس
    LaunchedEffect(Unit) {
        envText = withContext(Dispatchers.Default) { buildEnvText(context) }
        status = "جاهز"
        busy = false
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
                selectedColumn = fileInfo!!.numericColumns.firstOrNull()
                mlText = ""
                status = "تم تحميل الملف"
            } catch (e: Exception) {
                status = "خطأ: ${e.message}"
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("منقّح", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(status, fontSize = 14.sp)
        }

        // ---------- ١) فتح ملف ----------
        Section("الملف") {
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) {
                Text("اختر ملف CSV")
            }
            fileInfo?.let { f ->
                Text("الصفوف: ${f.rows}   الأعمدة: ${f.cols}   الحجم: ${fmt(f.sizeMb, 2)} MB")
                Text("وقت التحميل: ${fmt(f.loadTime)} ث   تحليل المفقود: ${fmt(missingTime)} ث")
                Text("ذروة الذاكرة: ${fmt(f.peakMb, 1)} MB")
            }
        }

        // ---------- ٢) جدول المفقود ----------
        if (missing.isNotEmpty()) {
            Section("القيم المفقودة") {
                TableRow(listOf("العمود", "المفقود %"), header = true)
                missing.forEach { (col, pct) -> TableRow(listOf(col, fmt(pct, 2))) }
            }
        }

        // ---------- ٣) Isolation Forest ----------
        fileInfo?.let { f ->
            Section("القيم الشاذة") {
                if (f.numericColumns.isEmpty()) {
                    Text("ما فيه أعمدة رقمية في الملف")
                } else {
                    ColumnPicker(f.numericColumns, selectedColumn) { selectedColumn = it }
                    Button(
                        enabled = !busy && selectedColumn != null,
                        onClick = {
                            scope.launch {
                                busy = true
                                status = "جاري تشغيل Isolation Forest..."
                                try {
                                    val (r, peak) = withContext(Dispatchers.Default) {
                                        val sampler = MemorySampler()
                                        sampler.start()
                                        val r = PythonBridge.runIsolationForest(selectedColumn!!)
                                        r to sampler.stop()
                                    }
                                    val n = r.getInt("outliers")
                                    val total = r.getInt("rows")
                                    mlText = "لقى $n صف شاذ من $total (${fmt(n * 100.0 / total, 2)}%)\n" +
                                            "الوقت: ${fmt(r.getDouble("time"))} ث   ذروة الذاكرة: ${fmt(peak, 1)} MB"
                                    status = "تم"
                                } catch (e: Exception) {
                                    status = "خطأ: ${e.message}"
                                }
                                busy = false
                            }
                        }
                    ) { Text("شغّل Isolation Forest") }
                    if (mlText.isNotEmpty()) Text(mlText)
                }
            }
        }

        // ---------- ٤) قياس الأداء ----------
        Section("قياس الأداء") {
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        benchRows = emptyList()
                        val raw = StringBuilder("Raw runs (seconds, MB)\nrows,run,load,missing,ml,peak_mb\n")
                        try {
                            for (size in listOf(1_000, 10_000, 100_000)) {
                                status = "جاري توليد ملف $size صف..."
                                val gen = withContext(Dispatchers.Default) {
                                    PythonBridge.generateFile(size, context.cacheDir.absolutePath)
                                }
                                val path = gen.getString("path")
                                val runs = mutableListOf<DoubleArray>()
                                for (run in 1..3) {
                                    status = "حجم $size - المحاولة $run من 3"
                                    val (r, peak) = withContext(Dispatchers.Default) {
                                        val sampler = MemorySampler()
                                        sampler.start()
                                        val r = PythonBridge.benchmarkOnce(path)
                                        r to sampler.stop()
                                    }
                                    val one = doubleArrayOf(
                                        r.getDouble("load_time"), r.getDouble("missing_time"),
                                        r.getDouble("ml_time"), peak
                                    )
                                    runs.add(one)
                                    raw.append("$size,$run,${one.joinToString(",") { fmt(it, 4) }}\n")
                                }
                                benchRows = benchRows + BenchRow(
                                    rows = size,
                                    sizeMb = gen.getDouble("size_mb"),
                                    load = runs.map { it[0] }.average(),
                                    missing = runs.map { it[1] }.average(),
                                    ml = runs.map { it[2] }.average(),
                                    peakMb = runs.map { it[3] }.average()
                                )
                                File(path).delete()
                            }
                            rawRuns = raw.toString()
                            status = "انتهى القياس"
                        } catch (e: Exception) {
                            status = "خطأ: ${e.message}"
                        }
                        busy = false
                    }
                }
            ) { Text("قياس الأداء") }

            if (benchRows.isNotEmpty()) {
                Text("المتوسط من 3 محاولات", fontWeight = FontWeight.Bold)
                TableRow(listOf("الصفوف", "الحجم MB", "التحميل ث", "المفقود ث", "ML ث", "الذاكرة MB"), header = true)
                benchRows.forEach { b ->
                    TableRow(listOf(String.format(Locale.US, "%,d", b.rows), fmt(b.sizeMb, 2), fmt(b.load),
                        fmt(b.missing), fmt(b.ml), fmt(b.peakMb, 1)))
                }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(benchTableText(benchRows) + "\n" + rawRuns + "\n" + envText))
                    status = "تم نسخ الجدول"
                }) { Text("نسخ الجدول") }
            }
        }

        // ---------- ٥) بيئة الاختبار ----------
        if (envText.isNotEmpty()) {
            Section("معلومات الجهاز") {
                Text(envText, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun TableRow(cells: List<String>, header: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (header) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        cells.forEach {
            Text(
                it, modifier = Modifier.weight(1f), fontSize = 12.sp,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
    HorizontalDivider()
}

@Composable
fun ColumnPicker(columns: List<String>, selected: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("العمود: ${selected ?: "-"}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            columns.forEach { col ->
                DropdownMenuItem(text = { Text(col) }, onClick = { onSelect(col); open = false })
            }
        }
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

fun benchTableText(rows: List<BenchRow>): String {
    val sb = StringBuilder("Rows\tFile Size (MB)\tLoad Time (s)\tMissing-Value Analysis (s)\tML Operation (s)\tPeak Memory (MB)\n")
    rows.forEach {
        sb.append(String.format(Locale.US, "%,d", it.rows)).append('\t')
            .append(fmt(it.sizeMb, 2)).append('\t')
            .append(fmt(it.load)).append('\t')
            .append(fmt(it.missing)).append('\t')
            .append(fmt(it.ml)).append('\t')
            .append(fmt(it.peakMb, 1)).append('\n')
    }
    return sb.toString()
}
