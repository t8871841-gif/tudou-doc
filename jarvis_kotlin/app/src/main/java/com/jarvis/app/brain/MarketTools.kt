package com.jarvis.app.brain

import org.json.JSONArray

/**
 * Andee 的 CodeFlying 集市在 Jarvis 里的空壳。
 *
 * Jarvis 不接 CodeFlying（没有那个后端），LocalBrain 的工具列表就是
 * ToolSchemas.all() 那 29 个。空壳只是为了让 LocalBrain 的构造签名不用改：
 * tools() 恒为空数组，hasMethod() 恒为 false。
 */
class MarketTools {
    fun tools(): JSONArray = JSONArray()
    fun hasMethod(name: String): Boolean = false
}
