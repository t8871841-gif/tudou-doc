package com.jarvis.app.memory

import android.content.Context
import com.jarvis.app.JarvisPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
* 批三：长期记忆服务。显式前缀（记住：/记住 /请记住：/请记住 ）拦截写入 + 分域分类 +
* 对话注入（取最近 15 条拼进系统提示词）。自然语言隐式提取未实现（Gemini 首交如实标注，
* Muse 补齐时保留该标注，不假装已做）。记忆存独立 Room 库 jarvis_memory.db，与 Agnes Key 解耦。
*/
object MemoryService {

private fun getDao(ctx: Context) = MemoryDatabase.getDatabase(ctx).memoryDao()

/** 命中显式记忆指令时返回确认语（并已落库），未命中返回 null。 */
suspend fun processExplicitMemory(ctx: Context, input: String): String? = withContext(Dispatchers.IO) {
if (!JarvisPrefs.isMemoryEnabled(ctx)) return@withContext null

val trimmed = input.trim()
val prefixes = listOf("记住：", "记住 ", "请记住：", "请记住 ")
for (prefix in prefixes) {
if (trimmed.startsWith(prefix, ignoreCase = true)) {
val content = trimmed.substring(prefix.length).trim()
if (content.isNotBlank()) {
val domain = classifyDomain(content)
getDao(ctx).insertMemory(MemoryItem(domain = domain.name, content = content))
return@withContext "好的，我已经记住了：“$content”"
}
}
}
return@withContext null
}

private fun classifyDomain(content: String): MemoryDomain {
val lower = content.lowercase()
return when {
lower.contains("我是") || lower.contains("职业") || lower.contains("喜欢") || lower.contains("讨厌") -> MemoryDomain.PROFILE
lower.contains("习惯") || lower.contains("偏好") || lower.contains("总是") -> MemoryDomain.PREFERENCE
lower.contains("生日") || lower.contains("电话") || lower.contains("地址") || lower.contains("号") -> MemoryDomain.FACT
else -> MemoryDomain.GENERAL
}
}

suspend fun getInjectableMemories(ctx: Context): String = withContext(Dispatchers.IO) {
if (!JarvisPrefs.isMemoryEnabled(ctx)) return@withContext ""
val list = getDao(ctx).getAllMemories()
if (list.isEmpty()) return@withContext ""

val sb = StringBuilder("\n\n")
list.take(15).forEach { item ->
sb.append("- [${item.domain}] ${item.content}\n")
}
sb.toString()
}

suspend fun getAllMemories(ctx: Context): List<MemoryItem> = withContext(Dispatchers.IO) {
getDao(ctx).getAllMemories()
}

suspend fun deleteMemory(ctx: Context, id: Long) = withContext(Dispatchers.IO) {
getDao(ctx).deleteMemoryById(id)
}

suspend fun clearAll(ctx: Context) = withContext(Dispatchers.IO) {
getDao(ctx).clearAllMemories()
}
}
