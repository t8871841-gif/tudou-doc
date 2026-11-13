package com.jarvis.app.brain

import org.json.JSONArray

/**
 * Andee 的扩展工具注册表在 Jarvis 里的空壳。
 *
 * Jarvis 没有 call extension（第三方给大脑加工具的注册机制），LocalBrain
 * 的工具列表就是 ToolSchemas.all() 那 29 个。空壳只是为了让 LocalBrain
 * 的构造签名不用改：tools() 恒为空数组，hasMethod() 恒为 false。
 */
class CallExtensionRegistry {
    fun tools(): JSONArray = JSONArray()
    fun hasMethod(name: String): Boolean = false
}
