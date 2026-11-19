package com.munaqqih.prototype

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// ---------------------------------------------------------------------------
// نقاط القياس. فيه تجربتين:
//   'R' = زيادة الصفوف تدريجيا (6 أعمدة، Isolation Forest على عمود الراتب)
//   'C' = زيادة الأعمدة تدريجيا (عدد صفوف ثابت، Isolation Forest على كل الأعمدة الرقمية)
// ---------------------------------------------------------------------------
const val COLUMN_TEST_ROWS = 100_000
const val BASE_COLS = 6
const val BENCH_RUNS = 3

val BENCH_SIZES: List<Int> = listOf(1_000, 10_000) + (1..10).map { it * 100_000 }
val COLUMN_SIZES: List<Int> = listOf(6, 10, 25, 50, 100, 200, 300, 400, 500)

// بعد الأساسي: نكمّل لين الجوال ينهار (محاولة وحدة لكل نقطة، لأن الوقت يطول)
val STRESS_SIZES: List<Int> = listOf(1_500_000, 2_000_000, 3_000_000, 4_000_000, 5_000_000, 7_500_000, 10_000_000)
val STRESS_COLUMN_SIZES: List<Int> = listOf(750, 1_000, 1_500)

data class BenchPoint(val kind: Char, val n: Int) {
    val rows: Int get() = if (kind == 'R') n else COLUMN_TEST_ROWS
    val cols: Int get() = if (kind == 'R') BASE_COLS else n
    val label: String
        get() = if (kind == 'R') "${fmtInt(n)} صف" else "$n عمود (على ${fmtInt(COLUMN_TEST_ROWS)} صف)"
}

val NORMAL_POINTS: List<BenchPoint> =
    BENCH_SIZES.map { BenchPoint('R', it) } + COLUMN_SIZES.map { BenchPoint('C', it) }
val STRESS_POINTS: List<BenchPoint> =
    STRESS_SIZES.map { BenchPoint('R', it) } + STRESS_COLUMN_SIZES.map { BenchPoint('C', it) }

data class OutlierRow(
    val n: Int, val flaggedPct: Double, val injectedPct: Double,
    val recallPct: Double, val precisionPct: Double
)

fun fmtInt(n: Int): String = String.format(Locale.US, "%,d", n)

// ---------------------------------------------------------------------------
// سجل القياس: يحفظ كل محاولة في ملف أول بأول.
// السبب: لما الذاكرة تخلص، أندرويد يقتل التطبيق كله بدون أي خطأ نقدر نمسكه (try/catch ما ينفع)،
// فلو النتايج بس في الشاشة تضيع. بهالطريقة نعرف آخر نقطة نجحت وعند أي نقطة انقتل.
//
// أنواع الأسطر في bench_log_v3.csv  (kind = R أو C):
//   OK,kind,n,run,حجم_الملف_MB,تحميل,مفقود,ML,ذروة_الذاكرة_MB,الذاكرة_المتاحة_MB,لقاهم,المحقونين,الصح
//   CRASH,kind,n,run           (التطبيق انقتل أثناءها، run=0 يعني أثناء توليد الملف)
//   FAIL,kind,n,run,الرسالة     (Python رجّع خطأ مثل MemoryError)
// ---------------------------------------------------------------------------
class BenchLog(context: Context) {
    private val logFile = File(context.filesDir, "bench_log_v3.csv")
    private val inflightFile = File(context.filesDir, "bench_inflight_v2.txt")

    // نكتب علامة قبل ما نبدأ أي محاولة، ونمسحها لما تخلص.
    // لو لقيناها باقية عند فتح التطبيق = التطبيق انقتل وهو يشتغل عليها.
    fun markStart(p: BenchPoint, run: Int) = inflightFile.writeText("${p.kind},${p.n},$run")
    fun markDone() { inflightFile.delete() }

    // يستدعى أول ما يفتح التطبيق. يرجع true لو المرة الماضية انقتل وهو يقيس
    fun recoverCrash(): Boolean {
        if (!inflightFile.exists()) return false
        val parts = inflightFile.readText().trim().split(",")
        inflightFile.delete()
        val kind = parts.getOrNull(0)?.singleOrNull() ?: return false
        val n = parts.getOrNull(1)?.toIntOrNull() ?: return false
        val run = parts.getOrNull(2)?.toIntOrNull() ?: return false
        logFile.appendText("CRASH,$kind,$n,$run\n")
        return true
    }

    fun addOk(
        p: BenchPoint, run: Int, sizeMb: Double, load: Double, missing: Double, ml: Double,
        peakMb: Double, availMb: Double, flagged: Int, injected: Int, truePos: Int
    ) {
        // الأوقات بـ 6 خانات عشرية (أجزاء من الثانية)
        val times = listOf(load, missing, ml).joinToString(",") { fmt(it, 6) }
        logFile.appendText(
            "OK,${p.kind},${p.n},$run,${fmt(sizeMb, 3)},$times,${fmt(peakMb, 1)},${fmt(availMb, 0)}," +
                "$flagged,$injected,$truePos\n"
        )
    }

    fun addFail(p: BenchPoint, run: Int, e: Exception) {
        val msg = (e.message ?: e.javaClass.simpleName).replace(",", ";").replace("\n", " ").take(120)
        logFile.appendText("FAIL,${p.kind},${p.n},$run,$msg\n")
    }

    fun lines(): List<String> = if (logFile.exists()) logFile.readLines() else emptyList()

    fun clear() {
        logFile.delete()
        inflightFile.delete()
    }
}

// ---------------------------------------------------------------------------
// قراءة السجل
// ---------------------------------------------------------------------------

// سطور OK الكاملة فقط (نتجاهل أي سطر ناقص لو التطبيق انقتل وهو يكتبه)
// الحقول: 0=OK 1=kind 2=n 3=run 4=sizeMb 5=load 6=missing 7=ml 8=peak 9=avail 10=flagged 11=injected 12=tp
private fun okLines(lines: List<String>): List<List<String>> =
    lines.map { it.split(",") }.filter { it.size >= 13 && it[0] == "OK" && it[1].length == 1 }

// أقل نقطة فشلت (أو انقتل التطبيق عندها) في هالتجربة، وإلا null
private fun failureOf(lines: List<String>, kind: Char): List<String>? =
    lines.map { it.split(",") }
        .filter { it.size >= 4 && (it[0] == "CRASH" || it[0] == "FAIL") && it[1] == kind.toString() }
        .filter { it[2].toIntOrNull() != null }
        .minByOrNull { it[2].toInt() }

// النقطة محجوبة لو فشلت هي أو نقطة أصغر منها في نفس التجربة (الأكبر بيفشل غالبا، فما نضيّع وقت)
fun isBlocked(lines: List<String>, p: BenchPoint): Boolean {
    val f = failureOf(lines, p.kind) ?: return false
    return p.n >= f[2].toInt()
}

// أرقام المحاولات الناجحة لنقطة، مثلا {1, 2}
fun completedRuns(lines: List<String>, p: BenchPoint): Set<Int> =
    okLines(lines)
        .filter { it[1][0] == p.kind && it[2].toIntOrNull() == p.n }
        .mapNotNull { it[3].toIntOrNull() }
        .toSet()

// هل خلص كل شي في القائمة (نجح كله، أو انحجب بسبب فشل)؟
fun isFinished(lines: List<String>, points: List<BenchPoint>, runs: Int): Boolean =
    points.all { isBlocked(lines, it) || completedRuns(lines, it).size >= runs }

// صفوف الجدول: متوسط المحاولات الناجحة لكل نقطة
fun buildBenchRows(lines: List<String>, kind: Char): List<BenchRow> =
    okLines(lines)
        .filter { it[1][0] == kind }
        .groupBy { it[2].toInt() }
        .toSortedMap()
        .map { (n, runs) ->
            fun avg(i: Int) = runs.map { it[i].toDouble() }.average()
            BenchRow(rows = n, sizeMb = avg(4), load = avg(5), missing = avg(6), ml = avg(7), peakMb = avg(8))
        }

// جدول نسب القيم الشاذة: كم لقى Isolation Forest مقابل الشاذ اللي حقناه
fun buildOutlierRows(lines: List<String>, kind: Char): List<OutlierRow> =
    okLines(lines)
        .filter { it[1][0] == kind }
        .groupBy { it[2].toInt() }
        .toSortedMap()
        .map { (n, runs) ->
            val total = BenchPoint(kind, n).rows.toDouble()
            val flagged = runs.map { it[10].toDouble() }.average()
            val injected = runs.map { it[11].toDouble() }.average()
            val truePos = runs.map { it[12].toDouble() }.average()
            OutlierRow(
                n = n,
                flaggedPct = flagged * 100 / total,
                injectedPct = injected * 100 / total,
                recallPct = if (injected > 0) truePos * 100 / injected else 0.0,
                precisionPct = if (flagged > 0) truePos * 100 / flagged else 0.0
            )
        }

// ملخص: لكل تجربة، أكبر نقطة اشتغلت، ووين فشل/انقفل لو صار
fun benchSummary(lines: List<String>): String {
    val ok = okLines(lines)
    val out = mutableListOf<String>()
    for ((kind, title) in listOf('R' to "الصفوف", 'C' to "الأعمدة")) {
        val maxOk = ok.filter { it[1][0] == kind }.maxOfOrNull { it[2].toInt() }
        val fail = failureOf(lines, kind)
        if (maxOk == null && fail == null) continue
        val sb = StringBuilder("$title: ")
        sb.append(if (maxOk != null) "أكبر نقطة اشتغلت = ${BenchPoint(kind, maxOk).label}" else "ما نجحت أي نقطة")
        if (fail != null) {
            val p = BenchPoint(kind, fail[2].toInt())
            val run = fail[3].toIntOrNull() ?: 0
            val where = if (run == 0) "أثناء توليد الملف" else "في المحاولة $run"
            sb.append("\n    ")
            if (fail[0] == "CRASH") {
                sb.append("انقفل التطبيق عند ${p.label} $where (غالبا الجوال قتله لأن الذاكرة خلصت)")
            } else {
                sb.append("فشل عند ${p.label} $where: ${fail.getOrNull(4) ?: ""}")
            }
        }
        out.add(sb.toString())
    }
    return out.joinToString("\n")
}

// شرح نسب القيم الشاذة، يظهر تحت الجدول ويتنسخ مع التقرير
const val OUTLIER_NOTE =
    "كيف نقرا نسب القيم الشاذة: حقنّا في الملف 1% صفوف شاذة معروفة (أرقامها ×10). " +
        "«شاذ %» = نسبة الصفوف اللي علّمها Isolation Forest. «استرجاع %» = كم من الشاذ المحقون لقاه " +
        "(في اختبار الصفوف يقل عن 100% لأن 5% من قيم الراتب مفقودة وتتعوّض بالوسيط فتضيع). " +
        "«دقة %» = كم من اللي علّمه فعلا شاذ محقون. القرار بالعتبة المعايرة 0.708 لدرجة الشذوذ، " +
        "بدل الإعداد الافتراضي contamination=auto (عتبته 0.5) اللي يعلّم أطراف التوزيع الطبيعي كشاذ."

// التقرير النصي اللي يتنسخ (جداول بفواصل tab، تنلصق في Word/Excel)
fun buildReportText(lines: List<String>, envText: String): String {
    val sb = StringBuilder()
    for ((kind, title) in listOf('R' to "Rows test (6 columns, Isolation Forest on salary)",
        'C' to "Columns test (${fmtInt(COLUMN_TEST_ROWS)} rows, Isolation Forest on all numeric columns)")) {
        val rows = buildBenchRows(lines, kind)
        if (rows.isEmpty()) continue
        val head = if (kind == 'R') "Rows" else "Columns"
        sb.append(title).append('\n')
        sb.append("$head\tFile Size (MB)\tLoad Time (s)\tMissing-Value Analysis (s)\tIsolation Forest (s)\tPeak Memory (MB)\n")
        rows.forEach {
            sb.append("${fmtInt(it.rows)}\t${fmt(it.sizeMb, 2)}\t${fmt(it.load, 4)}\t${fmt(it.missing, 4)}\t")
                .append("${fmt(it.ml, 4)}\t${fmt(it.peakMb, 1)}\n")
        }
        sb.append("\n$head\tFlagged by IF (%)\tInjected (%)\tRecall (%)\tPrecision (%)\n")
        buildOutlierRows(lines, kind).forEach {
            sb.append("${fmtInt(it.n)}\t${fmt(it.flaggedPct, 2)}\t${fmt(it.injectedPct, 2)}\t")
                .append("${fmt(it.recallPct, 1)}\t${fmt(it.precisionPct, 1)}\n")
        }
        sb.append('\n')
    }
    sb.append(benchSummary(lines)).append("\n\n").append(OUTLIER_NOTE).append("\n\n")
    sb.append("Raw runs (seconds, MB)\n")
    sb.append("kind,n,run,load,missing,ml,peak_mb,avail_ram_mb,flagged,injected,true_pos\n")
    okLines(lines).forEach {
        sb.append("${it[1]},${it[2]},${it[3]},${it[5]},${it[6]},${it[7]},${it[8]},${it[9]},")
            .append("${it[10]},${it[11]},${it[12]}\n")
    }
    sb.append('\n').append(envText)
    return sb.toString()
}

fun availMemMb(context: Context): Double {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return info.availMem / (1024.0 * 1024)
}

// ---------------------------------------------------------------------------
// تشغيل القياس على قائمة نقاط. يرجع true لو كل النقاط نجحت.
// لو فشلت نقطة: نسجل الفشل، ونتخطى النقاط الأكبر منها في نفس التجربة، ونكمّل التجربة الثانية.
// ---------------------------------------------------------------------------
suspend fun runBenchmark(
    context: Context,
    log: BenchLog,
    points: List<BenchPoint>,
    runsPerPoint: Int,
    onStatus: (String) -> Unit,
    onProgress: () -> Unit
): Boolean {
    var allOk = true
    for (p in points) {
        val before = log.lines()
        if (isBlocked(before, p)) { allOk = false; continue }
        // نتخطى المحاولات الي خلصت قبل (مهم لما نكمّل بعد انهيار)
        val done = completedRuns(before, p)
        if (done.size >= runsPerPoint) continue

        // ١) توليد الملف (المحاولة 0 في السجل)
        onStatus("جاري توليد ملف ${p.label}...")
        log.markStart(p, 0)
        val gen = try {
            withContext(Dispatchers.Default) {
                PythonBridge.generateFile(p.rows, p.cols, context.cacheDir.absolutePath)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.markDone(); log.addFail(p, 0, e); onProgress()
            allOk = false
            continue
        }
        log.markDone()
        val path = gen.getString("path")
        val sizeMb = gen.getDouble("size_mb")

        // ٢) المحاولات
        for (run in 1..runsPerPoint) {
            if (run in done) continue
            onStatus("${p.label} - المحاولة $run من $runsPerPoint")
            log.markStart(p, run)
            try {
                val avail = availMemMb(context)
                val (res, peak) = withContext(Dispatchers.Default) {
                    val sampler = MemorySampler()
                    sampler.start()
                    try {
                        val r = PythonBridge.benchmarkOnce(path, p.kind == 'C')
                        r to sampler.stop()
                    } catch (e: Exception) {
                        sampler.stop()   // نوقف ثريد القياس حتى لو صار خطأ
                        throw e
                    }
                }
                log.markDone()
                log.addOk(
                    p, run, sizeMb,
                    res.getDouble("load_time"), res.getDouble("missing_time"), res.getDouble("ml_time"),
                    peak, avail,
                    res.getInt("flagged"), res.getInt("injected"), res.getInt("true_pos")
                )
                onProgress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.markDone(); log.addFail(p, run, e); onProgress()
                allOk = false
                break
            }
        }
        File(path).delete()
        File("$path.truth.npy").delete()
    }
    return allOk
}
