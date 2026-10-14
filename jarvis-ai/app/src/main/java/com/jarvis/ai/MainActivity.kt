package com.jarvis.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private val bg = Color(0xFF030914)
private val panel = Color(0xFF071729)
private val cyan = Color(0xFF46D9FF)
private val pale = Color(0xFFBCEEFF)

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale.SIMPLIFIED_CHINESE }
        setContent { JarvisApp(onSpeak = { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "jarvis") }) }
    }
    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}

data class ChatLine(val role: String, val text: String)

@Composable
fun JarvisApp(onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("jarvis_config", 0) }
    var baseUrl by remember { mutableStateOf(prefs.getString("base", "https://api.openai.com/v1") ?: "https://api.openai.com/v1") }
    var apiKey by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini") }
    var showSettings by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("SYSTEM ONLINE · 等待指令") }
    val lines = remember { mutableStateListOf(ChatLine("assistant", "JARVIS 系统已启动。先生/女士，请输入指令，或点击麦克风开始中文语音对话。")) }
    val scope = rememberCoroutineScope()
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) input = spoken
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchSpeech(speechLauncher) else status = "麦克风权限未授予"
    }
    fun sendMessage() {
        val prompt = input.trim()
        if (prompt.isEmpty() || busy) return
        input = ""
        lines.add(ChatLine("user", prompt))
        busy = true; status = "正在连接 AI 核心…"
        scope.launch {
            val answer = withContext(Dispatchers.IO) { callChatApi(baseUrl, apiKey, model, lines.toList()) }
            lines.add(ChatLine("assistant", answer))
            status = if (answer.startsWith("连接失败：")) "连接异常 · 请检查配置" else "AI CORE READY · 可继续对话"
            busy = false
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = cyan, background = bg, surface = panel)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF06172A), bg, Color(0xFF02050C))))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("J.A.R.V.I.S", color = cyan, fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                        Text("PERSONAL AI INTERFACE  /  v1.0", color = pale.copy(alpha = .65f), fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                    Text("⚙", color = cyan, fontSize = 27.sp, modifier = Modifier.clickable { showSettings = true }.padding(8.dp))
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(245.dp), contentAlignment = Alignment.Center) {
                    HoloCore(active = busy)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (busy) "PROCESSING" else "JARVIS", color = pale, fontSize = 24.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp)
                        Text(if (busy) "ANALYZING REQUEST" else "READY FOR COMMAND", color = cyan.copy(alpha = .85f), fontSize = 9.sp, letterSpacing = 2.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xAA061B2D)).border(1.dp, cyan.copy(alpha = .28f), RoundedCornerShape(8.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (busy) Color.Yellow else Color(0xFF55FFB0), CircleShape))
                    Spacer(Modifier.width(9.dp)); Text(status, color = pale, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("COMMUNICATION LOG", color = cyan, fontSize = 10.sp, letterSpacing = 2.sp)
                    Text("${lines.size} ENTRIES", color = pale.copy(alpha = .45f), fontSize = 9.sp)
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x99040D18)).border(1.dp, cyan.copy(alpha = .15f), RoundedCornerShape(10.dp)).padding(10.dp), reverseLayout = false) {
                    items(lines) { line ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(if (line.role == "user") "YOU / INPUT" else "JARVIS / RESPONSE", color = if (line.role == "user") Color(0xFFFFD66B) else cyan, fontSize = 9.sp, letterSpacing = 1.5.sp)
                            Text(line.text, color = Color(0xFFE1F4FF), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 3.dp))
                            if (line.role == "assistant") Text("▶ 朗读", color = cyan.copy(alpha = .8f), fontSize = 10.sp, modifier = Modifier.clickable { onSpeak(line.text) }.padding(top = 4.dp))
                        }
                        HorizontalDivider(color = cyan.copy(alpha = .10f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(panel).border(1.dp, cyan.copy(alpha = .35f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 14.dp)) {
                        if (input.isEmpty()) Text("输入指令，和 JARVIS 对话…", color = pale.copy(alpha = .4f), fontSize = 13.sp)
                        BasicTextField(value = input, onValueChange = { input = it }, textStyle = TextStyle(color = Color.White, fontSize = 14.sp), modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    }
                    Spacer(Modifier.width(7.dp))
                    RoundAction("🎙") {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchSpeech(speechLauncher)
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    Spacer(Modifier.width(7.dp))
                    RoundAction(if (busy) "…" else "➤", enabled = !busy) { sendMessage() }
                }
            }
            if (showSettings) SettingsDialog(baseUrl, { baseUrl = it }, apiKey, { apiKey = it }, model, { model = it }, {
                prefs.edit().putString("base", baseUrl.trim()).putString("key", apiKey.trim()).putString("model", model.trim()).apply()
                showSettings = false; status = "配置已保存 · ${if (apiKey.isBlank()) "尚未设置 API Key" else "AI 核心已配置"}"
            }, { showSettings = false })
        }
    }
}

@Composable
private fun HoloCore(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "holo")
    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 3500 else 12000, easing = LinearEasing), RepeatMode.Restart), label = "rotation")
    val pulse by transition.animateFloat(.88f, 1.04f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2; val cy = size.height / 2; val r = size.minDimension * .34f * pulse
        drawCircle(Brush.radialGradient(listOf(cyan.copy(alpha = .10f), Color.Transparent), center = androidx.compose.ui.geometry.Offset(cx, cy), radius = r * 1.7f), radius = r * 1.7f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        listOf(.60f, .73f, .88f, 1f, 1.16f).forEachIndexed { i, scale ->
            drawCircle(cyan.copy(alpha = if (i == 3) .8f else .28f), r * scale, androidx.compose.ui.geometry.Offset(cx, cy), style = Stroke(width = if (i == 3) 1.8f else 1f))
        }
        rotate(rotation, androidx.compose.ui.geometry.Offset(cx, cy)) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, cyan, Color.White, Color.Transparent)), -65f, 75f, false, androidx.compose.ui.geometry.Offset(cx-r*1.12f, cy-r*1.12f), androidx.compose.ui.geometry.Size(r*2.24f, r*2.24f), style = Stroke(width = 3f))
            drawArc(cyan.copy(alpha = .75f), 110f, 58f, false, androidx.compose.ui.geometry.Offset(cx-r*.86f, cy-r*.86f), androidx.compose.ui.geometry.Size(r*1.72f, r*1.72f), style = Stroke(width = 2f))
            for (i in 0 until 48) {
                val a = i * 7.5f
                rotate(a, androidx.compose.ui.geometry.Offset(cx, cy)) {
                    drawLine(cyan.copy(alpha = if (i % 4 == 0) .9f else .25f), androidx.compose.ui.geometry.Offset(cx, cy-r*1.04f), androidx.compose.ui.geometry.Offset(cx, cy-r*(if (i%4==0) 1.16f else 1.09f)), strokeWidth = if (i%4==0) 2f else 1f)
                }
            }
        }
        drawCircle(cyan.copy(alpha = .85f), r*.39f, androidx.compose.ui.geometry.Offset(cx, cy), style = Stroke(1.4f))
        drawCircle(Color(0xFF0B3854).copy(alpha = .55f), r*.32f, androidx.compose.ui.geometry.Offset(cx, cy))
        drawLine(cyan.copy(alpha = .25f), androidx.compose.ui.geometry.Offset(cx-r*1.45f, cy), androidx.compose.ui.geometry.Offset(cx+r*1.45f, cy), 1f)
    }
}

@Composable
private fun RoundAction(text: String, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(if (enabled) Color(0xFF0A2940) else Color.DarkGray).border(1.dp, cyan.copy(alpha = .65f), CircleShape).clickable(enabled = enabled, onClick = action), contentAlignment = Alignment.Center) {
        Text(text, color = cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsDialog(base: String, onBase: (String) -> Unit, key: String, onKey: (String) -> Unit, model: String, onModel: (String) -> Unit, save: () -> Unit, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, containerColor = Color(0xFF071729), title = { Text("AI CORE CONFIGURATION", color = cyan, fontSize = 17.sp) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConfigField("API Base URL", base, onBase)
            ConfigField("API Key", key, onKey, secret = true)
            ConfigField("Model name", model, onModel)
            Text("支持 OpenAI 兼容接口。API Key 仅保存在本机应用偏好设置中；请勿将其分享给他人。", color = pale.copy(alpha = .65f), fontSize = 11.sp, lineHeight = 16.sp)
        }
    }, confirmButton = { TextButton(onClick = save) { Text("保存配置", color = cyan) } }, dismissButton = { TextButton(onClick = close) { Text("取消", color = pale) } })
}

@Composable
private fun ConfigField(label: String, value: String, onValue: (String) -> Unit, secret: Boolean = false) {
    Column {
        Text(label, color = cyan.copy(alpha = .85f), fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(value = value, onValueChange = onValue, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(color = Color.White, fontSize = 13.sp), visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None, keyboardOptions = if (label == "API Base URL") KeyboardOptions(keyboardType = KeyboardType.Uri) else KeyboardOptions.Default, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = cyan, unfocusedBorderColor = cyan.copy(alpha = .35f), focusedTextColor = Color.White, unfocusedTextColor = Color.White))
    }
}

private fun launchSpeech(launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出你的指令")
    }
    try { launcher.launch(intent) } catch (_: Exception) { }
}

private fun callChatApi(base: String, key: String, model: String, lines: List<ChatLine>): String {
    if (key.isBlank()) return "连接失败：请先点击右上角设置，填写 API Key 和模型名称。"
    var connection: HttpURLConnection? = null
    return try {
        val root = base.trim().trimEnd('/')
        val endpoint = if (root.endsWith("/chat/completions")) root else "$root/chat/completions"
        connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${key.trim()}")
        }
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", "你是 JARVIS，一位可靠、简洁、礼貌的中文个人 AI 助手。回答默认使用中文。"))
            lines.takeLast(20).forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
        }
        val body = JSONObject().put("model", model.trim()).put("messages", messages).put("temperature", 0.7).toString()
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) {
            val detail = try { JSONObject(response).optJSONObject("error")?.optString("message") ?: response.take(240) } catch (_: Exception) { response.take(240) }
            "连接失败：HTTP $code · $detail"
        } else {
            val json = JSONObject(response)
            json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "AI 暂时没有返回内容。")
        }
    } catch (e: Exception) { "连接失败：${e.localizedMessage ?: "网络异常，请检查接口地址和网络"}" }
    finally { connection?.disconnect() }
}
