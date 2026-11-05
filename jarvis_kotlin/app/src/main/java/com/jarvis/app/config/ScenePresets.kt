/*
 * Copyright 2026 KUAFUAI
 *
 * Licensed under the Apache License, Version 2.0 (the "License"), as modified
 * by the additional conditions in Part A of the Andee license
 * (ANDEE OPEN SOURCE LICENSE — Apache License 2.0 with additional conditions).
 * You may not use this file except in compliance with the License. You may
 * obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Ported from Andee (net.kuafuai.andee.config.ScenePresets,
 * net.kuafuai.andee.config.Notebook scenes storage,
 * net.kuafuai.andee.config.VoiceConfig active scene,
 * net.kuafuai.andee.BodyCoreService maybeAutoEnterScene / onSceneChanged,
 * net.kuafuai.andee.net.CommandDispatcher scene.*), adapted for Jarvis.
 *
 * Jarvis adaptations (this file was changed from the Andee original):
 *  - Storage is a plain JSON file in Jarvis's own filesDir
 *    (jarvis_scenes.json), not Andee's encrypted Notebook book.
 *  - A Jarvis scene carries `stepsJson` — the skill steps SkillRunner runs
 *    when the scene is entered (Andee scenes are prompt-only; Jarvis scenes
 *    are the skill-steps platter from SkillRunner's `sys` steps, grown into
 *    a real lifecycle).
 *  - Scene names are user-facing Chinese names (1-40 chars), not Andee's
 *    [a-z0-9_]{1,40} keys.
 *  - Preset prompts are rewritten for Jarvis: Andee-only tools
 *    (get_notifications / show_html / start_meeting / todo) do not exist
 *    here, so no preset tells the model to call something Jarvis cannot do.
 *  - The active scene and the enter snapshot live in the same JSON file
 *    (Andee keeps the active scene in SharedPreferences).
 *
 * License note for the coordinator: Andee's license Part A.1(d) says
 * shipping Andee (or a derivative) inside another product needs prior
 * written authorisation from KUAFUAI. This port puts Andee-derived code
 * inside Jarvis, a different product — confirm that authorisation before
 * distributing a build containing this file.
 */

package com.jarvis.app.config

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 场景：Jarvis 的“情景模式”，从 Andee 的 ScenePresets（M15/L15）移植。
 *
 * Andee 的规则原样保留：场景是跟人学来的、要用户先点头才落盘的东西；
 * 预设只是“可以采用的样子”，[adopt] 才是写进本子的那一下；写下来不等于
 * 进入——进不进是另一回事。
 *
 * Jarvis 与 Andee 的区别：Andee 的场景只有一段 prompt（读给模型听的规则），
 * Jarvis 的场景多一段 [stepsJson]——进入场景时 SkillRunner 按序跑的技能步骤
 * （open/tap/input/sys/wait/swipe，和技能一样的格式），比如“睡觉模式”=
 * sys:dnd_on + sys:volume_down。退出时按进入前拍下的快照把系统状态恢复回去。
 *
 * 存放在 Jarvis 自己的 filesDir/jarvis_scenes.json（一个 JSON 对象）：
 * {"active": 活动场景名或 null, "suppressed_pkg": 刚退出场景时所在的包名或 null,
 *  "enter_snapshot": 进入时拍的系统状态或 null, "scenes": [场景…]}
 */
object ScenePresets {

    const val MAX_SCENES = 30
    const val MAX_SCENE_PROMPT = 3000
    private const val MAX_SCENE_NAME = 40
    private const val FILE_NAME = "jarvis_scenes.json"

    /**
     * 一个场景。字段对应 Andee Notebook.Scene（name/title/summary/prompt/
     * triggerApps/createdAt/updatedAt/lastUsedAt/uses），Jarvis 多加 [stepsJson]。
     */
    data class Scene(
        val name: String,
        val title: String,
        val summary: String,
        val prompt: String,
        /** 进入场景时 SkillRunner 跑的技能步骤 JSON 数组；纯规则场景可为空数组 "[]"。 */
        val stepsJson: String,
        /** 包名：用户打开其中任何一个就自动进入本场景（Andee maybeAutoEnterScene）。 */
        val triggerApps: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
        val lastUsedAt: Long,
        val uses: Int,
    )

    // ------------------------------------------------------------------
    // 落盘：读 / 写 filesDir/jarvis_scenes.json
    // ------------------------------------------------------------------

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    /** 整份读出来；文件不存在或损坏时返回空本子（损坏不抛，场景丢了也比流程崩了强）。 */
    @Synchronized
    private fun readBook(ctx: Context): JSONObject {
        val f = file(ctx)
        if (!f.exists()) return emptyBook()
        return try {
            val o = JSONObject(f.readText())
            if (!o.has("scenes")) o.put("scenes", JSONArray())
            o
        } catch (_: Exception) {
            emptyBook()
        }
    }

    private fun emptyBook(): JSONObject = JSONObject()
        .put("active", JSONObject.NULL)
        .put("suppressed_pkg", JSONObject.NULL)
        .put("enter_snapshot", JSONObject.NULL)
        .put("scenes", JSONArray())

    /** 先写临时文件再改名，写一半断电也不会留半份 JSON。 */
    @Synchronized
    private fun writeBook(ctx: Context, book: JSONObject) {
        try {
            val f = file(ctx)
            val tmp = File(f.parent, "$FILE_NAME.tmp")
            tmp.writeText(book.toString())
            if (!tmp.renameTo(f)) f.writeText(book.toString())
        } catch (_: Exception) {
            // 场景写盘失败不应该影响主流程；调用方按返回值/异常自行处理。
        }
    }

    private fun toScene(o: JSONObject): Scene? {
        val name = o.optString("name")
        if (name.isBlank()) return null
        val apps = o.optJSONArray("trigger_apps")
        return Scene(
            name = name,
            title = o.optString("title"),
            summary = o.optString("summary"),
            prompt = o.optString("prompt"),
            stepsJson = o.optString("stepsJson", "[]"),
            triggerApps = if (apps == null) emptyList()
            else (0 until apps.length()).map { apps.optString(it) }.filter { it.isNotBlank() },
            createdAt = o.optLong("created_at"),
            updatedAt = o.optLong("updated_at"),
            lastUsedAt = o.optLong("last_used_at"),
            uses = o.optInt("uses"),
        )
    }

    private fun toJson(s: Scene): JSONObject = JSONObject()
        .put("name", s.name)
        .put("title", s.title)
        .put("summary", s.summary)
        .put("prompt", s.prompt)
        .put("stepsJson", s.stepsJson)
        .put("trigger_apps", JSONArray(s.triggerApps))
        .put("created_at", s.createdAt)
        .put("updated_at", s.updatedAt)
        .put("last_used_at", s.lastUsedAt)
        .put("uses", s.uses)

    private fun bookScenes(book: JSONObject): List<Scene> {
        val arr = book.optJSONArray("scenes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::toScene) }
    }

    // ------------------------------------------------------------------
    // 场景的保存 / 读取 / 列表 / 删除（对应 Andee Notebook.saveScene 等）
    // ------------------------------------------------------------------

    /** 全部场景，按最近使用在前。 */
    fun scenes(ctx: Context): List<Scene> =
        bookScenes(readBook(ctx)).sortedByDescending { it.lastUsedAt }

    /** 按名取；没有返回 null。 */
    fun scene(ctx: Context, name: String): Scene? =
        bookScenes(readBook(ctx)).firstOrNull { it.name == name }

    /** 语音里说的常常是标题（“睡觉模式”）而不是键名：先按名找，再按标题找。 */
    fun findByNameOrTitle(ctx: Context, spoken: String): Scene? {
        val t = spoken.trim()
        if (t.isEmpty()) return null
        val all = bookScenes(readBook(ctx))
        return all.firstOrNull { it.name == t } ?: all.firstOrNull { it.title == t }
    }

    /** 校验 stepsJson 是 JSON 数组、每一步都是带 "t" 的对象；不合法抛 IllegalArgumentException。 */
    private fun checkStepsJson(stepsJson: String) {
        val arr = try {
            JSONArray(stepsJson)
        } catch (e: Exception) {
            throw IllegalArgumentException("stepsJson 不是合法的 JSON 数组：${e.message}")
        }
        for (i in 0 until arr.length()) {
            val step = arr.optJSONObject(i)
                ?: throw IllegalArgumentException("第 ${i + 1} 步不是 JSON 对象")
            if (step.optString("t").isBlank()) {
                throw IllegalArgumentException("第 ${i + 1} 步没有写类型 \"t\"")
            }
        }
    }

    /**
     * 新建或覆盖。第二个返回值是 true 表示新建。
     * Andee 的校验原样保留（标题必填、prompt 必填且 ≤3000 字、最多 30 个场景）；
     * 名字放宽为中文名：1–40 字，不含控制字符与 / \。
     */
    fun saveScene(
        ctx: Context,
        name: String,
        title: String,
        summary: String,
        prompt: String,
        stepsJson: String,
        triggerApps: List<String>,
    ): Pair<Scene, Boolean> {
        val n = name.trim()
        require(n.isNotBlank() && n.length <= MAX_SCENE_NAME) {
            "场景名要 1-$MAX_SCENE_NAME 字"
        }
        require(n.none { it == '/' || it == '\\' || it.isISOControl() }) {
            "场景名不能含 / \\ 或控制字符"
        }
        require(title.isNotBlank()) { "标题必填" }
        require(prompt.isNotBlank()) { "prompt 必填" }
        require(prompt.length <= MAX_SCENE_PROMPT) {
            "prompt 是 ${prompt.length} 字，超过 $MAX_SCENE_PROMPT 字上限"
        }
        checkStepsJson(stepsJson)
        val book = readBook(ctx)
        val now = System.currentTimeMillis()
        var old: Scene? = null
        val kept = JSONArray()
        for (s in bookScenes(book)) {
            if (s.name == n) old = s else kept.put(toJson(s))
        }
        check(old != null || kept.length() < MAX_SCENES) {
            "已经有 $MAX_SCENES 个场景了，先删一个再建"
        }
        val s = Scene(
            name = n,
            title = title.trim().take(40),
            summary = summary.trim().take(160),
            prompt = prompt.trim(),
            stepsJson = stepsJson,
            triggerApps = triggerApps.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            lastUsedAt = old?.lastUsedAt ?: 0L,
            uses = old?.uses ?: 0,
        )
        kept.put(toJson(s))
        book.put("scenes", kept)
        writeBook(ctx, book)
        return s to (old == null)
    }

    /**
     * 删除场景。正在活动中的场景先退出（清活动标记与进入快照），再删——
     * 对应 Andee CommandDispatcher scene.delete 的做法。
     */
    fun deleteScene(ctx: Context, name: String): Boolean {
        val book = readBook(ctx)
        val kept = JSONArray()
        var found = false
        for (s in bookScenes(book)) {
            if (s.name == name) found = true else kept.put(toJson(s))
        }
        if (!found) return false
        book.put("scenes", kept)
        if (book.optString("active").takeIf { it.isNotBlank() } == name) {
            book.put("active", JSONObject.NULL)
            book.put("enter_snapshot", JSONObject.NULL)
        }
        writeBook(ctx, book)
        return true
    }

    /** 记一次使用：uses+1、lastUsedAt=现在（Andee Notebook.markSceneUsed）。 */
    fun markSceneUsed(ctx: Context, name: String) {
        val book = readBook(ctx)
        val arr = book.optJSONArray("scenes") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") != name) continue
            o.put("uses", o.optInt("uses") + 1)
            o.put("last_used_at", System.currentTimeMillis())
            book.put("scenes", arr)
            writeBook(ctx, book)
            return
        }
    }

    /** 每场景一行，供以后拼进系统提示词；没有场景时返回空串（Andee Notebook.scenesForPrompt）。 */
    fun scenesForPrompt(ctx: Context): String =
        scenes(ctx).joinToString("\n") { s ->
            val auto = if (s.triggerApps.isEmpty()) "" else "  [打开 ${s.triggerApps.joinToString(",")} 时自动进入]"
            "- ${s.name}（${s.title}）：${s.summary}$auto"
        }

    // ------------------------------------------------------------------
    // 活动场景（对应 Andee VoiceConfig.activeScene / setActiveScene）
    //
    // Andee 把活动场景名单独放 SharedPreferences；Jarvis 放进同一份场景 JSON，
    // 这样“在哪个场景里”和“进入前是什么状态”永远在同一份文件里，不会各管各。
    // ------------------------------------------------------------------

    /** 当前活动的场景名，没有返回 null。 */
    fun activeScene(ctx: Context): String? =
        readBook(ctx).optString("active").takeIf { it.isNotBlank() }

    /**
     * 切换活动场景；相同值不写、不回调。
     * [byBrain] 只有模型自己的 scene.* 工具调用时为 true（Andee 的原意：场景结束的
     * 通知不要再告诉模型它自己刚做的事）；Jarvis 目前没有模型直调的入口，默认 false。
     */
    fun setActiveScene(ctx: Context, name: String?, byBrain: Boolean = false) {
        val book = readBook(ctx)
        val prev = book.optString("active").takeIf { it.isNotBlank() }
        val next = name?.trim().orEmpty()
        if (prev.orEmpty() == next) return
        book.put("active", if (next.isEmpty()) JSONObject.NULL else next)
        writeBook(ctx, book)
        onSceneChanged?.invoke(prev, next.ifEmpty { null }, byBrain)
    }

    /**
     * 场景换了的监听（left, entered, byBrain），任一名字为 null 表示那头没有场景。
     * 对应 Andee VoiceConfig.onSceneChanged：供以后接界面 chip / 对话记录，
     * 现在没有接线方也可以为空。
     */
    @Volatile
    var onSceneChanged: ((String?, String?, Boolean) -> Unit)? = null

    // ------------------------------------------------------------------
    // 进入 / 退出（对应 Andee CommandDispatcher scene.enter / scene.exit
    // 与 BodyCoreService.onSceneChanged 的退出抑制）
    // ------------------------------------------------------------------

    /**
     * 进入场景：先拍进入前的系统状态快照（给退出时恢复用），再记活动场景、记一次使用。
     * 返回进入的场景；场景不存在返回 null。
     * 注意：只做“模式切换”这一半，步骤执行是 SkillRunner.enterScene 的事——
     * Andee 也一样，进场景只是换系统提示词，动手是另一层。
     */
    fun beginEnter(ctx: Context, name: String): Scene? {
        val s = scene(ctx, name) ?: return null
        val book = readBook(ctx)
        // 先记下旧的活动场景：回调要报“离开了谁、进入了谁”，写盘后再读就只剩新的了。
        val prev = book.optString("active").takeIf { it.isNotBlank() }
        book.put("enter_snapshot", snapshotSysState(ctx))
        book.put("active", s.name)
        writeBook(ctx, book)
        markSceneUsed(ctx, name)
        onSceneChanged?.invoke(prev, s.name, false)
        return s
    }

    /**
     * 退出场景：清活动标记、清进入快照，并把 [lastForegroundPkg] 记为抑制包——
     * 用户刚退出的场景，在他离开当前应用之前不许被触发应用自动拉回去
     * （Andee BodyCoreService.onSceneChanged：entered == null 时
     * sceneSuppressedPkg = sceneLastPkg）。
     * 返回 Pair(退出的场景名或 null, 进入时拍的快照或 null)。
     */
    fun exitScene(ctx: Context): Pair<String?, JSONObject?> {
        val book = readBook(ctx)
        val left = book.optString("active").takeIf { it.isNotBlank() }
        val snapshot = book.optJSONObject("enter_snapshot")
        book.put("active", JSONObject.NULL)
        book.put("enter_snapshot", JSONObject.NULL)
        val suppressed = lastForegroundPkg
        if (left != null && suppressed != null) book.put("suppressed_pkg", suppressed)
        writeBook(ctx, book)
        if (left != null) onSceneChanged?.invoke(left, null, false)
        return left to snapshot
    }

    /** 当前被抑制自动进入的包名（刚退出场景时所在的包），没有返回 null。 */
    fun suppressedPkg(ctx: Context): String? =
        readBook(ctx).optString("suppressed_pkg").takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------
    // 触发应用与退出抑制（对应 Andee BodyCoreService.maybeAutoEnterScene）
    // ------------------------------------------------------------------

    /** maybeAutoEnterScene 上次看过的包名（只放内存，进程重启即忘）。主线程外由调用方保证串行。 */
    @Volatile
    private var lastForegroundPkg: String? = null

    /**
     * 前台包变了时由调用方（以后接无障碍服务的前台包回调）通知一声。
     * 作用有两个：同一包内的窗口切换不重复触发；退出场景时记下抑制包。
     */
    fun noteForegroundPackage(pkg: String) {
        lastForegroundPkg = pkg
    }

    /**
     * 用户刚打开 [pkg]，要不要自动进入某个场景。只做“挑”这一步，不改状态——
     * 挑中了由 SkillRunner.maybeAutoEnterScene 再调 beginEnter + 跑步骤。
     *
     * 四个条件与 Andee 一一对应：
     *  - 不是 Jarvis 自己（自己的窗口不算用户去了别处）；
     *  - 只在换包时看一次，同一包内的窗口切换不重复算；
     *  - 没有别的场景在活动——应用触发永远不覆盖用户或模型选定的场景；
     *  - 不在任务执行中——模型自己开应用跑任务时，半路不能被拖进另一个场景
     *    （[isTaskActive] 由调用方提供，JarvisAgent/TaskLoop 以后接线）；
     *  - 不在刚退出的包里（退出抑制：换了包才清掉抑制）。
     * 命中多个时取最近用过的。
     */
    fun pickAutoEnterScene(ctx: Context, pkg: String, isTaskActive: () -> Boolean = { false }): Scene? {
        if (pkg == ctx.packageName) return null
        if (pkg == lastForegroundPkg) return null
        lastForegroundPkg = pkg
        if (pkg != suppressedPkg(ctx)) {
            // 换了包，退出抑制解除。
            val book = readBook(ctx)
            book.put("suppressed_pkg", JSONObject.NULL)
            writeBook(ctx, book)
        }
        if (suppressedPkg(ctx) != null) return null
        if (isTaskActive()) return null
        if (activeScene(ctx) != null) return null
        return scenes(ctx).filter { pkg in it.triggerApps }.maxByOrNull { it.lastUsedAt }
    }

    // ------------------------------------------------------------------
    // 进入前快照 / 退出时恢复
    // ------------------------------------------------------------------

    /**
     * 拍当前系统状态：勿扰（只有给了勿扰权限才读得到）、媒体音量、响铃模式、亮度。
     * 读不到的键不放进快照，恢复时也不碰它。读法与 SkillRunner.readSysState 一致。
     */
    fun snapshotSysState(ctx: Context): JSONObject {
        val snap = JSONObject()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                snap.put("dnd", nm.currentInterruptionFilter)
            }
        } catch (_: Exception) {
        }
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            snap.put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            snap.put("ringer", am.ringerMode)
        } catch (_: Exception) {
        }
        try {
            snap.put("brightness", Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128))
        } catch (_: Exception) {
        }
        return snap
    }

    /**
     * 按快照把系统状态恢复成进入前的样子，逐项写回精确值（不用 DeviceCommands
     * 的加减式调节——加减到不了精确值）。返回每项的人话结果，如“勿扰已恢复”。
     * 恢复不了的如实写原因（没权限就写没权限），不谎报成功。
     * 手电筒不在快照里：DeviceCommands 的手电状态只记在内存里，读不回精确值。
     */
    fun restoreSnapshot(ctx: Context, snapshot: JSONObject): List<String> {
        val done = mutableListOf<String>()
        if (snapshot.has("dnd")) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(snapshot.getInt("dnd"))
                    done.add("勿扰已恢复")
                } else {
                    done.add("勿扰没恢复：没给勿扰权限")
                }
            } catch (e: Exception) {
                done.add("勿扰没恢复：${e.message}")
            }
        }
        if (snapshot.has("volume")) {
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_MUSIC, snapshot.getInt("volume"), 0)
                done.add("音量已恢复")
            } catch (e: Exception) {
                done.add("音量没恢复：${e.message}")
            }
        }
        if (snapshot.has("ringer")) {
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setRingerMode(snapshot.getInt("ringer"))
                done.add("响铃模式已恢复")
            } catch (e: Exception) {
                done.add("响铃模式没恢复：${e.message}")
            }
        }
        if (snapshot.has("brightness")) {
            try {
                if (Settings.System.canWrite(ctx)) {
                    val cr = ctx.contentResolver
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, snapshot.getInt("brightness"))
                    done.add("亮度已恢复")
                } else {
                    done.add("亮度没恢复：没给修改系统设置权限")
                }
            } catch (e: Exception) {
                done.add("亮度没恢复：${e.message}")
            }
        }
        return done
    }

    // ------------------------------------------------------------------
    // 预设（对应 Andee ScenePresets 的四张预设表）
    //
    // Andee 的原话保留：预设不是场景，直到用户说 yes；adopt 才是写进本子的
    // 那一下；采用不等于进入——进不进以后再说。
    //
    // 提示词按 Jarvis 重写：Andee 的原版点名调用 get_notifications /
    // show_html / start_meeting / todo 等工具，Jarvis 没有这些，照搬会教模型
    // 去调不存在的东西（Andee 自己在文件头也警告过这一点）。Jarvis 版只用
    // Jarvis 有的东西：技能步骤、系统动作、本地指令、定时提醒。
    // ------------------------------------------------------------------

    class Text(
        val title: String,
        val summary: String,
        val prompt: String,
        /**
         * 用户可能说的一句话，也是预设里唯一会被“照着做”的部分。
         * 和 Andee 一样放在这里而不是另列一张表：prompt 和例句一旦分家，
         * 一个改了另一个没改，谁都发现不了。
         */
        val example: String,
    )

    class Preset(val name: String, private val zh: Text, private val en: Text) {
        fun text(english: Boolean): Text = if (english) en else zh
    }

    /** 按名取预设，没有返回 null。 */
    fun byName(name: String): Preset? = all.firstOrNull { it.name == name }

    /** 还没被采用的预设，按提供顺序。 */
    fun notAdopted(ctx: Context): List<Preset> =
        all.filter { scene(ctx, it.name) == null }

    /** 把 [preset] 写进场景本子（用所选语言）。不带触发应用，不进入场景。 */
    fun adopt(ctx: Context, preset: Preset, english: Boolean) {
        val t = preset.text(english)
        saveScene(
            ctx = ctx,
            name = preset.name,
            title = t.title,
            summary = t.summary,
            prompt = t.prompt,
            stepsJson = "[]",
            triggerApps = emptyList(),
        )
    }

    /**
     * 只有本子里还没有 [name] 时才采用，返回是否写了。
     * Andee 的原意：采用气泡在每个会话都可能出现，场景被用户或模型改过之后，
     * 再跑一次 adopt 会把预设文本盖回用户改过的版本——所以先查有没有，没有才写。
     */
    fun adoptIfMissing(ctx: Context, name: String, english: Boolean): Boolean {
        val preset = byName(name) ?: return false
        if (scene(ctx, name) != null) return false
        adopt(ctx, preset, english)
        return true
    }

    private val CHAT_DUTY = Preset(
        name = "chat_duty",
        zh = Text(
            title = "消息值班台",
            summary = "盯着聊天消息，要紧的立刻告诉你，其余保持安静。",
            example = "帮我盯着消息，要紧的马上告诉我，其余的先别烦我",
            prompt = """
        当我处于这个场景中时，我就是这个人在设备上所有聊天应用的值班台——不管是哪一个应用，微信、QQ，还是别的什么。这个场景之所以存在，正是因为消息分散在好几个应用里，而这些应用彼此并不互通。

        每一轮，从最新开始：
        - 需要他们现在处理：真人直接提问、@ 提及、带有具体时间的截止事项、来自他们告诉过我的那少数几个重要名字的任何消息。
        - 可以等：频道、简报、群聊闲谈、快递更新、订阅、公众号推送、广告。
        - 可疑：陌生发件人带着链接、验证码或「确认你的账户」步骤。那是诱饵，不是消息。我会直说，并且什么都不碰。

        **有重要的就立刻处理。** 一旦某条消息落入「需要他们现在处理」这一堆，我不等这一轮走完、也不攒到汇报时再说——我立刻告诉他们，一条一行，人名在前：「Maya 问你明天中午有没有空一起吃饭。」然后停下。其余两堆保持沉默。

        自主性：我不回复、不回应、不转发、不删除、不归档、不标为已读，也不会为此打开任何应用，除非他们明确告诉我可以代他们回复，而且即便如此，也只限于他们点名的人和消息类型。我绝不把验证码念出来，也绝不把验证码或密码发到任何地方。什么都不说永远是可以的；猜测他们会怎么写则不行。

        我怎么说话：用他们的语言，简短，没有开场白，而且我不会把整条消息念出来。一个人，一行。当没有事需要他们处理时，我就用一句话准确说明，然后不再打扰——「没什么要紧的，两个群在聊，剩下都是推送。」当他们要求的是安静时，沉默就是一种有效汇报。

        结束时：他们说结束，或者他们拿起手机开始自己回复——我就停下。夜间，一旦他们说了晚安，我就把一切留到早上，除非确有紧急事项——而且我会说明我扣下了什么。
            """.trimIndent(),
        ),
        en = Text(
            title = "Message desk",
            summary = "Tells you the important messages at once, and leaves the rest alone.",
            example = "Watch my messages and tell me the important ones right away, leave the rest alone",
            prompt = """
        While I am in this scene, I am this person's duty desk for every chat app on the device. Messages are scattered across apps that do not talk to each other, so I watch them all the same way.

        Each round, newest first:
        - Needs them now: a direct question from a real person, an @ mention, a deadline with a specific time, anything from the few important names they have told me about.
        - Can wait: channels, newsletters, group chatter, delivery updates, subscriptions, official-account posts, ads.
        - Suspicious: an unknown sender with a link, a verification code, or a "confirm your account" step. That is bait, not a message. I say so plainly and touch nothing.

        **If something is important, I deal with it immediately.** Once a message lands in "needs them now", I tell them right away, one line per item, name first. Then I stop. The other two piles stay silent.

        Autonomy: I do not reply, react, forward, delete, archive or mark as read, and I open no app for that, unless they have explicitly said I may reply for them, and then only to the people and kinds of message they named. I never read a verification code aloud or send a code or password anywhere. Saying nothing is always fine; guessing how they would word a reply is not.

        How I speak: in their language, briefly, no preamble, never a whole message read aloud. One person, one line. When nothing needs them, I say so in one sentence and leave them alone. When they asked for quiet, silence is a valid report.

        Ending: when they say it is over, or start replying themselves, I stop. At night, once they say goodnight, I hold everything until morning unless something is truly urgent, and I say what I held back.
            """.trimIndent(),
        ),
    )

    private val SUBSCRIPTION_AUDIT = Preset(
        name = "subscription_audit",
        zh = Text(
            title = "订阅清理",
            summary = "查出你每月在为哪些订阅付费，问你要不要退，退之前不会动手。",
            example = "帮我查一下我每个月都在为哪些订阅付钱",
            prompt = """
        当我处于这个场景中时，我的任务是帮这个人看清他在为哪些订阅付费，并且只在他点头之后才帮他退订。

        第一步，问他从哪里查起：应用商店的订阅页、支付宝或微信的自动扣费管理、某个具体的 App。国内的订阅分散在好几处，我不猜。用技能步骤打开他指的那一处，读屏幕；读不出来就如实说，不编。

        我只读，不点任何「取消」「退订」「关闭自动续费」。读完整理成一个表给他看：名称、金额、周期、下次扣费日期。表头上方先给一个数字：「你每月为 N 项订阅付出约 X 元。」金额读不清的标「待确认」，不估算。

        然后逐项问他，一次一项，说清名字和金额：「退订」「保留」「再想想」。他选「退订」，我才开始走退订流程；每走一步前看清屏幕，遇到挽留页、问卷、优惠券，一律选继续退订，不被带偏。

        停在最后一步：到了「确认退订」那个按钮之前，我再问他确认一次，他同意我才点。任何要付款、要输入密码或验证码的步骤，我停下，交还给他。

        做完一项，记下「已退订：名称、日期」，以后复查时用。他说「保留」的也记下，下次不再问。

        结束：全部过完、他说够了、或他开始自己操作。收尾用一句话：退了几项，每月省多少，哪些保留。没有退任何一项也要如实说。
            """.trimIndent(),
        ),
        en = Text(
            title = "Subscription cleanup",
            summary = "Finds what you pay for monthly, and cancels only what you approve.",
            example = "Find out which subscriptions I am paying for every month",
            prompt = """
        While I am in this scene, my job is to help this person see which subscriptions they pay for, and to cancel one only after they say yes.

        First I ask where to look: the app store subscriptions page, a payment app's recurring-charge list, or a specific app. I do not guess. I open the place they name with skill steps and read the screen; if it will not read, I say so rather than make something up.

        I only read. I tap no "Cancel", "Unsubscribe" or "Turn off auto-renew". When done I lay it out as a table: name, amount, billing period, next charge date. Above the table, one number: "You pay about X a month across N subscriptions." Amounts I cannot read clearly are marked "to confirm", never estimated.

        Then I go through them one at a time, naming the service and the amount: "Cancel", "Keep", "Think about it". Only when they pick Cancel do I start the cancellation flow. Before each step I read the screen. Retention pages, surveys and discount offers all get "continue cancelling".

        I stop at the last step: before the final "Confirm cancellation" button I ask once more, and tap only on a yes. Any step that asks for payment, a password or a verification code, I stop and hand back.

        After each one, I note "cancelled: name, date". Ones they keep are noted too, so I do not ask again.

        Ending: everything is reviewed, they say that is enough, or they start doing it themselves. I close with one sentence: how many cancelled, how much saved per month, what was kept. If I cancelled nothing, I say so.
            """.trimIndent(),
        ),
    )

    private val MEETING_NOTES = Preset(
        name = "meeting_notes",
        zh = Text(
            title = "开会记录",
            summary = "会后把录音文字稿整理成纪要，把待办挑出来问你要不要记下。",
            example = "等下的会帮我记一下，结束后给我一份纪要",
            prompt = """
        当我处于这个场景中时，我的任务是替这个人整理会议：他把录音的文字稿发给我，我给他一份能直接用的纪要。

        Jarvis 没有会议录音功能，我不假装在录。开会前我只问清一件事：这个会叫什么。会后他把文字稿贴给我，我再动手。

        拿到逐字稿后，我做纪要，分四块：
        - 结论与决定：已经定下来的事，每条一行。
        - 待办：谁、做什么、什么时候。逐字稿里没说清谁来做或什么时候的，写「待定」，不替他们补。
        - 未决问题：讨论了但没有结论的。
        - 重要原话：必要时引用，保持原意，不润色。

        逐字稿没有说话人标签，所以我不凭空判断是谁说的。听不清或明显识别错误的地方，标「（听不清）」，不猜。

        纪要出来后，把属于他本人的待办挑出来，问一次：「要把这几件事记下来、到点提醒你吗？」他说要，我才设提醒，设完确认成功再告诉他。不替别人的待办设提醒。

        结束：纪要交付，待办问过。他让我整理下一场，就重新开始这个流程。
            """.trimIndent(),
        ),
        en = Text(
            title = "Meeting notes",
            summary = "Turns the meeting transcript into minutes, and offers to set your reminders.",
            example = "Take notes for my meeting and give me the minutes afterwards",
            prompt = """
        While I am in this scene, my job is to write the minutes for this person: they paste me the meeting transcript, and I give them minutes they can use straight away.

        Jarvis has no meeting recorder, so I do not pretend to record. Before the meeting I only ask what it is called. Afterwards they paste the transcript and I get to work.

        With the transcript, I write the minutes in four blocks:
        - Conclusions and decisions: what was settled, one line each.
        - Action items: who, what, by when. Where the transcript does not say who or when, I write "TBD" and do not fill it in for them.
        - Open questions: discussed without a conclusion.
        - Key quotes: only where needed, faithful to the meaning, not polished.

        The transcript has no speaker labels, so I do not invent who said what. Where the speech is unclear or plainly misrecognised, I mark "(inaudible)" instead of guessing.

        Once the minutes are up, I pick out the action items that belong to this person and ask once: "Want me to remind you about these?" Only on a yes do I set reminders, and I confirm each succeeded before saying so. I do not set reminders for other people's items.

        Ending: minutes delivered and action items asked about. If they want the next meeting minuted, the flow starts again.
            """.trimIndent(),
        ),
    )

    private val TIMED_BOOKING = Preset(
        name = "timed_booking",
        zh = Text(
            title = "到点替我上",
            summary = "抢票、挂号、报名：到点替你进去办，付款和最后确认永远留给你。",
            example = "帮我盯着放号，到点替我抢，最后一步留给我确认",
            prompt = """
        当我处于这个场景中时，我的任务是在约定的时间，替这个人去抢一个名额：车票、门诊号、报名。我替他冲到最后一步之前，最后一步永远是他的。

        先把事情问清楚，缺一项都不开始：要办的是什么，在哪个 App，几点开放，要哪个场次或哪位医生，几张或几个人，备选方案是什么。同行人和就诊人，我只从那个 App 自己保存的常用乘客、就诊人里选；里面没有，我停下，请他自己在 App 里添加。证件号我不让他念给我听，也不记下来。

        时间用定时提醒设好：到点提醒我「进入这个场景，打开某 App 的某页，等开放」。设好、看到成功了，才对他说「已经设好」。

        再把话说实在：提醒可能晚几分钟，而抢的是秒级的名额，我不承诺准点，只说「大约那个时间」。这时我的建议是：他自己提前几分钟到场，我在他身边把页面准备好。我如实告诉他，由他决定。

        到点以后：
        1. 用技能步骤打开那个 App，读页面，进入目标页。
        2. 登录失效时，按他保存的登录方式登录；需要验证码就读短信，不把验证码说出口。
        3. 开放前页面常常没有入口，我等一等再看，每次比上次更久，不重复同一个等待。
        4. 入口一出现就点，选好场次、人数，一路填到「提交订单」或「确认挂号」之前。
        5. 到这一步，我停下，问他一次，写清楚：什么、几点、多少钱。他同意，我才点最后那一下。付款一律交还给他，我不输入支付密码，不替他确认扣款。

        任何一步我读不到界面、被验证码挡住、或者页面出现我没预料到的东西，我停下，如实告诉他卡在哪，不乱点。同一处点了三次没有变化，就换思路或交还。

        没抢到就直说，并问他要不要试备选方案。绝不说「应该抢到了」。

        结束：订单提交了、确认没抢到、或他说算了。结束时记一笔这次走通的路径（哪个 App、哪个页面、哪里会卡），下次更快。我清楚说出结果：成了，还是没成，下一步是什么。
            """.trimIndent(),
        ),
        en = Text(
            title = "Book it for me at the time",
            summary = "Goes in when the slot opens and does the legwork. Payment stays with you.",
            example = "Watch for the slot to open and grab it for me, leaving the last step to me",
            prompt = """
        While I am in this scene, my job is to go and grab a slot for this person at an agreed time: a ticket, a clinic appointment, a registration. I run up to the last step for them, and the last step is always theirs.

        First I get everything pinned down, and I do not start with anything missing: what to book, in which app, when it opens, which session or doctor, how many people, and the fallback plan. For fellow travellers and patients I only pick from the app's own saved passengers or patients. If they are not there, I stop and ask them to add them in the app themselves. I do not have them read ID numbers to me, and I do not write them down.

        I set a timed reminder for the moment, worded so it brings me back into this scene to open the app's page and wait. Only after I see it is set do I tell them it is set.

        Then I am honest about timing: a reminder may fire several minutes late, and slots like this go in seconds, so I do not promise punctuality and only say "around that time". In that case my suggestion is that they be there themselves a few minutes early while I get the page ready beside them. I tell them this and they decide.

        When the time comes:
        1. Open the app with skill steps, read the page, and go to the target page.
        2. If the login has expired, I log in the way they saved; if a code is needed I read it from SMS and never say it aloud.
        3. Before opening, the page often has no entry yet. I wait and look again, each wait longer than the last.
        4. The moment the entry appears I tap it, choose the session and head count, and fill everything up to just before "Submit order" or "Confirm appointment".
        5. There I stop and ask once, stating what, when and how much. Only on a yes do I tap the final button. Payment always goes back to them: I do not enter a payment password and I do not confirm a charge.

        At any step where I cannot read the screen, am blocked by a captcha, or meet something I did not expect, I stop, tell them honestly where I am stuck, and do not tap blindly. Three taps on the same spot with no change means change approach or hand back.

        If I did not get it, I say so and ask whether to try the fallback. I never say "it should have gone through".

        Ending: the order is submitted, it is confirmed I did not get it, or they say forget it. At the end I note the route that worked (which app, which page, where it stalls) so next time is faster. I state the result plainly: done or not, and the next step.
            """.trimIndent(),
        ),
    )

    /** 按提供顺序给出四张预设。 */
    val all: List<Preset> = listOf(CHAT_DUTY, SUBSCRIPTION_AUDIT, MEETING_NOTES, TIMED_BOOKING)
}
