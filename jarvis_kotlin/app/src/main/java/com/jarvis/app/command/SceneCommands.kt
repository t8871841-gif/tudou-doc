/*
 * Copyright 2026 KUAFUAI
 *
 * Licensed under the Apache License, Version 2.0 (the "License"), as modified
 * by the additional conditions in Part A of the Andee license
 * (ANDEE OPEN SOURCE LICENSE — Apache License 2.0 with additional conditions).
 * You may not use this file except in compliance with the License. You may
 * obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Ported from Andee (net.kuafuai.andee.net.CommandDispatcher scene.* and
 * net.kuafuai.andee.BodyCoreService scene handling), adapted for Jarvis.
 *
 * Jarvis adaptations: Andee 的 scene.* 是模型调的工具（scene.list / get /
 * save / enter / exit / delete）；Jarvis 没有模型直调的工具入口，所以这里是
 * 语音命令入口——用户说“进入睡觉模式场景”就走这里。命令层只认中文句式，
 * 真正的状态机（保存/进入/退出/抑制）全在 ScenePresets，步骤执行在
 * SkillRunner，本文件只做句式匹配与播报。
 *
 * 接线（由总协调加，不在本文件里）：LocalCommands.tryHandle 里，
 * ReminderCommands.tryHandle 命中判断之后、拆解指令判断之前，加一行
 * `if (SceneCommands.tryHandle(ctx, t, speak)) return true`。
 * 注意传的是剥掉口语前缀后的 `t`，不是原句 `text`。
 */

package com.jarvis.app.command

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jarvis.app.AuditLog
import com.jarvis.app.config.ScenePresets
import com.jarvis.app.skill.SkillRunner
import java.util.concurrent.Executors

/**
 * 场景语音命令入口。命中返回 true（已接手），没命中返回 false（交还给聊天流程）。
 *
 * 认的句式（“场景/情景”通用）：
 * - “列出场景 / 有哪些场景 / 我的场景”——列出场景
 * - “当前场景 / 现在是什么场景”——报正在用的场景
 * - “进入睡觉模式场景 / 进入场景睡觉模式”——进入场景
 * - “退出场景 / 离开场景 / 结束场景”——退出场景
 * - “新建场景睡觉模式 / 创建场景睡觉模式”——新建空场景
 * - “采用消息值班台场景”——采用一张预设（没采用过才写）
 * - “删除场景睡觉模式”——删除场景
 *
 * 不认的直接返回 false，不抢聊天的话——比如“进入微信”没有“场景”二字，
 * 不会落到这里。
 */
object SceneCommands {

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "SceneCommands").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private val ENTER_A = Regex("^进入(.+?)(场景|情景)$")
    private val ENTER_B = Regex("^进(.+?)(场景|情景)$")
    private val ENTER_C = Regex("^进入(场景|情景)(.+)$")
    private val EXIT_NAMED = Regex("^(退出|离开|结束)(.+?)(场景|情景)$")
    private val DELETE_A = Regex("^(删除|删掉)(场景|情景)(.+)$")
    private val DELETE_B = Regex("^(删除|删掉)(.+?)(场景|情景)$")
    private val CREATE_A = Regex("^(新建|创建|新加)(场景|情景)(.+)$")
    private val CREATE_B = Regex("^(新建|创建|新加)(.+?)(场景|情景)$")
    private val ADOPT = Regex("^采用(场景|情景)?(.+)$")

    fun tryHandle(ctx: Context, text: String, speak: (String) -> Unit): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false

        // 当前在什么场景。
        if (t == "当前场景" || t == "现在是什么场景" || t == "在什么场景" || t == "现在在什么场景") {
            io.execute {
                val active = ScenePresets.activeScene(ctx)
                val msg = if (active == null) {
                    "现在没在任何场景里"
                } else {
                    val s = ScenePresets.scene(ctx, active)
                    "现在在「${s?.title ?: active}」场景里"
                }
                main.post { speak(msg) }
            }
            return true
        }

        // 列出场景。
        if (t.contains("场景列表") || t.contains("情景列表") ||
            t.contains("有哪些场景") || t.contains("有哪些情景") ||
            t.contains("列出场景") || t.contains("列出情景") ||
            t.contains("查看场景") || t.contains("查看情景") ||
            t.contains("场景有哪些") || t.contains("情景有哪些") ||
            t == "我的场景" || t == "我的情景"
        ) {
            io.execute {
                val all = ScenePresets.scenes(ctx)
                val active = ScenePresets.activeScene(ctx)
                val msg = if (all.isEmpty()) {
                    "还没有场景，可以说「采用消息值班台场景」先试一个预设"
                } else {
                    val names = all.joinToString("、") { s ->
                        if (s.name == active) "「${s.title}」（正在用）" else "「${s.title}」"
                    }
                    "现在有 ${all.size} 个场景：$names"
                }
                main.post { speak(msg) }
            }
            return true
        }

        // 退出场景（“退出场景”或“退出睡觉模式场景”；只退当前活动的那个，
        // 名字对不上也照样退——退出说的是“不待了”，不是“删了它”）。
        if (t.contains("退出场景") || t.contains("退出情景") ||
            t.contains("离开场景") || t.contains("离开情景") ||
            t.contains("结束场景") || t.contains("结束情景") ||
            EXIT_NAMED.matches(t)
        ) {
            io.execute {
                val msg = SkillRunner.exitScene(ctx)
                AuditLog.append(ctx, "场景", "退出场景", msg)
                main.post { speak(msg) }
            }
            return true
        }

        // 删除场景。
        val deleteName = DELETE_A.matchEntire(t)?.groupValues?.get(3)
            ?: DELETE_B.matchEntire(t)?.groupValues?.get(2)
        if (deleteName != null) {
            val name = cleanSceneSpoken(deleteName)
            io.execute {
                val target = resolveScene(ctx, name)
                if (target == null) {
                    main.post { speak("没找到叫「$name」的场景，删不了。${listHint(ctx)}") }
                } else {
                    val wasActive = ScenePresets.activeScene(ctx) == target.name
                    ScenePresets.deleteScene(ctx, target.name)
                    AuditLog.append(ctx, "场景", "删除场景「${target.name}」", if (wasActive) "正在用的场景，已退出并删除" else "成功")
                    val msg = if (wasActive) "已退出并删除了「${target.title}」" else "已删除「${target.title}」"
                    main.post { speak(msg) }
                }
            }
            return true
        }

        // 新建场景（空场景：没有步骤和规则，先占个名）。
        val createName = CREATE_A.matchEntire(t)?.groupValues?.get(3)
            ?: CREATE_B.matchEntire(t)?.groupValues?.get(2)
        if (createName != null) {
            val name = cleanSceneSpoken(createName)
            io.execute {
                try {
                    val (s, created) = ScenePresets.saveScene(
                        ctx = ctx,
                        name = name,
                        title = name,
                        summary = "新建场景，步骤和规则还没填",
                        prompt = "（新建场景：还没有写规则和步骤）",
                        stepsJson = "[]",
                        triggerApps = emptyList(),
                    )
                    AuditLog.append(ctx, "场景", "新建场景「${s.name}」", if (created) "成功" else "已存在，覆盖为空场景")
                    main.post {
                        speak(
                            if (created) "已新建场景「${s.title}」，现在是空的，还没有步骤和规则"
                            else "「${s.title}」已经有了，刚把它清空成了一个空场景"
                        )
                    }
                } catch (e: IllegalArgumentException) {
                    main.post { speak("新建场景失败：${e.message}") }
                }
            }
            return true
        }

        // 采用预设。
        val adoptName = ADOPT.matchEntire(t)?.groupValues?.get(2)
        if (adoptName != null) {
            val spoken = cleanSceneSpoken(adoptName)
            io.execute {
                val preset = ScenePresets.byName(spoken)
                    ?: ScenePresets.all.firstOrNull { it.text(false).title == spoken }
                if (preset == null) {
                    val titles = ScenePresets.all.joinToString("、") { it.text(false).title }
                    main.post { speak("没找到这个预设，能采用的有：$titles") }
                } else if (ScenePresets.adoptIfMissing(ctx, preset.name, false)) {
                    AuditLog.append(ctx, "场景", "采用预设「${preset.text(false).title}」", "成功")
                    main.post { speak("已采用「${preset.text(false).title}」，说「进入${preset.text(false).title}场景」就能用") }
                } else {
                    main.post { speak("「${preset.text(false).title}」已经在你的场景里了") }
                }
            }
            return true
        }

        // 进入场景。
        val enterName = ENTER_A.matchEntire(t)?.groupValues?.get(1)
            ?: ENTER_B.matchEntire(t)?.groupValues?.get(1)
            ?: ENTER_C.matchEntire(t)?.groupValues?.get(2)
        if (enterName != null) {
            val name = cleanSceneSpoken(enterName)
            io.execute {
                val target = resolveScene(ctx, name)
                if (target == null) {
                    main.post { speak("没找到叫「$name」的场景。${listHint(ctx)}") }
                    return@execute
                }
                SkillRunner.enterScene(ctx, target) { ok, detail ->
                    val msg = when {
                        ok && detail.isBlank() -> "已进入「${target.title}」"
                        ok -> "已进入「${target.title}」：$detail"
                        else -> "进了「${target.title}」，但步骤没跑完：$detail"
                    }
                    AuditLog.append(ctx, "场景", "进入场景「${target.title}」", msg)
                    main.post { speak(msg) }
                }
            }
            return true
        }

        return false
    }

    /**
     * 语音里说的场景名清理：去首尾空格，去掉句尾多余的“场景/情景/模式”。
     * “睡觉模式场景”→“睡觉模式”；“睡觉场景”→“睡觉”。
     */
    private fun cleanSceneSpoken(raw: String): String {
        var s = raw.trim()
        s = s.removeSuffix("场景").removeSuffix("情景").trim()
        return s
    }

    /**
     * 按用户说的找场景：先按名/标题精确找，再试补一个“模式”
     * （用户常把“睡觉模式”说成“睡觉”）。
     */
    private fun resolveScene(ctx: Context, spoken: String): ScenePresets.Scene? {
        if (spoken.isEmpty()) return null
        return ScenePresets.findByNameOrTitle(ctx, spoken)
            ?: ScenePresets.findByNameOrTitle(ctx, "${spoken}模式")
    }

    /** “没找到”时顺带告诉用户现在有什么，供播报拼接。 */
    private fun listHint(ctx: Context): String {
        val all = ScenePresets.scenes(ctx)
        return if (all.isEmpty()) {
            "现在一个场景都没有，可以说「采用消息值班台场景」先试一个预设"
        } else {
            "现在有：${all.joinToString("、") { it.title }}"
        }
    }
}
