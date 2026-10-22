package com.munaqqih.prototype

import com.chaquo.python.Python
import org.json.JSONObject
import java.io.File

// كل استدعاءات Python في مكان واحد
object PythonBridge {

    private val module by lazy { Python.getInstance().getModule("analyzer") }

    private fun call(function: String, vararg args: Any): JSONObject {
        val text = module.callAttr(function, *args).toString()
        return JSONObject(text)
    }

    fun loadFile(path: String) = call("load_file", path)
    fun analyzeMissing() = call("analyze_missing")
    fun runIsolationForest(column: String) = call("run_isolation_forest", column)
    fun generateFile(rows: Int, folder: String) = call("generate_file", rows, folder)
    fun benchmarkOnce(path: String) = call("benchmark_once", path)
    fun versions() = call("versions")
}

// يقيس أعلى استهلاك ذاكرة (RSS) للتطبيق كامل أثناء العملية
// يقرا /proc/self/status كل 10 ملي ثانية من ثريد منفصل
class MemorySampler {
    @Volatile private var running = false
    @Volatile private var peakKb = 0L
    private var thread: Thread? = null

    fun start() {
        peakKb = currentRssKb()
        running = true
        thread = Thread {
            while (running) {
                val now = currentRssKb()
                if (now > peakKb) peakKb = now
                Thread.sleep(10)
            }
        }.also { it.start() }
    }

    // يرجع الذروة بالميجابايت
    fun stop(): Double {
        running = false
        thread?.join()
        val now = currentRssKb()
        if (now > peakKb) peakKb = now
        return peakKb / 1024.0
    }

    private fun currentRssKb(): Long {
        val line = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
        return line?.filter { it.isDigit() }?.toLongOrNull() ?: 0L
    }
}
