package com.jarvis.jarvis_app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.concurrent.Executors

/**
 * 端侧小模型（MediaPipe LLM Inference）。channel: jarvis/localllm
 *  - importModel：系统文件选择器选 .task/.bin，复制进 filesDir/models（App 私有目录）
 *  - load / generate / unload：加载与同步生成（在后台线程跑，结果回主线程）
 * 模型由用户自备，App 不内置、不联网下载。
 */
class LocalLlmBridge(private val activity: Activity) {
    companion object {
        const val REQ_PICK = 5151
    }

    private var llm: LlmInference? = null
    private var loadedPath: String? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var pendingImport: MethodChannel.Result? = null

    fun register(engine: FlutterEngine) {
        MethodChannel(engine.dartExecutor.binaryMessenger, "jarvis/localllm").setMethodCallHandler { call, result ->
            when (call.method) {
                "load" -> {
                    val path = call.argument<String>("path") ?: ""
                    val maxTokens = (call.argument<Number>("maxTokens") ?: 1024).toInt().coerceAtLeast(1024)
                    executor.execute {
                        val ok = try {
                            if (loadedPath != path || llm == null) {
                                llm?.close()
                                llm = null
                                val opts = LlmInference.LlmInferenceOptions.builder()
                                    .setModelPath(path)
                                    .setMaxTokens(maxTokens)
                                    .build()
                                llm = LlmInference.createFromOptions(activity.applicationContext, opts)
                                loadedPath = path
                            }
                            true
                        } catch (t: Throwable) {
                            llm = null
                            loadedPath = null
                            false
                        }
                        activity.runOnUiThread { result.success(ok) }
                    }
                }
                "generate" -> {
                    val prompt = call.argument<String>("prompt") ?: ""
                    executor.execute {
                        val engine = llm
                        if (engine == null) {
                            activity.runOnUiThread { result.error("NOT_LOADED", "本地模型未加载", null) }
                        } else {
                            try {
                                val out = engine.generateResponse(prompt)
                                activity.runOnUiThread { result.success(out) }
                            } catch (t: Throwable) {
                                activity.runOnUiThread { result.error("GENERATE_FAILED", t.message, null) }
                            }
                        }
                    }
                }
                "unload" -> {
                    executor.execute {
                        try { llm?.close() } catch (_: Throwable) {}
                        llm = null
                        loadedPath = null
                    }
                    result.success(true)
                }
                "importModel" -> {
                    pendingImport?.success(null)
                    pendingImport = result
                    try {
                        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }
                        @Suppress("DEPRECATION")
                        activity.startActivityForResult(i, REQ_PICK)
                    } catch (e: Exception) {
                        pendingImport = null
                        result.success(null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK) return
        val res = pendingImport ?: return
        pendingImport = null
        val uri: Uri? = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            res.success(null)
            return
        }
        executor.execute {
            val path = try {
                var name = "local_model.task"
                activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
                }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = File(activity.filesDir, "models").apply { mkdirs() }
                val dest = File(dir, safe)
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                } ?: throw IllegalStateException("无法读取所选文件")
                dest.absolutePath
            } catch (t: Throwable) {
                null
            }
            activity.runOnUiThread { res.success(path) }
        }
    }

    fun release() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
        executor.shutdown()
    }
}
