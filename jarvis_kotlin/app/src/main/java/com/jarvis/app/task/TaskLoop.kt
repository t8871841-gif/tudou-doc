package com.jarvis.app.task

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.jarvis.app.AuditLog
import com.jarvis.app.JarvisAccessibilityService
import com.jarvis.app.command.LocalCommands
import com.jarvis.app.net.LlmRegistry
import com.jarvis.app.screen.ScreenCapture
import com.jarvis.app.screen.ScreenController
import com.jarvis.app.screen.TapMarkerUi
import com.jarvis.app.TakeoverBorder
import com.jarvis.app.vision.GridOverlay
import com.jarvis.app.vision.OcrReader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跨应用任务循环。批九初版是自写的简化循环；批十四按用户要求把 Andee 的循环
 * 内核（BodyCoreService + brain/LocalBrain + net/ToolSchemas 的做法）移植进来，
 * 与现状不同处以它为准：
 *
 * - 步数上限 25（Andee VoiceConfig.DEFAULT_MAX_STEPS），不再是 12。
 * - 相同动作签名连续重复守卫（LocalBrain 的 MAX_REPEATS=3）：同一个动作连着
 *   发第 4 次之前就中止并如实回报在打转，不让它原地耗满步数。无法解析的输出
 *   也算一种签名，连着来一样触发。
 * - 模型输出不合规：带原因立即纠偏重试一次（原有做法，保留）；再不行记为一
 *   步失败继续循环（Andee 是把错误当工具结果回喂让模型自纠），不再整场中止。
 * - 历史回喂：每步把「动作 + 结果摘要」记进下一步提示词，结果摘要尽量透传
 *   ScreenController 自己的判定（screen_changed / now_pkg / elements_hint /
 *   snapped）；历史段有字符预算，超了从最旧的整步丢弃并注明（对应 LocalBrain
 *   的 MAX_HISTORY_CHARS 截断思路；本工程是一次性提示词，预算按单条提示词定）。
 * - 截图策略（ToolSchemas 的纪律）：界面树大纲是默认观察，截图只在树为空
 *   （自绘界面/被扣留）、上一步失败或界面没变化需要核对、或 zoom 要求时才带；
 *   任务大脑（GLM / 自定义）都带图问。无障碍截图拿不到图而录屏通道
 *   （[ScreenCapture]，批十四移植）可用时用它兜底，图上无网格、只许看不许按
 *   图瞄准，提示词里会标明来源；隐私黑名单包（isShotBlocked）两条通道都不拍。
 *
 * 模型每步仍只能输出一个 JSON 动作（本工程两个 Client 都是单轮文本接口，
 * 没有原生 tool-calling，协议不变）。一次 run 的生命周期：优先用无障碍服务持有
 * 的长期 ScreenController（拿不到才新建一个注册上去）→ 接管边框亮起 → 循环 → done/fail/急停
 * 收尾并注销（共享的控制器不注销）。急停复用 JarvisAgent 的代次序号 + 本类 runSeq 双保险。
 */
object TaskLoop {

    /** 步数上限：Andee 的 DEFAULT_MAX_STEPS。 */
    private const val MAX_STEPS = 25

    /** 续跑累计步数上限（M20）：新跑 + 续跑加起来不超过 50 步，免得断点一直续下去。 */
    private const val MAX_TOTAL_STEPS = 50

    /** 相同动作签名连续重复的容忍次数（LocalBrain MAX_REPEATS）：达到即判打转中止。 */
    private const val MAX_REPEATS = 3

    /** 第八批·遍历环检测：同一（界面指纹，动作签名）对第几次出现时停下任务（第 1 次只记录，
     * 第 2 次命中警告并记一步失败，第 3 次直接停下、不再执行）。 */
    private const val LOOP_STOP_OCCURRENCES = 3
    /** 第八批·遍历环检测：本轮任务内记住的（界面，动作）对上限，超了丢最旧的，
     * 别让长任务无限涨内存；单轮 runBlocking 最多 25 步，正常到不了这个数。 */
    private const val MAX_LOOP_PAIRS = 64

    /** 第一批·计划：子目标列表上限（MA-v3 式计划，提示词预算内单独限小）。 */
    private const val MAX_PLAN_ITEMS = 10
    /** 第一批·已完成子目标保留上限，防列表无限长。 */
    private const val MAX_COMPLETED_ITEMS = 20
    /** 第十二批·Scratchpad 持久笔记：笔记条数上限与单条字数上限（与第一批的计划段同款上限，防提示词膨胀）。 */
    private const val MAX_NOTE_ITEMS = 10
    private const val MAX_NOTE_CHARS = 60
    /** 第一批·连续失败升级阈值（MA-v3 err_to_manager_thresh=2）。 */
    private const val STUCK_THRESHOLD = 2
    /** 第二批·Reflector 精简版：核验超时（短问答，不用等满 200 秒）。 */
    private const val REFLECT_TIMEOUT_SEC = 60L
    /** 第二批·核验提示词里前后界面摘要各自的上限（短调用，不塞全量历史）。 */
    private const val REFLECT_COMPACT_CHARS = 1200
    /** 第二批·(b) 信号只对“应当引起界面变化”的动作核验：wait/zoom/find 等本身就不指望界面变，不核验；浏览器类动作看的是网页快照、树 diff 不准，也不核验；flow 的 screenChanged 恒为 null，本来也不会触发。 */
    private val REFLECT_CHANGE_KINDS = setOf("tap", "longPress", "type", "submit", "swipe", "scroll_to", "tap_sel", "openApp", "back", "home")
    /** 第六批·每轮核对前台包名：本来就会换前台应用的动作（openApp/back/home），包名变了是预期切换、不算跑偏。
     * flow 不在这里：流内步骤混着切应用与屏内动作，单看 kind 分不出来，执行后按流内实际步骤另判（见 flowHasAppSwitch）。 */
    private val EXPECTED_PKG_SWITCH_KINDS = setOf("openApp", "back", "home")
    /** 第二批·核验员系统提示词（短问答专用，走注册表的聊天 client——GLM 模式下任务 client 只回 do(...)/finish(...) 动作串，问不出 A/B/C，聊天模型才能回自由文本）。 */
    private const val REFLECT_SYSTEM_PROMPT =
        "你是 Jarvis 任务核验员，只判断上一步动作到底成没成，不执行任何动作。" +
        "只输出一个字母加一句说明：A 成功/符合期望；B 到了错误页面、点错了地方；C 界面没有任何变化、动作没生效。" +
        "不要输出其他文字，不要解释推理过程。"

    /** 第十四批·Manager 系统提示词（短问答专用，与第二批 Reflector 同款调用方式：
     * 走注册表的聊天 client，聊天模型回自由文本——GLM 模式下任务 client 只回
     * do(...)/finish(...) 动作串，问不出计划块。Manager 只规划、不执行动作，
     * 只回计划块（第一批的计划段同款格式），不回 JSON 动作。 */
    private const val MANAGER_SYSTEM_PROMPT =
        "你是 Jarvis 任务规划员（Manager），只负责规划，不执行任何动作。" +
        "只输出一个计划块，不要输出 JSON 动作、不要解释推理过程。格式：\n" +
        "计划：\n" +
        "1. 第一步（一句话）\n" +
        "2. ✓ 已完成的子目标\n" +
        "3. 下一步\n" +
        "已完成的子目标在行首标 ✓，没做完的照常列；卡住时修订计划（重排、改写、插入纠正步骤、换策略）。最多 10 条，每条一句话。"

    /** 第十四批·Manager 单独问一次的触发原因（只用于审计日志里的中文说明）。 */
    private enum class ManagerTrigger(val label: String) {
        EMPTY_PLAN("计划为空"),
        STUCK("连续失败升级"),
        DONE_CHALLENGED("done 核验没通过")
    }

    /**
     * 无进展计数（Andee 对齐后不再熔断 abort）：原来这里有一层“连续 7 步无真交互
     * 就强制 fail”的熔断，是 2026-10-24 微信任务故障追查后加的；但 Andee 没有这层
     * （只有重复守卫 + 25 步上限兜底），且熔断会误伤需要多轮等待的慢任务。
     * 2026-10-25 起按 Andee 照搬：计数保留，只用于“连续 2 轮无真交互时强制带图”
     * （截图策略），让模型有图可破局；真正的兜底是 MAX_STEPS 与重复守卫。
     * 没有把 swipe 算进“真操作”——滑动本身更像是在找东西，不一定代表任务在往前推进。
     */
    private val REAL_ACTION_KINDS = setOf("tap", "longPress", "type", "zoom", "flow", "browse", "browserTap", "browserBack")

    private const val MODEL_TIMEOUT_SEC = 200L // Andee 对齐：客户端读超时 180 秒，闩锁须大于它才不先成瓶颈
    private const val COMPACT_CHARS = 2500

    /** 历史段字符预算（单条提示词内，含任务与大纲之外的余量）。M22：Andee 同款 60_000。 */
    private const val HISTORY_CHARS = 60000

    /** 每步结果摘要进历史时的截断（Andee 单条工具结果上限 24_000，M22 同款）。 */
    private const val STEP_RESULT_CHARS = 24000

    /** H8：单步执行超时（Andee dispatchWithTimeout 的 DEFAULT_TOOL_TIMEOUT_MS）。 */
    private const val STEP_TIMEOUT_MS = 60_000L
    /** H8：工具自带 timeout_ms 时加的 slack（Andee TIMEOUT_SLACK_MS）。 */
    private const val STEP_TIMEOUT_SLACK_MS = 10_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-taskloop").apply { isDaemon = true }
    }
    /** H8：单步执行用的线程池（cached：flow 步里 execute 会嵌套再调 execute，单线程池会死锁）。 */
    private val stepExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "jarvis-taskstep").apply { isDaemon = true }
    }
    private val runSeq = AtomicInteger(0)

    /** 任务循环是否正在执行（彻底融合第 3 步：HUD Agent 调度面板要读的真状态）。
     * 在 runBlocking 的 try 之前置位、finally 里复位；无障碍未开那条早退路在置位之前，
     * 不会把标志留在 true。 */
    @Volatile
    private var running = false
    fun isRunning(): Boolean = running

    // 差距 8e 的守卫要认“在跑的那个任务是不是还活着”：新任务顶掉旧任务是正常流程
    // （JarvisAgent 先 hardResetToIdle，旧任务的 isAlive 已死），只拦“旧任务还活着
    // 又来一个新任务”。currentAlive/currentAliveSeq 只由 run() 写、runBlocking 的
    // finally 按序号清（序号对不上不碰，免得先到的 finally 把后到的任务标志清掉）。
    @Volatile
    private var currentAlive: (() -> Boolean)? = null
    @Volatile
    private var currentAliveSeq = -1

    // 「在<应用>里/上给<人>发信息…」「给<人>发微信…」「打开/用<应用>…发/给…」。
    // M25：「发信息给老婆」——"发"在前、"给/向/跟+人名"在后的词序，SEND_TO 只认"给"在"发"前。
    // 保守优先：必须同时出现「应用或发送动作」与「收件/发送对象」两类信号才算任务；
    // 拿不准放行原流程（聊天/技能/单步指令）。
    private val IN_APP_SEND = Regex("在.{1,12}?(上|里|中).{0,10}?(给|向|跟).{1,16}?(发信息|发消息|发微信|发短信|发送|留言)")
    private val SEND_TO = Regex("(给|向|跟).{1,16}?(发微信|发信息|发消息|发短信|发送消息|发条消息)")
    private val SEND_AFTER = Regex("发(个|条|送)?(信息|消息|微信|短信).{0,8}?(给|向|跟).{1,16}?")
    private val OPEN_THEN = Regex("^(打开|开启|启动|进入|用).{1,14}?(给|向|发|帮我).{2,}")

    /** 任务句式判定（JarvisAgent 路由用）。纯聊天、单步打开应用、提问句都不算。 */
    fun looksLikeTask(text: String): Boolean {
        val t = text.trim()
        if (t.length < 6) return false
        if (t.contains('？') || t.contains('?')) return false
        return IN_APP_SEND.containsMatchIn(t) ||
            SEND_TO.containsMatchIn(t) ||
            SEND_AFTER.containsMatchIn(t) ||
            OPEN_THEN.containsMatchIn(t)
    }

    /** 急停：自增序号，正在跑的循环下一步检查时自行中止（与 Agent 的 genSeq 急停并行）。 */
    fun cancelInFlight() {
        runSeq.incrementAndGet()
    }

    /**
     * 执行一个多步任务。[isAlive] 由调用方给（Agent 的代次检查）；[onReply] 只会被
     * 调用一次，内容是给用户的一句结果（完成说明或卡在哪一步的如实说明）。
     * [onProgress] 是 M18 的每步播报：每步执行前按动作种类推一句（如“正在点按……”），
     * 调用方拿它推字幕/气泡，不要只在最后调一次 onReply。
     * [resumeFrom] 非空时是从断点续跑：历史、步数与计划接着上次的来（见 TaskResumeState，
     * 第四批·断点升级：plan/completed/screenSig 一起恢复）。
     * [saveResume] 为 false 时不写断点（也不清旧断点）：内部子任务用，
     * 免得顶掉用户断点（S16，如 TripLoop 的高德核验）。
     * 注意：onReply 必须放最后——调用方用尾随 lambda 传它，后面再加参数会
     * 让尾随 lambda 绑错（jk-v55 的教训）。onProgress 带默认值、放在 onReply
     * 之前，尾随 lambda 仍绑到 onReply。
     */
    fun run(
        ctx: Context, taskText: String, isAlive: () -> Boolean,
        resumeFrom: TaskResumeState.ResumeInfo? = null,
        saveResume: Boolean = true,
        onProgress: (String) -> Unit = {},
        onReply: (String) -> Unit
    ) {
        // 差距 8e：running 守卫——在跑的任务还活着时直接回绝，不递增 runSeq（递增会杀死
        // 在跑任务的 alive 检查、让它的 finally 在新任务还在跑时把球恢复，把两个任务的
        // 沉底态搅在一起）。旧任务已被顶掉（isAlive 已死，如新指令前的 hardReset）时放行，
        // 单线程执行器保证旧任务的 finally 先于新任务的 runBlocking 跑完。
        if (running && currentAlive?.invoke() == true) {
            onReply("已经有一个任务在进行中，等它做完或喊停再说新的。")
            return
        }
        val mySeq = runSeq.incrementAndGet()
        currentAlive = isAlive
        currentAliveSeq = mySeq
        val appCtx = ctx.applicationContext
        // 新任务开始清掉旧的断点（续跑的那次不调这里，resumeFrom 非空）；否则
        // 上次停下的任务会一直挂着等“继续”，用户说了新任务还会被误续。
        // saveResume=false 是内部子任务（TripLoop 高德核验）：不碰断点状态，
        // 既不清除用户原来的断点，也不写自己的断点（S16）。
        if (resumeFrom == null && saveResume) TaskResumeState.clear()
        executor.execute {
            runBlocking(appCtx, taskText, mySeq, isAlive, onProgress, onReply, resumeFrom, saveResume)
        }
    }

    private fun runBlocking(
        ctx: Context, taskText: String, mySeq: Int,
        isAlive: () -> Boolean, onProgress: (String) -> Unit, onReply: (String) -> Unit,
        resumeFrom: TaskResumeState.ResumeInfo? = null,
        saveResume: Boolean = true
    ) {
        fun alive() = mySeq == runSeq.get() && isAlive()
        val svc = JarvisAccessibilityService.instance
        if (svc == null) {
            // 差距 8e：早退路也在置位之前——但 run() 已登记了 currentAliveSeq，自己的那份要清掉，
            // 否则 running 会一直留在 true、把后面的任务全挡在守卫外。
            if (currentAliveSeq == mySeq) {
                running = false
                currentAlive = null
                currentAliveSeq = -1
            }
            onReply("无障碍服务没有开启，跨应用任务做不了。请到设置里打开 Jarvis 无障碍服务。")
            return
        }
        // M14：不要每步新建 ScreenController——优先用无障碍服务持有的长期控制器
        // （服务路约定暴露的公开属性）；拿不到时才新建一个当兜底，并照旧注册上去、
        // 结束时注销（共享的那个不是我们建的，结束时不注销，留给服务继续用）。
        // 构造签名 (service, marker, ownArea)：ownArea 用默认（不遮罩自家悬浮球区域）。
        val existingController = JarvisAccessibilityService.screenController
        val controller = existingController ?: ScreenController(svc, TapMarkerUi(svc))
        val ownsController = existingController == null
        if (ownsController) JarvisAccessibilityService.screenController = controller
        AuditLog.append(ctx, "任务循环", taskText, "开始（上限 $MAX_STEPS 步）")
        TakeoverBorder.show()
        // 跨应用任务期球沉底沿（右下角，留半个球可见、可点；任务结束回原位）——
        // 不是彻底隐藏：沉底才能让球不挡点按落点、又不断任务进度反馈。
        // 截图空白是另一层标志，互不顶掉（见 OrbController）。
        com.jarvis.app.OrbController.setTaskHidden(true)
        // 断点续跑：历史与步数接着上次的来；新任务从空开始。
        val history = resumeFrom?.history?.toMutableList() ?: mutableListOf<String>()
        // 停下时要播报“说继续可以接着做”：onReply 平时只在 alive() 时才发，
        // 急停分支会把 finalReply 置 null 抢掉播报；用这个旗子让 finally
        // 在急停时也把断点播报发出去。
        var stoppedWithResume = false
        var lastError = ""
        var pendingImage: String? = null // zoom 放大图：作为下一轮的观察图喂回模型
        var finalReply: String? = null
        // 重复守卫（LocalBrain 同款：数的是动作签名，不是点的位置——那层在
        // ScreenController 里另有计数）：同一签名连着来，repeats 每次 +1，
        // 达到 MAX_REPEATS 时在执行前拦下中止。
        var lastSignature = ""
        var repeats = 0
        // 第八批·遍历环检测：本轮任务内已走过的（界面指纹 screenSig，动作签名）对
        // 到出现次数的映射（LinkedHashMap 插入顺序，超 MAX_LOOP_PAIRS 时丢最旧的）。
        // 只放本轮任务内：新任务从空开始，续跑不继承（断点不存它，不改 TaskResumeState）；
        // key 是 screenSig 全文 + '\u0000' + 签名全文，界面指纹按全量 elements 比，不用
        // 哈希（哈希碰撞会误停任务）。只在“界面确实回到之前的状态”时算环：key 里含
        // screenSig，同一签名在不同界面上做是不同的对；连续重复（签名连着上一步）
        // 不进这里，仍归重复守卫（见执行前的 consecutiveSameSig）。
        val loopSeen = LinkedHashMap<String, Int>()
        // 无进展计数：连续多少步没有出现 tap/longPress/type/zoom 这类真交互，
        // 见上方的说明。任何一步出现这几种动作就清零；计数只用于强制带图。
        var noProgressSteps = 0
        // 2026-10-24 跨应用修复第 2 件：问模型连续失败计数（网络 timeout 之类），
        // 旧代码一次失败就直接 return 判死整轮任务；现在允许连着失败 2 次才判死，
        // 中间那次记一步失败继续（下一轮重新观察、重新问）。
        var askFails = 0
        // 上一步的成败与界面变化：决定下一步要不要带图核对（截图策略）。
        var prevStepFailed = false
        // 上一步是不是 outcomeUnknown：是就下一轮先核对、不要直接重做。
        var prevOutcomeUnknown = false
        var prevScreenChanged: Boolean? = null
        // R3 轻量核验（2026-11-05）：模型说“done”被顶回过没有，只顶回一次。
        var doneChallenged = false
        // 第十三批·子目标状态机（Minitap Orchestrator 生命周期 NOT_STARTED /
        // PENDING / SUCCESS / FAILURE 的 TaskLoop 版，轻量实现、不照搬 LangGraph）：
        // 第一批的 plan/completed 两个字符串列表合并成一个 subgoals 列表，每项带
        // 状态（见文件底部的 SubgoalStatus / Subgoal）。与文字历史 history 分开存，
        // 提示词里单独一段；模型某轮没给计划时保留旧状态。状态只服务于计划段渲染
        // 与升级逻辑，不参与失败计数、不影响 done 判定（done 仍走现有顶回与核验）。
        // 第四批·断点升级：续跑时从断点恢复 plan/completed，超限只留最后 N 条
        // （复用第一批的上限常量，不另起一套）；旧断点没有这两个字段时默认
        // 空列表，按新任务从空开始，不报错、不丢断点。
        // 恢复时重建状态：断点格式只有 plan/completed 两列，分不出“从来没做过”
        // 还是“做过但失败了”——plan 里的一律重建为 NOT_STARTED（含断点前已标
        // FAILURE 的项：续跑就是一次换招重试的机会），completed 里的一律重建为
        // SUCCESS；两列都出现同一条时 SUCCESS 优先（只留一项，不重复显示）。
        // 失败经过本身仍留在断点恢复的文字历史里，模型看得到，不丢信息。
        val subgoals = mutableListOf<Subgoal>().also { list ->
            val seen = mutableSetOf<String>()
            for (text in resumeFrom?.completed?.takeLast(MAX_COMPLETED_ITEMS) ?: emptyList()) {
                if (seen.add(text)) list += Subgoal(text, SubgoalStatus.SUCCESS)
            }
            for (text in resumeFrom?.plan?.takeLast(MAX_PLAN_ITEMS) ?: emptyList()) {
                if (seen.add(text)) list += Subgoal(text, SubgoalStatus.NOT_STARTED)
            }
        }
        // 第十二批·Scratchpad 持久笔记（Minitap scratchpad / MA-v3 important_notes 的 TaskLoop 版）：
        // notes 是模型可写的跨步持久笔记（账号、位置、已确认的事实），与断点 plan/completed 字段
        // 同款恢复方式：续跑时从断点恢复，超限只留最后 MAX_NOTE_ITEMS 条、每条截断
        // MAX_NOTE_CHARS 字；旧断点没有该字段时默认空列表。笔记只进提示词，不参与
        // 失败计数、不触发升级、不影响 done 判定。
        val notes = resumeFrom?.notes?.takeLast(MAX_NOTE_ITEMS)?.map { it.take(MAX_NOTE_CHARS) }?.toMutableList() ?: mutableListOf<String>()
        // 补丁·计划回传两模式都成立：AutoGLMClient 已把抠到的计划块附在
        // JSON 动作串之后回传，GLM 模式同样能收到计划；抠不到时子目标列表为空、
        // 按无计划回落（见 parsePlan 与下面的计划段）。
        val planSupported = true
        // 第一批·连续失败计数：最近连续失败的步数。执行失败（不含结果未知）
        // 与输出无法解析各 +1；确认成功清零；问模型网络失败不计（它有自己的
        // 连败规则）。达到 STUCK_THRESHOLD 时下一轮提示词注入升级段。
        var consecutiveFailures = 0
        val recentFailureNotes = mutableListOf<String>()
        // 第十三批·子目标状态机：本次连续失败升级里是否已把进行中的子目标标过
        // FAILURE（一次升级只标一次，成功清零失败计数时复位；见计划段的升级块
        // 与下面的 r.ok 分支）。
        var subgoalStuckEpisodeMarked = false
        // 第十四批·Manager/Executor 分工：Manager 单独问一次的“已尝试”旗子（成本护栏，
        // 每种触发每轮任务最多单独问一次，失败也不重试——失败就当没规划过，按原流程继续）：
        // - 计划为空：只在任务开头问一次（首步计划为空时）；问完无论成败不再因“空计划”重问，
        //   Executor 那一次调用里的计划段（第一批机制）仍会请模型列计划。
        // - 连续失败升级：每次连续失败升级（consecutiveFailures 首次达到 STUCK_THRESHOLD）
        //   问一次；成功清零失败计数时复位（见下面的 r.ok 分支），下一轮升级可再问一次。
        // - done 核验没通过：done 被顶回后的下一轮问一次；doneChallenged 本身只置位一次，
        //   这旗子是双保险（Manager 失败时不让它每步都重问）。
        var managerEmptyPlanTried = false
        var managerStuckEpisodeAttempted = false
        var managerDoneReplanAttempted = false
        // 界面指纹（pkg + 大纲）：和上一步一字不差时在提示词里点明，防模型对着
        // 同一张屏反复下同样的动作。
        // 第四批·断点升级：续跑时从断点恢复上一次的界面指纹，首步就能判断界面
        // 自断点起变没变（screenSameAsPrev 与 done 顶回都认它）；旧断点没有
        // 该字段时为空，跟新任务一样从空开始。
        var prevScreenSig = resumeFrom?.screenSig.orEmpty()
        // 第四批·断点升级：最近一次观察到的前台包名（每步观察后更新）。停下记
        // 断点时优先用它当 foregroundPkg，比停下瞬间现读更贴合任务当时所在的
        // 应用；还没观察过（首步前就停下）为空，offer() 内回落到现读。
        var lastObservedPkg = ""
        // L14：最近一次观察到的前台包名——知识只在前台应用切换时注入一次，
        // 同一个应用连着多步不再重复贴同一份。
        var lastKnowledgePkg = ""
        // 第六批·每轮核对前台包名（DroidRun/Minitap“每轮核对前台包名”的 TaskLoop 版，确定性代码、不调模型、不额外取树）：
        // prevRoundPkg 是上一轮观察到的前台包名（只在循环顶部观察段更新；第二批核验、第三批输入校验里的 fresh 树是轮内重读，不更新它）；
        // prevActionKind / prevActionExpectedSwitch 是上一步实际执行过的动作及其“本来就会换应用”判定。
        // 与第四批的 lastObservedPkg 同源但用途不同，不复用：lastObservedPkg 是停下记断点用的“最后所在应用”，
        // prevRoundPkg 是逐轮比对用的“上一轮所在应用”；续跑首轮 prevRoundPkg 为空，不核对（本轮没有上一轮）。
        // 连续两轮跑偏每次都记（不去重）：每跳一次都是新信息（可能又跳去了第三家应用），去重会把第二次跳变藏起来；
        // 同一应用内多轮不动不触发，跑偏轮里模型原地不动也不会重复刷。
        var prevRoundPkg = ""
        var prevActionKind = ""
        var prevActionExpectedSwitch = false
        // 本轮观察算出的包名偏差说明（null = 本轮没跑偏）：观察段算好，提示词组装段贴进本轮提示词；每轮观察时重算。
        var pkgDriftNote: String? = null
        // 第八批·遍历环检测：上一轮命中的环警告（第 2 次出现时写下），本轮提示词组装时
        // 贴一次、贴完清掉，不让它每轮重复刷；历史里那笔记录还在，模型回头能对上号。
        // 只在提示词组装段消费，不在观察段重算（它来自上一轮执行前的判定，不是本轮观察）。
        var loopNote: String? = null
        running = true
        try {
            // 续跑时步数接着上次的编号（历史里的“第 N 步”才连贯），但新跑的
            // 步数预算照样是 MAX_STEPS 步；累计步数另有总上限 MAX_TOTAL_STEPS
            // （M20）：上限 = min(已做步数 + MAX_STEPS, 50)，续跑不再每次续满 25 步。
            var step = resumeFrom?.stepsDone ?: 0
            val stepLimit = minOf(step + MAX_STEPS, MAX_TOTAL_STEPS)
            if (step >= MAX_TOTAL_STEPS) {
                // 续跑进来时累计步数已到顶：拒绝再续，不写新断点、不空跑一轮。
                finalReply = "这个任务累计已经做了 $step 步，达到 $MAX_TOTAL_STEPS 步的上限，不再续跑。"
                AuditLog.append(ctx, "任务循环", "续跑被拒", "累计步数 $step 已达上限 $MAX_TOTAL_STEPS")
                return
            }
            stepLoop@ while (step < stepLimit) {
                if (!alive()) {
                    // 急停：把断点记下来，让用户说“继续”接着做；播报不抢调用方的话，
                    // 只报一句断点（finally 里靠 stoppedWithResume 发出去）。
                    // saveResume=false（内部核验任务）：不记断点、也不发“说继续”那句，
                    // onReply 不发，调用方（TripLoop）超时后自己收尾（S16）。
                    if (saveResume) {
                        // 第四批·断点升级：断点连同当前计划、笔记与最后界面指纹一起记下，
                        // 用户说“继续”时 plan/completed/notes/prevScreenSig 一起恢复。
                        TaskResumeState.offer(
                            taskText, history.toList(), step,
                            foregroundPkg = lastObservedPkg.takeIf { it.isNotEmpty() },
                            plan = subgoalResumePlanTexts(subgoals), completed = subgoalCompletedTexts(subgoals),
                            screenSig = prevScreenSig.takeIf { it.isNotEmpty() },
                            notes = notes.toList()
                        )
                        stoppedWithResume = true
                        finalReply = "任务停在第 $step 步。已做到：${history.lastOrNull() ?: "还没开始动手"}。说「继续」可以从断点接着做。"
                        AuditLog.append(ctx, "任务循环", "第 ${step + 1} 步前被急停", "断点已记，说继续可续跑")
                    } else {
                        AuditLog.append(ctx, "任务循环", "第 ${step + 1} 步前被急停", "内部任务，不记断点")
                    }
                    return
                }
                step++
                // M19 锁屏守卫（现读，不缓存；Andee CommandDispatcher.keyguardLocked 同款）：
                // 锁屏了就停下记断点，不烧步数；播报里明说，解锁后说「继续」接着做。
                if (isKeyguardLocked(ctx)) {
                    if (saveResume) {
                        // 第四批·断点升级：同急停，断点连同计划、笔记与界面指纹一起记。
                        TaskResumeState.offer(
                            taskText, history.toList(), step - 1,
                            foregroundPkg = lastObservedPkg.takeIf { it.isNotEmpty() },
                            plan = subgoalResumePlanTexts(subgoals), completed = subgoalCompletedTexts(subgoals),
                            screenSig = prevScreenSig.takeIf { it.isNotEmpty() },
                            notes = notes.toList()
                        )
                        stoppedWithResume = true
                        finalReply = "手机锁了，解锁后我再继续。已停在第 ${step - 1} 步，解锁后说「继续」可以接着做。"
                        AuditLog.append(ctx, "任务循环", "第 $step 步前发现锁屏", "断点已记，解锁后说继续可续跑")
                    } else {
                        finalReply = "手机锁了，解锁后我再继续。"
                        AuditLog.append(ctx, "任务循环", "第 $step 步前发现锁屏", "内部任务，不记断点")
                    }
                    return
                }
                // 第 4 批·每步服务守卫（断点 #6）：无障碍服务中途死掉只表现为 dump
                // 持续返回空树，旧代码会沿 treeUnreadable 一路记失败、走升级、
                // 烧完 25 步。每步观察前现读 instance（不用 runBlocking 开头
                // 抓到的那个 svc——服务死掉重绑后 instance 会换新对象），掉了
                // 就干净停下：不记连续失败、不触发 STUCK 升级、不烧步数；
                // 记断点，用户到设置里重新开关一次 Jarvis 无障碍服务、说
                // 「继续」可以接着做。不要后台常驻看门狗，本批只要每步检查。
                if (JarvisAccessibilityService.instance == null) {
                    if (saveResume) {
                        TaskResumeState.offer(
                            taskText, history.toList(), step - 1,
                            foregroundPkg = lastObservedPkg.takeIf { it.isNotEmpty() },
                            plan = subgoalResumePlanTexts(subgoals), completed = subgoalCompletedTexts(subgoals),
                            screenSig = prevScreenSig.takeIf { it.isNotEmpty() },
                            notes = notes.toList()
                        )
                        stoppedWithResume = true
                        finalReply = "无障碍服务掉了，任务停在第 ${step - 1} 步。到设置里重新开关一次 Jarvis 无障碍服务，然后说「继续」可以接着做。"
                        AuditLog.append(ctx, "任务循环", "第 $step 步前发现无障碍服务已掉线", "断点已记，重开服务后说继续可续跑")
                    } else {
                        finalReply = "无障碍服务掉了，任务停下了。"
                        AuditLog.append(ctx, "任务循环", "第 $step 步前发现无障碍服务已掉线", "内部任务，不记断点")
                    }
                    return
                }
                // ---------- 观察 ----------
                // 第七批·取树重试 + 恢复：dumpUiTree 内已带退避重试（抛错/无窗口时
                // 最多 7 次尝试，见 ScreenController），正常不再抛错；这里只留兜底，
                // 万一抛了也合成一张空树走下面的“读不到界面”分支，不再 continue
                // 烧步（旧做法：取树一抛错这一步就白白烧掉，模型连问都没问到）。
                // 另：TaskLoop 原有的 1500ms/300ms 空树轮询已删——dumpUiTree 内
                // 已有同样的 settle（SETTLE_BUDGET_MS=1500），两层叠加等于同一轮
                // 观察里把树读 6 遍还多花 1.5 秒；settle 只留 ScreenController 一层。
                var dump = try {
                    controller.dumpUiTree()
                } catch (e: Exception) {
                    JSONObject().put("empty", true).put("error", e.message ?: "dump failed")
                        .put("nodes_total", 0).put("nodes_useful", 0).put("pkg", "")
                        .put("elements", "").put("compact", "")
                }
                val pkg = dump.optString("pkg")
                // 第四批·断点升级：记下本步观察到的前台包名，供停下记断点时用。
                if (pkg.isNotEmpty()) lastObservedPkg = pkg
                // 第六批·每轮核对前台包名：本轮观察到的包名与上一轮比对。
                // 首轮（prevRoundPkg 为空）或本轮读不到包名（pkg 为空，树被扣留/读不到）时不核对；
                // 同一应用内 Activity 跳转包名不变，不触发；核对不调模型、不额外取树，复用本轮已取的 dump。
                // 跑偏本身不记失败、不触发升级（模型可能本来就想换应用，只是要让它知道换了）；
                // 只有跑偏后模型继续盲动、动作失败时，才走现有的失败计数。
                pkgDriftNote = null
                if (pkg.isNotEmpty() && prevRoundPkg.isNotEmpty() && pkg != prevRoundPkg) {
                    if (prevActionExpectedSwitch) {
                        history += "第 ${step} 步观察：前台应用从 ${prevRoundPkg} 切换到 ${pkg}（上一步是 ${prevActionKind.ifEmpty { "未知动作" }}，预期的切换）"
                    } else {
                        pkgDriftNote = "前台应用从 ${prevRoundPkg} 变成了 ${pkg}，上一步是 ${prevActionKind.ifEmpty { "未知动作" }}（屏内动作，本不应换应用），可能是点到广告、跳到别家应用或系统弹窗抢了前台。"
                        history += "第 ${step} 步观察：${pkgDriftNote}"
                        AuditLog.append(ctx, "任务循环", "前台跑偏（第 ${step} 步）", "从 ${prevRoundPkg} 到 ${pkg}，上一步是 ${prevActionKind.ifEmpty { "未知动作" }}")
                    }
                }
                if (pkg.isNotEmpty()) prevRoundPkg = pkg
                val hint = dump.optString("hint")
                // elements 是文本大纲：每行「e3*|Button|发送|[540,2100]」——编号、
                // 类名、文字、0-1000 相对中心坐标，带 * 的可点，缩进表示从属层级
                // （ScreenController 格式）。
                val elements = dump.optString("elements")
                // 紧凑表（Phase 2）：只列可操作元素，稳定编号 + 语义类型 + 文字 +
                // 中心坐标，是给模型看的主列表；elements 原样保留（屏变检测、
                // FlowRuntime 的 find/守卫仍按它匹配），不放进提示词。
                val compact = dump.optString("compact")
                val compactDropped = dump.optInt("compact_dropped", 0)
                val nodesTotal = dump.optInt("nodes_total", -1)
                val nodesUseful = dump.optInt("nodes_useful", -1)
                val withheld = dump.optBoolean("withheld", false)
                // 第七批·取树重试 + 恢复：这轮是不是“读不到界面”——包名读不到
                // 且一个可用节点都没有。不是“盲树”：盲树有包名/层级（自绘界面、
                // 树被扣留），走截图/OCR；这里是真的没读到窗口。
                val treeUnreadable = pkg.isEmpty() && nodesUseful == 0
                val screenSig = pkg + "\n" + elements
                // 两轮都读不到时 screenSig 都是空串、字面相同，但那说明不了
                // “界面没变”，别让它触发“界面纹丝没动”的提示与 done 顶回。
                val screenSameAsPrev = prevScreenSig.isNotEmpty() && screenSig == prevScreenSig && !treeUnreadable
                prevScreenSig = screenSig
                // 第七批·中途恢复：读不到也不 return/判死、不 continue 烧步——
                // 记一笔历史与 lastError，照常问模型，让它下一轮决定 wait、
                // back 还是 fail；连续多轮都读不到时，复用第一批的连续失败
                // 计数与升级（STUCK_THRESHOLD=2 时提示词注入“Potentially
                // Stuck!”），不另起一套计数/阈值/收尾。首轮读不到也一样走
                // 这里（模型可决定先 wait 或 openApp），不为首轮单开逻辑。
                // 跑满 25 步/模型说 fail 的收尾仍走现有逻辑。
                if (treeUnreadable) {
                    val retries = dump.optInt("dump_retries", 0)
                    val retryDetail = dump.optString("dump_last_error").takeIf { it.isNotEmpty() }
                        ?.let { "（${it.take(80)}）" }.orEmpty()
                    val retryNote = if (retries > 0) {
                        "这轮读不到界面，重试 ${retries} 次仍失败${retryDetail}"
                    } else {
                        "这轮读不到界面${retryDetail}"
                    }
                    history += "第 ${step} 步观察：${retryNote}"
                    lastError = retryNote
                    AuditLog.append(ctx, "任务循环", "读不到界面（第 ${step} 步）", retryNote)
                    consecutiveFailures++
                    recentFailureNotes += "第 ${step} 步：${retryNote}"
                    if (recentFailureNotes.size > STUCK_THRESHOLD) recentFailureNotes.removeAt(0)
                    prevStepFailed = true
                    prevScreenChanged = null
                }

                // ---------- 截图策略（M8：树够用且上一步成功时不拍） ----------
                // 紧凑表是默认观察；截图只在“图是必需的”时才拍（树空、上一步失败、
                // 界面没变、树太薄、无进展≥2），其它轮次只给树、不烧 token。
                // needImage 就是这个信号（也用于下面“需要图但没拿到”的提示）。
                // 拍不到就只给树、不卡住这一步。录屏通道仍是无障碍截图的兜底。
                var image: String? = pendingImage
                var imageFromProjection = false
                // pendingImage 是上一轮 zoom 的放大图（坐标系不是全屏），OCR 不能复用它。
                val imageIsZoom = pendingImage != null
                pendingImage = null
                val treeTooThin = nodesUseful >= 0 && nodesUseful < 3
                val needImage =
                    !JarvisAccessibilityService.isShotBlocked(pkg) &&
                    (image != null || elements.isBlank() || prevStepFailed || prevScreenChanged == false ||
                        treeTooThin || noProgressSteps >= 2)
                if (image == null && needImage && pkg.isNotEmpty()) {
                    image = accessibilityShot(controller, ctx)
                    if (image == null) {
                        image = projectionShot(ctx)
                        imageFromProjection = image != null
                    }
                }

                // ---------- OCR 补字层（2026-11-05，Mika 同款） ----------
                // 树不够用时才跑（树空/太薄、被扣留、紧凑表空白），不每步跑。
                // 取一张干净图（不画网格）做 OCR，不然网格字母会被认成字。
                // 结果追加到紧凑表后面单独一段，不混进紧凑表（OCR 行没有 eid，只有坐标）。
                // OCR 坐标与 tap 同一套 0–1000，模型可直接点；盲树下被守卫拒了，
                // 由 4b42bcd 的自动 zoom→点接住，拼成 OCR 找字 → auto-flow 落点。
                // 失败不卡步：超时/抛错/取不到图 → 当作本步无 OCR，继续走原有流程。
                var ocrSection: String? = null
                var ocrStatus: String? = null
                val ocrNeeded = (treeTooThin || withheld || compact.isBlank()) &&
                    pkg.isNotEmpty() && !JarvisAccessibilityService.isShotBlocked(pkg)
                if (ocrNeeded) {
                    // M8：OCR 复用本轮已拍的那张图，不再二次截图。本轮没图
                    // （截图被拒/失败，或本轮图是 zoom 放大图）时跳过 OCR。
                    // 注意：无障碍截图那张是带网格的 JPEG，网格字母可能被 OCR
                    // 认成文字混进补字段；录屏通道的图无网格，不受影响。
                    val ocrBmp = if (imageIsZoom) {
                        null
                    } else {
                        val b64 = image
                        if (b64.isNullOrEmpty()) {
                            null
                        } else try {
                            Base64.decode(b64, Base64.DEFAULT)
                                .let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        } catch (e: Exception) {
                            AuditLog.append(ctx, "OCR", "复用本轮图失败", e.message ?: e.javaClass.simpleName)
                            null
                        }
                    }
                    if (ocrBmp != null) {
                        // 树已判不够用时放宽到 5 秒，树只是判不准时 2 秒（Mika 两档一致）。
                        val timeoutMs = if (nodesUseful < 3) 5000L else 2000L
                        try {
                            val raw = OcrReader.read(ocrBmp, timeoutMs)
                            val legible = OcrReader.legible(raw)
                            // 树上已有的文字：从元素大纲里抽
                            // （格式 e3*|Button|发送|[540,2100]，第 3 段是文字）。
                            val knownTexts = elements.lineSequence()
                                .map { it.trim().split("|") }
                                .filter { it.size >= 3 }
                                .map { it[2].trim() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                            val missing = OcrReader.missing(knownTexts, legible)
                            if (missing.isNotEmpty()) {
                                ocrSection = missing.joinToString("\n") {
                                    "${it.text}|[${it.centerX},${it.centerY}]"
                                }
                            } else if (raw.isEmpty()) {
                                ocrStatus = "empty"
                            } else if (legible.isEmpty()) {
                                ocrStatus = "illegible"
                            }
                            // legible 非空但 missing 为空：OCR 的字树里都有，不用报状态。
                        } catch (e: Exception) {
                            ocrStatus = "failed"
                            AuditLog.append(ctx, "OCR", "识别失败", e.message ?: e.javaClass.simpleName)
                        }
                    } else {
                        ocrStatus = "unavailable"
                    }
                }

                // 第十四批·Manager/Executor 分工（MA-v3 Manager/Executor 的 TaskLoop 版，
                // 只借角色分工、不照搬每步 3 次调用）：默认每步仍只有 Executor 那一次模型
                // 调用（下面的 askModel），Manager 的活仍由那一次调用里的计划段兼任
                // （第一批机制不动）；只在需要（重新）规划时才单独问一次 Manager（短问答，
                // 经注册表取聊天 client，走聊天模型，与第二批 Reflector 同款）：
                // (a) 计划为空（任务开头，还没有子目标）；(b) 连续失败升级首次触发
                // （consecutiveFailures 首次达到 STUCK_THRESHOLD，本次升级只问一次）；
                // (c) 模型说 done 但核验没通过、被顶回后的下一轮。三种触发各有“已尝试”
                // 旗子，每轮任务每种最多问一次；Manager 的输出只进子目标状态机
                // （applyPlanUpdate，与第一批的计划段同款格式），不直接发动作；
                // Manager 调用失败（网络/解析/超时）时当没规划过、按原流程继续，
                // 不记失败、不阻塞；Manager 不参与 Reflector 核验与输入校验。
                // 调用点在提示词组装之前：问到的新计划直接进本轮 Executor 提示词的计划段。
                val managerTrigger = when {
                    subgoals.isEmpty() && !managerEmptyPlanTried -> ManagerTrigger.EMPTY_PLAN
                    consecutiveFailures >= STUCK_THRESHOLD && !managerStuckEpisodeAttempted -> ManagerTrigger.STUCK
                    doneChallenged && !managerDoneReplanAttempted -> ManagerTrigger.DONE_CHALLENGED
                    else -> null
                }
                if (managerTrigger != null) {
                    when (managerTrigger) {
                        ManagerTrigger.EMPTY_PLAN -> managerEmptyPlanTried = true
                        ManagerTrigger.STUCK -> managerStuckEpisodeAttempted = true
                        ManagerTrigger.DONE_CHALLENGED -> managerDoneReplanAttempted = true
                    }
                    runManagerPlanning(
                        ctx, managerTrigger, taskText, pkg, compact,
                        history, recentFailureNotes, subgoals
                    ) { alive() }
                }
                val userText = buildString {
                    append("任务：").append(taskText).append('\n')
                    append("当前前台应用包名：").append(pkg.ifEmpty { "未知" }).append('\n')
                    // 分应用用法知识（Mika APP.md 思路）：前台应用有知识就追加，
                    // 让模型在微信这类难啃的应用里不用每步现猜。所有大脑通用。
                    // L14：只在前台应用切换时注入一次，同一个应用连着多步不再
                    // 重复贴同一份；带 ctx 的重载查不到知识时记审计（M25 接线），
                    // 只在切换时查，免得每步都刷一条“查不到”。
                    if (pkg != lastKnowledgePkg) {
                        lastKnowledgePkg = pkg
                        val appKnowledge = AppKnowledge.forPackage(ctx, pkg)
                        if (appKnowledge != null) {
                            append("这个应用的用法知识：\n").append(appKnowledge).append('\n')
                        }
                    }
                    // 内置浏览器开着时，把当前页快照（地址/标题/正文/链接）也给模型，
                    // 它才能按链接编号点（browserTap）或决定下一步。
                    val browserSnap = BrowserController.lastSnapshot()
                    if (BrowserController.isOpen() && browserSnap != null) {
                        append("内置浏览器当前页：").append(browserSnap.url).append('\n')
                        if (browserSnap.title.isNotEmpty()) append("标题：").append(browserSnap.title).append('\n')
                        if (browserSnap.text.isNotEmpty()) {
                            append("页面正文（前 2000 字）：\n").append(browserSnap.text).append('\n')
                        }
                        if (browserSnap.links.isNotEmpty()) {
                            append("页面链接（编号|文字）：\n")
                            for (l in browserSnap.links) append(l.index).append("|").append(l.text).append('\n')
                        }
                    }
                    if (hint.isNotEmpty()) append("界面提示：").append(hint).append('\n')
                    if (nodesTotal >= 0) {
                        append("界面节点：共 $nodesTotal 个，其中带名可用的 $nodesUseful 个")
                        if (withheld) append("（界面树被这个应用扣留，元素可能不全）")
                        append('\n')
                    }
                    append("界面元素（紧凑表，每行：稳定编号|类型|文字|[中心x,中心y]，坐标是 0-1000 相对值；稳定编号跨轮不变，同一元素每轮同一个编号，点它就用这个编号；类型 Button/Input/Text/Image/List/Scroll/Checkbox）：\n")
                    append(
                        compact.take(COMPACT_CHARS)
                            .ifEmpty { "（无可操作元素：这个应用可能不向无障碍暴露可点内容，只能看截图操作）" }
                    ).append('\n')
                    if (compactDropped > 0) {
                        append("（紧凑表只列了前 40 个可操作元素，另有 $compactDropped 个没列出；要找没列出的，滑一滑或缩小范围再看）\n")
                    }
                    // OCR 补字：树不够用时认出的屏上文字（只列树里没有的），坐标与 tap
                    // 同一套 0–1000，可直接用 {"action":"tap","x":cx,"y":cy} 点。
                    // 树不够用且 OCR 一行没有时，给出原因，让模型知道该看图找、而不是再读一次。
                    if (ocrSection != null) {
                        append("屏幕可见文字（OCR，只列树里没有的；每行：文字|[中心x,中心y]，坐标 0–1000，可直接点）：\n")
                        append(ocrSection).append('\n')
                    } else if (ocrStatus != null) {
                        append("OCR 状态：$ocrStatus（树不够用且没认出可用文字，请看截图找目标）\n")
                    }
                    if (needImage && image == null) {
                        append("（本轮需要截图但没能拿到，只能按界面树操作）\n")
                    }
                    if (imageFromProjection) {
                        append("本轮附图来自录屏通道：原始屏幕像素，没有网格标定，不能按图报格号或坐标瞄准；只能看内容，操作仍按元素编号和界面树进行。\n")
                    }
                    if (screenSameAsPrev) {
                        append("注意：界面与上一步完全相同、纹丝没动——上一个动作没有生效或页面卡住了。不要重复同一个动作，换个做法（先 zoom 看清、滑动、返回或 wait 更久）。\n")
                    }
                    // 第八批·遍历环检测：上一轮命中的环警告，本轮贴一次、贴完清掉。
                    // 位置在“界面纹丝没动”之后：环警告本身就是“回到了做过动作的界面”，
                    // 两句挨着，模型不用翻历史就能对上号。
                    if (loopNote != null) {
                        append("【遍历环警告】").append(loopNote).append('\n')
                        loopNote = null
                    }
                    if (pkgDriftNote != null) {
                        append("【前台应用跑偏】").append(pkgDriftNote).append("本轮先确认当前在哪个应用、要不要回上一个应用再继续，不要对着错的应用直接做下一步。\n")
                    }
                    val hist = historySection(history)
                    if (hist.isNotEmpty()) {
                        append("已执行步骤（旧→新）：\n").append(hist)
                    }
                    if (lastError.isNotEmpty()) append("上一步的问题：").append(lastError).append('\n')
                    if (prevOutcomeUnknown) append("上一步结果未知：不知道那个动作成没成，先看一眼界面（紧凑表和截图）确认成没成，再决定下一步，不要直接重做同样的动作。\n")
                    // 第一批·计划段（只在计划回传成立的模式下出现；GLM 模式子目标列表恒为空，
                    // 不贴这段、也不催模型列计划）：
                    // 第十三批·子目标状态机：渲染格式与第一批一字不差（先 ✓ 已完成行、再
                    // 编号待做行），只换数据源——✓ 行是 SUCCESS 项，编号行是 NOT_STARTED /
                    // PENDING 项；FAILURE 项不渲染（模型没重列 = 不再做，失败经过在历史里）。
                    if (planSupported) {
                        val completedTexts = subgoalCompletedTexts(subgoals)
                        val planTexts = subgoalActiveTexts(subgoals)
                        if (planTexts.isNotEmpty() || completedTexts.isNotEmpty()) {
                            append("计划（子目标列表）：\n")
                            for (c in completedTexts) append("✓ ").append(c).append('\n')
                            for ((i, p) in planTexts.withIndex()) {
                                append(i + 1).append(". ").append(p).append('\n')
                            }
                            append("每轮回复里更新这个计划：做完的子目标标 ✓，没做完的照常列，卡住时修订计划（重排、改写、插入纠正步骤、换策略）。\n")
                        } else {
                            append("还没有计划：请在回复里先列出编号子目标列表（最多 10 条，每条一句话），再输出 JSON 动作。\n")
                        }
                    }
                    // 第十二批·Scratchpad 持久笔记：单独一段、贴在计划段附近（计划段
                    // 之后、失败升级段之前）。有笔记就列出来并告诉模型怎么写新段；
                    // 没笔记就只给一句写法提示（没有可记的可以不写，不强求）。
                    // 笔记只来自模型回复，不自动从界面里抽；不参与失败计数、不触发
                    // 升级、不影响 done 判定。
                    if (notes.isNotEmpty()) {
                        append("笔记（已确认的关键信息，跨步保留）：\n")
                        for (n in notes) append("- ").append(n).append('\n')
                        append("需要更新笔记时在回复里重写整个“笔记：”段（最多 10 条，每条一句话）；没新内容时不用重写，旧笔记会一直保留。\n")
                    } else {
                        append("需要记住的关键信息（账号、位置、已确认的事实）可以写在“笔记：”段里（与“计划：”段并列，每条一行以 - 开头，最多 10 条）；没有可记的可以不写。\n")
                    }
                    // 第一批·连续失败升级（MA-v3 err_to_manager_thresh=2）：
                    // 最近 STUCK_THRESHOLD 步都失败时，不再让模型原地重试，
                    // 要求它修订计划（重排、改写、插入纠正步骤、换策略）。
                    if (consecutiveFailures >= STUCK_THRESHOLD) {
                        // 第十三批·子目标状态机：首次进入本次连续失败升级时，把进行中的
                        // 子目标标 FAILURE（NOT_STARTED→PENDING→FAILURE 的失败转移；
                        // 没有进行中的项时什么都不做）。只标一次：同一轮升级里不重复标，
                        // 成功清零失败计数时复位（见下面的 r.ok 分支）；标 FAILURE 不参与
                        // 失败计数、不影响 done 判定，只是状态。
                        if (!subgoalStuckEpisodeMarked) {
                            subgoalStuckEpisodeMarked = true
                            markSubgoalFailed(subgoals)
                        }
                        append("【Potentially Stuck!】最近 ${recentFailureNotes.size} 步都失败了：\n")
                        for (note in recentFailureNotes) append(note).append('\n')
                        append("不要原地重试同一个做法。请修订计划：重排子目标、改写卡住的步骤、插入纠正步骤、或换一条完全不同的策略")
                        if (planSupported) append("；修订后的计划在回复里一并给出")
                        append("。\n")
                    }
                    append("现在只输出下一步的一个 JSON 动作。")
                }
                // 任务大脑（GLM / 自定义）需要时才带图问（M8：树够用且上一步成功时不拍图）；
                // 紧凑表空白且最终不带图时，
                // 明确告知模型它看不到可操作元素，别让它空转。
                val sendImage = image
                val askBase = if (compact.isBlank() && sendImage == null) {
                    userText + "\n（注意：本屏没有可操作元素、也看不到截图，只能根据历史步骤判断；若无法推进请输出 done 并说明卡点）"
                } else {
                    userText
                }
                // 第一梯队批二·失败后换策略：同一个动作签名已经连续出现到快要触发重复守卫
                // （repeats 从上一步执行后带到这里），提前一步给出强提示，逼模型主动换一种
                // 真正不同的做法，而不是等拦停了才回报失败——拦停是整场任务直接结束，没有
                // 重试机会，能提前破局就不要走到那一步。
                val strategyNudge = if (repeats >= MAX_REPEATS - 1) {
                    "\n【重要】你前面已经连续${repeats + 1}次做了几乎一样的动作、界面却没有真正推进。" +
                        "再重复一次相似的动作这个任务就会被强制中止，没有下次机会了。现在必须换一种" +
                        "完全不同的思路：比如滚动页面找找看有没有别的入口、点搜索图标直接搜目标文字、" +
                        "先返回上一页换条路线、或者先 zoom 放大看清楚这个点有没有点偏了。" +
                        "不要再输出和前面几步相似的动作。"
                } else ""
                val askTextBase = askBase + strategyNudge
                // ---------- 问模型（解析失败带原因重试一次，再不行记一步失败继续） ----------
                var action: JSONObject? = null
                var attempt = 0
                var askText = askTextBase
                var badReply: String? = null
                while (action == null && attempt < 2) {
                    attempt++
                    // 每步问模型前固定停 1 秒，降速、别一分钟内连着发多次请求撞限流。
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { /* 继续 */ }
                    val (reply, err) = askModel(ctx, askText, sendImage) { alive() }
                    if (!alive()) return
                    if (err != null) {
                        // 2026-10-24 跨应用修复第 2 件：问模型失败不再直接判死。
                        // 连着失败 2 次才结束；第 1 次记一步失败、下一轮重新观察再问
                        // （不计入无进展计数——这是网络问题，不是模型原地打转）。
                        askFails++
                        if (askFails >= 2) {
                            finalReply = "任务没能继续：$err"
                            AuditLog.append(ctx, "任务循环", "第 $step 步问模型失败", "$err（连着失败 $askFails 次，已停下）")
                            return
                        }
                        AuditLog.append(ctx, "任务循环", "第 $step 步问模型失败", "$err（第 $askFails 次，将重试）")
                        history += "第 $step 步：问模型失败 — $err（已重试）"
                        lastError = "问模型失败：$err，已重试"
                        prevStepFailed = true
                        prevScreenChanged = null
                        // 429 是每分钟限流：等 30 秒再进下一轮，别立刻重问又撞上限。
                        // 连着失败计数照旧（2 次照样停）。
                        if (err.contains("429")) {
                            AuditLog.append(ctx, "任务循环", "第 $step 步问模型失败", "HTTP 429 限流，等 30 秒再重试")
                            try { Thread.sleep(30_000) } catch (_: InterruptedException) { /* 被打断就直接进下一轮 */ }
                        }
                        // 直接进下一轮（重新观察、重新问），不能掉进下面“输出无法解析”
                        // 的分支——那是解析失败的账，网络失败不该计入无进展计数。
                        continue@stepLoop
                    }
                    askFails = 0
                    action = parseAction(reply.orEmpty())
                    if (action != null) {
                        // 第一批·计划：回复里带计划块就更新子目标状态机；
                        // 没带保留旧状态（宽容回落）。补丁后 GLM 模式的 reply
                        // 是 JSON 动作串 + 附在其后的计划块，parsePlan 能抠到。
                        // 第十三批·子目标状态机：✓ 的项 → SUCCESS（模型看屏后凭观测
                        // 证据标完成，见元认知规则）；重列的 FAILURE 项 → NOT_STARTED
                        // （换招重试）；没重列的 FAILURE 项留在 FAILURE 里不再做。
                        parsePlan(reply.orEmpty())?.let { parsed -> applyPlanUpdate(parsed, subgoals) }
                        // 第十二批·Scratchpad 持久笔记：回复里带笔记段就以新段为准
                        // 全量替换旧笔记；没带保留旧笔记（宽容回落，与计划同款）。
                        // GLM 模式的 reply 是 JSON 动作串 + 附在其后的计划块与笔记块，
                        // parseNotes 能抠到（见 AutoGLMClient.extractNotesBlock）。
                        parseNotes(reply.orEmpty())?.let { parsed -> applyNotesUpdate(parsed, notes) }
                    }
                    if (action == null) {
                        badReply = reply
                        lastError = "模型输出不是合法的 JSON 动作"
                        askText = askBase +
                            "\n你上一次的输出无法解析：${reply?.take(200)}。请严格只输出一个 JSON 对象，不要任何其他文字。"
                    }
                }
                if (action == null) {
                    // 按 Andee 的做法把错误当一步结果回喂（下一步提示词里带着），
                    // 模型有机会自纠；无法解析也算一种签名，连着来照样被守卫拦下。
                    history += "第 $step 步：输出无法解析 — ${badReply?.take(80) ?: "（空回复）"}"
                    // 第一批·连续失败：输出无法解析也算一步失败。
                    consecutiveFailures++
                    recentFailureNotes += "第 ${step} 步：输出无法解析 — ${badReply?.take(60) ?: "（空回复）"}"
                    if (recentFailureNotes.size > STUCK_THRESHOLD) recentFailureNotes.removeAt(0)
                    prevStepFailed = true
                    prevScreenChanged = null
                    if ("∅unparseable" == lastSignature) {
                        repeats++
                    } else {
                        lastSignature = "∅unparseable"
                        repeats = 0
                    }
                    if (repeats >= MAX_REPEATS) {
                        finalReply = "任务卡在第 $step 步：模型连续多次输出无法解析的内容，已停下，没有继续乱点。"
                        AuditLog.append(ctx, "任务循环", "重复守卫触发", "连续无法解析的输出")
                        return
                    }
                    // 解析不出动作，自然也谈不上是真交互；Andee 对齐：计数保留
                    // （供强制带图），熔断 abort 删除，继续下一轮。
                    noProgressSteps++
                    continue
                }
                val kind = action.optString("action")
                if (kind == "done" || kind == "fail") {
                    // R3 轻量核验（2026-11-05）：模型说“做完了”不能直接信——
                    // 上一步结果未知，或界面自上一个真动作后纹丝没动，说明它可能
                    // 根本没做成。只顶回一次：请它再看一眼确认；它坚持说完就信。
                    // “fail”不顶回：模型说做不下去就停，别逼它硬撑。
                    // 注意：第一步就说 done 时 prevOutcomeUnknown 与 screenSameAsPrev
                    // 都是 false，不会误顶（任务可能本来就已做完）。
                    if (kind == "done" && !doneChallenged && (prevOutcomeUnknown || screenSameAsPrev)) {
                        // 第二批·Reflector 精简版（done 宣称核验）：先取一次 fresh 界面状态问一次核验，
                        // A 才放行；B/C 或核验失败时按原有顶回逻辑处理。图复用本轮已拍的全屏图
                        // （zoom 放大图坐标系不是全屏，不用）。
                        val afterDump = try { controller.dumpUiTree() } catch (_: Exception) { null }
                        val doneVerdict = if (afterDump != null) {
                            val doneImage = if (!imageIsZoom) sendImage else null
                            askReflect(
                                ctx,
                                buildDoneReflectText(
                                    taskText, action.optString("message"),
                                    afterDump.optString("pkg"), afterDump.optString("compact")
                                ),
                                doneImage
                            ) { alive() }
                        } else null
                        if (doneVerdict?.first == ReflectVerdict.SUCCESS) {
                            AuditLog.append(ctx, "任务循环", "done 核验通过", doneVerdict.second.take(120))
                            history += "第 $step 步：模型说做完了，核验通过（${doneVerdict.second.take(60)}）"
                            // 不顶回，继续往下走正常 done 收尾。
                        } else {
                            doneChallenged = true
                            val reason = if (prevOutcomeUnknown) "上一步结果未知" else "界面自上一步起完全没变"
                            history += "第 $step 步：模型说做完了，但$reason，已请它再确认一次"
                            AuditLog.append(ctx, "任务循环", "done 待核验", reason)
                            lastError = "你说做完了，但$reason——请再看一眼界面（紧凑表和截图），确认要找的东西/要发的内容真的在屏上，再决定是真的做完了还是继续做。不要只凭感觉说 done。"
                            continue@stepLoop
                        }
                    }
                    val msg = if (kind == "done") {
                        action.optString("message").ifEmpty { "任务完成了" }
                    } else {
                        action.optString("reason").ifEmpty { "模型判断做不下去" }
                    }
                    history += "第 $step 步：$kind — $msg"
                    AuditLog.append(ctx, "任务循环", kind, msg)
                    finalReply = if (kind == "done") msg else "任务没完成，卡在第 $step 步：$msg"
                    return
                }
                // ---------- 重复守卫：执行前判定（LocalBrain 在执行前拦） ----------
                val sig = signatureOf(action)
                // 第八批·先记下这一步是不是“连着上一步同一个签名”：遍历环检测只管
                // 非连续的环（A→B→A→B，中间隔了别的动作），连着的重复仍归重复守卫，
                // 环检测里不抢它的活、不重复计数。注意这里必须在下面的 if/else 更新
                // lastSignature 之前记——更新完 lastSignature 就恒等于 sig 了。
                val consecutiveSameSig = sig == lastSignature
                if (sig == lastSignature) {
                    repeats++
                } else {
                    lastSignature = sig
                    repeats = 0
                }
                if (repeats >= MAX_REPEATS) {
                    finalReply = "任务卡在第 $step 步：同一个动作（$kind）连着重复多次、界面没有进展，已停下。" +
                        "当前停在${pkg.ifEmpty { "未知界面" }}。"
                    AuditLog.append(ctx, "任务循环", "重复守卫触发", "$kind 连续重复")
                    return
                }
                // ---------- 第八批·遍历环检测：执行前判定（Minitap 元认知“发现循环”的 TaskLoop 版，确定性代码、不调模型、不额外取树） ----------
                // 环的定义：本轮任务内，同一（界面指纹 screenSig，动作签名）对再次出现，
                // 即模型回到了之前做过同一动作的界面。只凭签名重复不算环（那是重复守卫的活）；
                // 同一签名在不同界面上做（列表连续滑动翻页、每滑一屏界面都变）是不同的对，不算环；
                // type 签名含文字（见 signatureOf），同一输入框连打不同文字是不同的对，不算环。
                // 出现次数：第 1 次只记录；第 2 次命中警告——写一笔历史、下一轮提示词贴
                // 【遍历环警告】、并复用第一批的连续失败计数记一步失败（走升级逻辑，
                // STUCK_THRESHOLD=2 时下一轮提示词注入“Potentially Stuck!”），但这一步
                // 不拦（模型还没看到警告，先让它做完这一次）；第 3 次（LOOP_STOP_OCCURRENCES）
                // 直接停下、不再执行，收尾走现有断点那套（写断点、播报“说继续”）。
                // 跳过的情况：读不到界面（treeUnreadable，screenSig 是空串、两轮都相同也说明不了
                // 界面没变，与 screenSameAsPrev 同款 guard）、elements 为空（树被扣留到只剩包名，
                // 光凭包名确认不了“回到了之前的状态”，宁可漏判、不误判）、连续重复
                // （consecutiveSameSig，归重复守卫）。done/fail/输出无法解析/问模型失败
                // 的轮次到不了这里（前面已 return/continue），不记对、不耗次数。
                // 本步执行失败时执行层还会记一次失败，下面的 loopFailureCounted 会拦住，
                // 一步失败只算一次（Reflector/输入校验同款防重复计数）；执行成功则按第一批
                // 惯例清零（成功就是破局了，环警告作废）。
                var loopFailureCounted = false
                if (!treeUnreadable && elements.isNotEmpty() && !consecutiveSameSig) {
                    val loopKey = screenSig + "\u0000" + sig
                    val seenCount = loopSeen[loopKey] ?: 0
                    when {
                        seenCount == 0 -> {
                            loopSeen[loopKey] = 1
                            if (loopSeen.size > MAX_LOOP_PAIRS) {
                                loopSeen.keys.firstOrNull()?.let { loopSeen.remove(it) }
                            }
                        }
                        seenCount + 1 < LOOP_STOP_OCCURRENCES -> {
                            loopSeen[loopKey] = seenCount + 1
                            loopNote = "你已经在同一界面对同一目标做过「${describeAction(action)}」，界面又回到了当时的状态——不要原样重做这个动作，换个做法（换个入口、换个瞄准方式、或先确认目标到底在不在这屏上）。"
                            history += "第 ${step} 步环检测：同一（界面，动作）对第 ${seenCount + 1} 次出现——${describeAction(action)}"
                            AuditLog.append(ctx, "任务循环", "遍历环命中（第 ${step} 步）", "同一（界面，动作）对第 ${seenCount + 1} 次出现：${describeAction(action)}")
                            consecutiveFailures++
                            recentFailureNotes += "第 ${step} 步：遍历环——同一界面上对同一目标重复做 ${describeAction(action)}"
                            if (recentFailureNotes.size > STUCK_THRESHOLD) recentFailureNotes.removeAt(0)
                            loopFailureCounted = true
                        }
                        else -> {
                            // 同一对第 LOOP_STOP_OCCURRENCES 次出现：模型看过环警告还照原样打转，
                            // 直接停下，不再烧步数。收尾走现有断点那套（与锁屏一致）：写断点、
                            // 播报“说继续”；内部任务（saveResume=false）不写断点、不发那句（S16）。
                            history += "第 ${step} 步环检测：同一（界面，动作）对第 ${LOOP_STOP_OCCURRENCES} 次出现——${describeAction(action)}，已停下"
                            AuditLog.append(ctx, "任务循环", "遍历环停下（第 ${step} 步）", "同一（界面，动作）对第 $LOOP_STOP_OCCURRENCES 次出现：${describeAction(action)}")
                            if (saveResume) {
                                TaskResumeState.offer(
                                    taskText, history.toList(), step - 1,
                                    foregroundPkg = lastObservedPkg.takeIf { it.isNotEmpty() },
                                    plan = subgoalResumePlanTexts(subgoals), completed = subgoalCompletedTexts(subgoals),
                                    screenSig = prevScreenSig.takeIf { it.isNotEmpty() },
                                    notes = notes.toList()
                                )
                                stoppedWithResume = true
                                finalReply = "任务卡在第 $step 步：在${pkg.ifEmpty { "未知界面" }}对同一目标反复做「${describeAction(action)}」，在同一界面反复打转，已停下。说「继续」可以接着做。"
                            } else {
                                finalReply = "任务卡在第 $step 步：在${pkg.ifEmpty { "未知界面" }}对同一目标反复做「${describeAction(action)}」，在同一界面反复打转，已停下。"
                            }
                            return
                        }
                    }
                }
                // Andee 对齐：无进展熔断 abort 删除（Andee 只有重复守卫 + 25 步上限兜底）。
                // 计数保留：连续 2 轮无真交互时强制带图（截图策略），让模型有图可破局。
                noProgressSteps = if (kind in REAL_ACTION_KINDS) 0 else noProgressSteps + 1
                // ---------- 执行 ----------
                // M18：每步执行前推一句播报（Andee narrate(toolName) 同款），不要只在最后调一次 onReply。
                onProgress(narrate(kind))
                // 第十三批·子目标状态机：真正执行动作时才算“开始做”——把最早的待做
                // 子目标标 PENDING（NOT_STARTED→PENDING）。同一时间最多一个 PENDING
                // （Minitap Orchestrator 同款：一次只做一个子目标）；done/fail、
                // 输出无法解析、重复守卫/环检测拦下的轮次到不了这里，不标。
                markSubgoalStarted(subgoals)
                val r = execute(ctx, controller, action)
                if (r.zoomImage != null) pendingImage = r.zoomImage
                // 第六批·记下本步实际执行的动作，供下一轮观察时核对前台包名：
                // 只有真正执行过的动作才更新（问模型失败/输出无法解析/done 顶回
                // 的轮次没执行动作，沿用更早一步的执行记录——包名若在那几轮里变了，
                // 仍按最后一次真实动作判预期与否）。
                prevActionKind = kind
                prevActionExpectedSwitch = kind in EXPECTED_PKG_SWITCH_KINDS ||
                    action.optBoolean("handover", false) || flowHasAppSwitch(action)
                // 固定等 600ms 改成等界面静止：连续两次读到的界面哈希相同才算稳，
                // 下一步的大纲在界面真稳了之后再读，不盲等。超时没稳住也继续，
                // 只是如实记一笔（历史里的“界面已稳定 / 仍在变”就是这句的来处）。
                val settle = try {
                    ScreenWait.waitForSettle(controller, ScreenWait.SETTLE_DEFAULT_MS, "idle")
                } catch (e: InterruptedException) {
                    return
                } catch (_: Exception) {
                    null
                }
                val settleNote = when {
                    settle == null -> "（等界面稳定时出错，未确认）"
                    settle.readFailed -> "（读界面失败，未确认是否稳定）"
                    settle.settled -> "（界面已稳定）"
                    else -> "（界面仍在变，超时未稳）"
                }
                val unknownNote = if (r.outcomeUnknown) "（结果未知：不知道成没成）" else ""
                history += "第 $step 步：${describeAction(action)} → ${r.summary.take(STEP_RESULT_CHARS)}$unknownNote$settleNote"
                AuditLog.append(ctx, "任务循环", "$kind（第 $step 步）", r.summary)
                lastError = if (r.ok) "" else if (r.code.isNotEmpty()) "[${r.code}] ${r.summary}" else r.summary
                prevStepFailed = !r.ok
                prevOutcomeUnknown = r.outcomeUnknown
                prevScreenChanged = r.screenChanged
                // 第一批·连续失败计数：确认成功清零；确认失败 +1 并记一条；
                // 结果未知不计（不知道成没成，既不算失败也不算成功）。
                // 第八批·环检测已把这一步记过一次失败（同一对第二次出现，
                // loopFailureCounted）时不再重复记，一步失败只算一次；
                // 环检测的失败原因已在 history 与 recentFailureNotes 里。
                if (r.ok) {
                    consecutiveFailures = 0
                    recentFailureNotes.clear()
                    // 第十三批·子目标状态机：成功即破局，下一次连续失败升级可再标一次 FAILURE。
                    subgoalStuckEpisodeMarked = false
                    // 第十四批·Manager/Executor 分工：成功即破局，下一轮连续失败升级可再单独问一次 Manager。
                    managerStuckEpisodeAttempted = false
                } else if (!r.outcomeUnknown && !loopFailureCounted) {
                    consecutiveFailures++
                    recentFailureNotes += "第 ${step} 步：${describeAction(action)} → ${r.summary.take(80)}"
                    if (recentFailureNotes.size > STUCK_THRESHOLD) recentFailureNotes.removeAt(0)
                }
                // 结果未知的一步不计入重复：不知道它到底跑没跑成，模型换招或
                // 重试都不该被当成原地打转拦下；普通失败的重复照旧累计。
                if (r.outcomeUnknown) repeats = (repeats - 1).coerceAtLeast(0)
                // 第二批·Reflector 精简版（R3）：只在不确定信号时核验——
                // (a) 结果未知；(b) 应当引起界面变化的动作执行后界面纹丝没动。
                // 确定成功绝不核验；核验失败（网络/解析/取不到 fresh 状态）当没验证过，不记失败、不阻塞。
                // 第三批·记下核验前的失败计数：输入校验只在“本步还没被记为失败”时才计数，
                // 核验已记过的（consecutiveFailures 已涨）不再重复记。
                val failuresBeforeReflect = consecutiveFailures
                val needReflect = r.outcomeUnknown ||
                    (r.screenChanged == false && kind in REFLECT_CHANGE_KINDS)
                if (needReflect && alive()) {
                    // 取一次 fresh 界面状态（执行后）：只读树，不动界面；取不到就当没验证过。
                    val afterDump = try { controller.dumpUiTree() } catch (_: Exception) { null }
                    val verdict = if (afterDump != null) {
                        val afterCompact = afterDump.optString("compact")
                        val afterPkg = afterDump.optString("pkg")
                        val afterWithheld = afterDump.optBoolean("withheld", false)
                        val afterThin = afterDump.optInt("nodes_useful", -1).let { it >= 0 && it < 3 }
                        // 截图只在 fresh 树不够用时才拍（树空/被扣留/太薄），树 diff 是主证据。
                        val verifyImage = if ((afterCompact.isBlank() || afterWithheld || afterThin) &&
                            afterPkg.isNotEmpty() && !JarvisAccessibilityService.isShotBlocked(afterPkg)
                        ) {
                            accessibilityShot(controller, ctx) ?: projectionShot(ctx)
                        } else null
                        askReflect(
                            ctx,
                            buildStepReflectText(
                                taskText, describeAction(action), r.summary,
                                pkg, compact, afterPkg, afterCompact
                            ),
                            verifyImage
                        ) { alive() }
                    } else null
                    if (verdict != null && verdict.first != ReflectVerdict.SUCCESS) {
                        val verdictText = if (verdict.first == ReflectVerdict.WRONG_PAGE) "B 错页" else "C 无变化"
                        val desc = verdict.second
                        history += "第 $step 步核验：$verdictText — ${desc.take(80)}"
                        AuditLog.append(ctx, "任务循环", "核验$verdictText（第 $step 步）", desc.take(120))
                        lastError = "上一步核验没通过（$verdictText）：$desc"
                        prevStepFailed = true
                        // 只在“本步还没被记为失败”时才计数，避免一次失败算两次：
                        // r.ok=false 且结果明确的步，上面已经记过一次了。
                        if (r.ok || r.outcomeUnknown) {
                            consecutiveFailures++
                            recentFailureNotes += "第 $step 步核验$verdictText：${desc.take(60)}"
                            if (recentFailureNotes.size > STUCK_THRESHOLD) recentFailureNotes.removeAt(0)
                        }
                    } else if (verdict != null) {
                        // 核验通过（A）：结果未知已被确认成真，下一轮不必再按未知处理；
                        // 界面没变但动作已生效的，也记一笔，免得下一轮还盯着“没变化”纠结。
                        if (r.outcomeUnknown) {
                            prevOutcomeUnknown = false
                            history += "第 $step 步核验：A 成功——上一步结果已确认"
                        } else {
                            history += "第 $step 步核验：A 成功——界面没变但动作已生效"
                        }
                    }
                }
                // 第三批·输入后校验（Minitap 文本输入六步校验的 TaskLoop 版，确定性代码、不调模型）：
                // 只对 type 动作，且执行结果不是明确失败（r.ok 或结果未知）时校验。
                // 执行层（ScreenController 四档输入通道）内部已有“焦点框精确读回”，
                // 这里补它没有的两点：(a) 归一化比对，容忍输入框的正常变换；
                // (b) 在 fresh 树里全量扫输入框，抓“打了一半、根本没打进去”。
                // type 动作本身不带目标字段身份（协议只有 {"action":"type","text":...}，
                // executeInner 传给 typeText 的 id 恒为 null），且紧凑表/元素大纲
                // 都不标哪个是焦点框，所以按 fresh 树里所有输入框扫——字段一个都
                // 找不到时（跳页、输入框已消失、树被扣留）当无法验证，什么都不做。
                if (kind == "type" && (r.ok || r.outcomeUnknown) && alive()) {
                    val afterDump = try { controller.dumpUiTree() } catch (_: Exception) { null }
                    val expected = action.optString("text")
                    val fieldTexts = if (afterDump != null) {
                        extractInputFieldTexts(afterDump.optString("compact"), afterDump.optString("elements"))
                    } else emptyList()
                    if (fieldTexts.isNotEmpty() && normalizeTypedText(expected).isNotEmpty()) {
                        // 第二批核验已把这一步记为失败时（B/C），输入校验只记证据、
                        // 不再重复计数、不改 lastError（错页才是根因，重打解决不了）。
                        val alreadyFailedThisStep = consecutiveFailures > failuresBeforeReflect
                        val matched = fieldTexts.any { typedTextMatches(expected, it) }
                        if (matched) {
                            if (!alreadyFailedThisStep) {
                                if (r.outcomeUnknown) {
                                    prevOutcomeUnknown = false
                                    history += "第 ${step} 步输入校验：框内文字与应打一致，结果已确认"
                                } else {
                                    history += "第 ${step} 步输入校验：框内文字与应打一致"
                                }
                            }
                        } else {
                            // 挑与应打文本最像的那个字段写进失败原因，让模型下一轮
                            // 知道到底打成了什么样（空字段/打一半/多了字/对不上）。
                            val actual = fieldTexts.maxByOrNull {
                                commonPrefixLength(normalizeTypedText(expected), normalizeTypedText(it))
                            }.orEmpty()
                            val reason = describeTypeMismatch(expected, actual)
                            history += "第 ${step} 步输入校验没通过：应打「${expected.take(40)}」，实际「${actual.take(40)}」"
                            AuditLog.append(ctx, "任务循环", "输入校验没通过（第 ${step} 步）", "应打「${expected.take(60)}」，实际「${actual.take(60)}」")
                            prevStepFailed = true
                            if (!alreadyFailedThisStep) {
                                lastError = "输入校验没通过（${reason}）：应打「${expected.take(80)}」，输入框实际是「${actual.take(80)}」。请先看一眼输入框，再决定是重打、补打还是换个框，不要原样重发 type。"
                                consecutiveFailures++
                                recentFailureNotes += "第 ${step} 步输入校验没通过（${reason}）：应打「${expected.take(60)}」"
                                if (recentFailureNotes.size > STUCK_THRESHOLD) recentFailureNotes.removeAt(0)
                            }
                        }
                    }
                }
            }
            // 跑满步数也没完成：把断点记下来，用户说“继续”可以接着做（累计不超
            // MAX_TOTAL_STEPS 步）；内部任务（saveResume=false）不记断点（S16）。
            // 第四批·断点升级：同急停/锁屏，断点连同计划、笔记与界面指纹一起记。
            if (saveResume) TaskResumeState.offer(
                taskText, history.toList(), step,
                foregroundPkg = lastObservedPkg.takeIf { it.isNotEmpty() },
                plan = subgoalResumePlanTexts(subgoals), completed = subgoalCompletedTexts(subgoals),
                screenSig = prevScreenSig.takeIf { it.isNotEmpty() },
                notes = notes.toList()
            )
            finalReply = if (saveResume) {
                "任务跑满了步数上限（累计 $step 步）还没完成，已停下。最后进展：${history.lastOrNull() ?: "没有进展"}。说「继续」可以接着做。"
            } else {
                "任务跑满了步数上限（累计 $step 步）还没完成，已停下。最后进展：${history.lastOrNull() ?: "没有进展"}。"
            }
            AuditLog.append(ctx, "任务循环", "步数用完", history.lastOrNull() ?: "")
        } catch (e: Exception) {
            finalReply = "任务执行中出错：${e.message}"
            AuditLog.append(ctx, "任务循环", "异常", e.message ?: e.javaClass.simpleName)
        } finally {
            // 差距 8e：只清自己的 running 标志——新任务已入队（currentAliveSeq 已是新序号）
            // 时不碰，否则先到的 finally 会把还在跑的新任务的 running 清掉、让再下一个
            // run() 误判为“没任务在跑”。setTaskHidden(false) 不用守：单线程执行器保证
            // 先到的 finally 一定在新任务的 runBlocking 之前跑完，不会清到新任务头上。
            if (currentAliveSeq == mySeq) {
                running = false
                currentAlive = null
                currentAliveSeq = -1
            }
            TakeoverBorder.hide()
            com.jarvis.app.OrbController.setTaskHidden(false)
            if (ownsController && JarvisAccessibilityService.screenController === controller) {
                JarvisAccessibilityService.screenController = null
            }
            val reply = finalReply
            // 平时只在 alive() 时发（用户发了新指令就别抢话）；急停断点那句例外，
            // 靠 stoppedWithResume 发出去，不然用户不知道可以说“继续”接着做。
            if (reply != null && (alive() || stoppedWithResume)) onReply(reply)
        }
    }

    /** M19：现读锁屏状态（Andee CommandDispatcher.keyguardLocked 同款：调一次系统服务，取不到默认当没锁）。 */
    private fun isKeyguardLocked(ctx: Context): Boolean = runCatching {
        (ctx.applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    }.getOrDefault(false)

    /** M18：按动作种类叙述这一步在干什么（Andee LocalBrain.narrate(toolName) 同款）。 */
    private fun narrate(kind: String): String = when (kind) {
        "tap", "longPress", "tap_sel" -> "正在点按……"
        "type" -> "正在输入……"
        "submit" -> "正在提交……"
        "swipe", "scroll_to" -> "正在滑动……"
        "zoom" -> "正在放大看……"
        "openApp" -> "正在打开应用……"
        "browse", "browserTap", "browserBack" -> "正在打开网页……"
        "back", "home" -> "正在返回……"
        "find" -> "正在找目标……"
        "flow" -> "正在按流程操作……"
        "wait", "wait_idle", "wait_change" -> "正在等一等……"
        else -> "正在执行 $kind……"
    }

    /** 历史段：总字符超预算时从最旧的整步丢弃，保留最近的，并在段首注明丢了几步。 */
    private fun historySection(history: List<String>): String {
        if (history.isEmpty()) return ""
        var total = history.sumOf { it.length + 1 }
        var from = 0
        while (total > HISTORY_CHARS && from < history.size - 1) {
            total -= history[from].length + 1
            from++
        }
        val sb = StringBuilder()
        if (from > 0) sb.append("（更早的 $from 步已省略）\n")
        for (i in from until history.size) sb.append(history[i]).append('\n')
        return sb.toString()
    }

    /**
     * 第六批·流内是否含有本来就会换前台应用的步骤（openApp/back/home，或带 handover 的步）：
     * flow 的 kind 单看分不出来，执行后按流内实际步骤判——含切应用步骤的流，包名变了不算跑偏；
     * 纯屏内动作的流，包名变了照样算跑偏。不是 flow、或 steps 不是数组/为空时返回 false。
     */
    private fun flowHasAppSwitch(action: JSONObject): Boolean {
        if (action.optString("action") != "flow") return false
        val steps = action.optJSONArray("steps") ?: return false
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: continue
            if (s.optString("action") in EXPECTED_PKG_SWITCH_KINDS || s.optBoolean("handover", false)) return true
        }
        return false
    }

    /**
     * 动作签名（重复守卫用）：动作种类 + 决定它「是同一个动作」的关键参数。
     * wait 把 ms 折进签名——Andee 的纪律就是连着等同一次数会被守卫拦下，
     * 要等更久必须把毫秒数往上加（提示词里写明了）。
     */
    private fun signatureOf(action: JSONObject): String {
        val kind = action.optString("action")
        val sb = StringBuilder(kind)
        // 本地流：签名按流里各步的全量参数拼（Andee ToolCall.signature() =
        // "$name/$argumentsRaw" 同款）——只拼动作种类会让点不同目标的流被误判
        // 为原地打转；每步的 eid/坐标/文字都进签名，目标不同就是不同的流。
        if (kind == "flow") {
            val steps = action.optJSONArray("steps")
            if (steps != null) {
                for (i in 0 until steps.length()) {
                    sb.append('|').append(steps.optJSONObject(i)?.toString().orEmpty())
                }
            }
            return sb.toString()
        }
        for (k in listOf("eid", "cell", "part", "on", "text", "app", "direction")) {
            val v = action.optString(k)
            if (v.isNotEmpty()) sb.append('|').append(k).append('=').append(v)
        }
        action.optJSONObject("sel")?.let { sb.append("|sel=").append(it.toString()) }
        for (k in listOf("x", "y", "x1", "y1", "x2", "y2", "ms", "screens", "maxSwipes", "index")) {
            if (action.has(k)) sb.append('|').append(k).append('=').append(action.optInt(k))
        }
        return sb.toString()
    }

    /** 一步的简短描述（进历史用）：种类 + 关键参数，比光秃秃的种类更能让模型对上号。 */
    private fun describeAction(action: JSONObject): String {
        val kind = action.optString("action")
        val detail = when (kind) {
            "tap", "longPress" -> action.optString("eid").ifEmpty {
                action.optJSONObject("sel")?.let { "选「${UiSelector.shortSummary(it)}」" } ?: action.optString("cell").ifEmpty {
                    if (action.has("x")) "${action.optInt("x")},${action.optInt("y")}" else ""
                }
            }
            "tap_sel" -> action.optJSONObject("sel")?.let { "选「${UiSelector.shortSummary(it)}」" }.orEmpty()
            "find" -> action.optJSONObject("sel")?.let { "找「${UiSelector.shortSummary(it)}」" }.orEmpty()
            "type" -> "「${action.optString("text").take(20)}」"
            "openApp" -> action.optString("app")
            "swipe" -> "(${action.optInt("x1")},${action.optInt("y1")})→(${action.optInt("x2")},${action.optInt("y2")})"
            "zoom" -> action.optString("cell")
            "wait" -> "${action.optLong("ms", 1200)}ms"
            "wait_idle" -> "等稳 ${action.optLong("ms", 5000)}ms"
            "wait_change" -> "等变 ${action.optLong("ms", 5000)}ms"
            "scroll_to" -> "滑找「${action.optString("text").ifEmpty { action.optString("eid") }}」"
            "flow" -> {
                val steps = action.optJSONArray("steps")
                if (steps == null) "" else (0 until steps.length())
                    .map { steps.optJSONObject(it)?.optString("action").orEmpty() }
                    .joinToString("→")
            }
            else -> ""
        }
        return if (detail.isEmpty()) kind else "$kind $detail"
    }

    /**
     * 无障碍截图 → JPEG base64（批九同路径：ScreenController 出带网格的 PNG，GridOverlay 只做编码）。
     *
     * 修复（截图通道追查后新增）：原来不管 `screenshot()` 是返回空 `png_base64` 还是直接抛异常
     * （超时 / `onFailure(errorCode)`，比如 FLAG_SECURE 窗口），这里一律吞成裸 `null`，连异常
     * message 里带的具体 errCode 都没留下。现在两种失败都写一条审计日志，把 `screenshot()`
     * 异常里的原始 message（含 errCode）原样记下来——下次真机再卡在这里，审计日志里就能直接
     * 看到是哪种失败，不用再靠猜。
     */
    private fun accessibilityShot(controller: ScreenController, ctx: Context): String? {
        return try {
            val shot = controller.screenshot()
            val b64 = shot.optString("png_base64")
            if (b64.isEmpty()) {
                AuditLog.append(ctx, "截图通道", "accessibility", "返回了结果但没有 png_base64 字段（未抛异常）")
                null
            } else {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val jpeg = bmp?.let { GridOverlay.toJpegBase64(it) }
                bmp?.recycle()
                // 2026-10-24 跨应用修复第 3 件：成功也要留痕。旧逻辑只记失败，
                // 审计里没记录时分不清“没拍”和“拍了没用”。
                if (jpeg != null) {
                    AuditLog.append(ctx, "截图通道", "accessibility", "已拿到图")
                } else {
                    AuditLog.append(ctx, "截图通道", "accessibility", "解码失败：b64 非空但解不出位图")
                }
                jpeg
            }
        } catch (e: Exception) {
            // screenshot() 的异常 message 本身就带着 errCode（见 ScreenController.screenshot()
            // 的 "screenshot failed with code $errCode" / "screenshot timeout"），原样记下。
            AuditLog.append(ctx, "截图通道", "accessibility", "失败：${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /**
     * 录屏兜底（批十四，BodyCoreService 的 captureFallback 同位）：无障碍截图
     * 拿不到图时才走。录屏图是原始像素：缩到长边 [ScreenCapture.SHOT_MAX_SIDE]
     * 后直接编码，不画网格——它没有 shot_id 与点击绑定，只能看不能按图瞄准，
     * 提示词里会标明。
     *
     * 修复（截图通道追查后新增）：之前这里连同 grab() 超时、解码异常，全部静默吃成
     * null，审计日志里一条痕迹都没有，没法区分"没授权"和"这次没抓到帧"。现在三种失败
     * 分别记一条，"未授权"这条按时间限流（60 秒内最多记一条），不然失败是常态、每一步
     * 都记会把审计日志刷屏。注：授权入口在 MainActivity（设置-视觉看屏"录屏授权"按钮
     * + onActivityResult 转 start()）是接好的；isAvailable() 为 false 只说明本次进程里
     * 还没授权过（MediaProjection 授权不随进程重启保留，进程死后需重新授权）。
     */
    private fun projectionShot(ctx: Context): String? {
        val cap = ScreenCapture.shared(ctx)
        if (!cap.isAvailable()) {
            val now = System.currentTimeMillis()
            if (now - lastProjectionUnavailableLogAt > PROJECTION_LOG_THROTTLE_MS) {
                lastProjectionUnavailableLogAt = now
                AuditLog.append(
                    ctx, "截图通道", "media_projection",
                    "不可用：本次进程里尚未获得录屏授权（去设置-视觉看屏点一次录屏授权；" +
                        "授权不随进程重启保留，进程死后需重新授权）"
                )
            }
            return null
        }
        return try {
            val bmp = cap.grab()
            if (bmp == null) {
                AuditLog.append(ctx, "截图通道", "media_projection", "grab() 超时，没等到帧")
                return null
            }
            val scaled = downscale(bmp, ScreenCapture.SHOT_MAX_SIDE)
            val jpeg = GridOverlay.toJpegBase64(scaled)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            // 2026-10-24 跨应用修复第 3 件：成功留痕（同 accessibility 通道）。
            AuditLog.append(ctx, "截图通道", "media_projection", "已拿到图")
            jpeg
        } catch (e: Exception) {
            AuditLog.append(ctx, "截图通道", "media_projection", "失败：${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** media_projection"未授权"这条日志的节流时间戳，避免每一步都重复刷同一条结论。 */
    @Volatile
    private var lastProjectionUnavailableLogAt = 0L
    private const val PROJECTION_LOG_THROTTLE_MS = 60_000L

    private fun downscale(bmp: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

/** 同步等一轮模型回复（askOnce 是回调式，这里用闩锁包成同步，跑在循环线程上）。
 * M11：不等满 200 秒——每 200ms 轮询一次闩锁，每轮检查任务是否还存活，
 * 不存活就提前返回，急停不用等模型回话。L2：isAlive 同时传给任务 client
 * （接口的 askOnce 接受 isAlive，见 net/LlmClient.kt），不要只靠外层闩锁轮询兜底。 */
private fun askModel(ctx: Context, userText: String, image: String?, isAlive: () -> Boolean = { true }): Pair<String?, String?> {
val latch = CountDownLatch(1)
var reply: String? = null
var error: String? = null
// 第十五批·LLM 注册表：任务大脑经注册表按模型开关取（自定义 → CustomClient
// 适配、GLM → AutoGLMClient 适配），分发在 LlmRegistry.taskClient 里；这里
// 只认 LlmClient 接口，不再硬编码用哪个 client。行为与改前一字不变：同一模式
// 下还是原来那个 client、同一回调、同一超时（MODEL_TIMEOUT_SEC 闩锁在下面）。
LlmRegistry.taskClient(ctx).askOnce(
ctx, SYSTEM_PROMPT, userText, image,
isAlive = isAlive,
onResult = { reply = it; latch.countDown() },
onError = { error = it; latch.countDown() }
)
val deadlineMs = System.currentTimeMillis() + MODEL_TIMEOUT_SEC * 1000
while (true) {
val done = try {
latch.await(200, TimeUnit.MILLISECONDS)
} catch (_: InterruptedException) {
return null to "问模型时被打断"
}
if (done) break
if (!isAlive()) return null to "任务已被取消，不再等模型回复"
if (System.currentTimeMillis() >= deadlineMs) return null to "模型 $MODEL_TIMEOUT_SEC 秒没有回复"
}
return reply to error
}

/** 第二批·核验结论：A 成功、B 错页、C 无变化。 */
private enum class ReflectVerdict { SUCCESS, WRONG_PAGE, NO_CHANGE }

/**
 * 第二批·从核验回复里抠结论：某行以字母 A/B/C 开头、后跟分隔符（.、。：:，、空格、- 等）即认，
 * 余下文字是说明；单字母一行也认；抠不到返回 null（调用方当没验证过）。
 * 字母后必须跟分隔符——裸单词 "Action..." 开头是 A 也不能误认。
 * 注意：matchEntire 自带两端锚定，正则里不写 ^ 与 $（Kotlin 里 $ 会触发字符串插值）。
 */
private fun parseReflectVerdict(reply: String): Pair<ReflectVerdict, String>? {
for (rawLine in reply.lineSequence()) {
val line = rawLine.trim()
if (line.isEmpty()) continue
if (line.length == 1 && line[0].uppercaseChar() in "ABC") {
val verdict = when (line[0].uppercaseChar()) {
'A' -> ReflectVerdict.SUCCESS
'B' -> ReflectVerdict.WRONG_PAGE
else -> ReflectVerdict.NO_CHANGE
}
return verdict to ""
}
val m = Regex("""([ABCabc])[.、。：:，,\s\-—）\)]+(.*)""").matchEntire(line) ?: continue
val verdict = when (m.groupValues[1].uppercase()) {
"A" -> ReflectVerdict.SUCCESS
"B" -> ReflectVerdict.WRONG_PAGE
else -> ReflectVerdict.NO_CHANGE
}
return verdict to m.groupValues[2].trim()
}
return null
}

/**
 * 第二批·核验调用：同步问一次短问答（经注册表取聊天 client，返回原始文本）。
 * GLM 模式下任务 client（AutoGLMClient 适配）只回转好的 JSON 动作串，问不出 A/B/C，
 * 所以核验统一走聊天模型（GLM 模式 glm-4.5 / 自定义模式用户填的模型）。
 * 返回 null = 没验证成（网络失败/解析失败/超时/被打断），调用方当没验证过。
 */
private fun askReflect(
ctx: Context, userText: String, image: String?,
isAlive: () -> Boolean = { true }
): Pair<ReflectVerdict, String>? {
// 降速 1 秒：与主循环问模型前同款，防一分钟内连发撞限流。
try { Thread.sleep(1000) } catch (_: InterruptedException) { return null }
val latch = CountDownLatch(1)
var reply: String? = null
var error: String? = null
LlmRegistry.chatClient().askOnce(
ctx, REFLECT_SYSTEM_PROMPT, userText, image,
isAlive = isAlive,
onResult = { reply = it; latch.countDown() },
onError = { error = it; latch.countDown() }
)
val deadlineMs = System.currentTimeMillis() + REFLECT_TIMEOUT_SEC * 1000
while (true) {
val done = try {
latch.await(200, TimeUnit.MILLISECONDS)
} catch (_: InterruptedException) {
return null
}
if (done) break
if (!isAlive()) return null
if (System.currentTimeMillis() >= deadlineMs) return null
}
if (error != null || reply == null) return null
return parseReflectVerdict(reply.orEmpty())
}

/**
 * 第十四批·Manager 短问答：同步问一次规划（经注册表取聊天 client，不带图——
 * 规划不需要截图，任务原文 + 界面摘要 + 最近进展 + 失败记录就够了，省 token）。
 * 与第二批 askReflect 同款调用方式（降速 1 秒、短问答超时 60 秒、急停提前返回；
 * 超时复用 REFLECT_TIMEOUT_SEC，不另起常量）。
 * 返回 null = 没问到计划（网络失败/解析失败/超时/被打断），调用方当没规划过。
 */
private fun askManager(
ctx: Context, userText: String,
isAlive: () -> Boolean = { true }
): String? {
try { Thread.sleep(1000) } catch (_: InterruptedException) { return null }
val latch = CountDownLatch(1)
var reply: String? = null
var error: String? = null
LlmRegistry.chatClient().askOnce(
ctx, MANAGER_SYSTEM_PROMPT, userText, null,
isAlive = isAlive,
onResult = { reply = it; latch.countDown() },
onError = { error = it; latch.countDown() }
)
val deadlineMs = System.currentTimeMillis() + REFLECT_TIMEOUT_SEC * 1000
while (true) {
val done = try {
latch.await(200, TimeUnit.MILLISECONDS)
} catch (_: InterruptedException) {
return null
}
if (done) break
if (!isAlive()) return null
if (System.currentTimeMillis() >= deadlineMs) return null
}
if (error != null || reply == null) return null
return reply
}

/** 第十四批·Manager 提示词（短问答）：任务原文、当前应用与界面摘要、当前计划、
 * 最近进展（最多 5 步，每步截断 300 字）、最近失败记录、触发原因。只问计划，
 * 不问动作；不带图、不带全量历史（短调用，防 token 膨胀）。 */
private fun buildManagerText(
trigger: ManagerTrigger, taskText: String, pkg: String, compact: String,
history: List<String>, failureNotes: List<String>, subgoals: List<Subgoal>
): String = buildString {
append("任务：").append(taskText).append('\n')
append("当前前台应用：").append(pkg.ifEmpty { "未知" }).append('\n')
append("当前界面（紧凑表前 1200 字）：\n")
append(compact.take(REFLECT_COMPACT_CHARS).ifEmpty { "（无可操作元素）" }).append('\n')
val completedTexts = subgoalCompletedTexts(subgoals)
val planTexts = subgoalActiveTexts(subgoals)
if (planTexts.isNotEmpty() || completedTexts.isNotEmpty()) {
append("当前计划：\n")
for (c in completedTexts) append("✓ ").append(c).append('\n')
for ((i, p) in planTexts.withIndex()) append(i + 1).append(". ").append(p).append('\n')
}
if (history.isNotEmpty()) {
append("最近进展（最多 5 步）：\n")
for (h in history.takeLast(5)) append(h.take(300)).append('\n')
}
if (failureNotes.isNotEmpty()) {
append("最近失败：\n")
for (n in failureNotes) append(n).append('\n')
}
append(
when (trigger) {
ManagerTrigger.EMPTY_PLAN -> "任务刚开始，还没有子目标计划，请按任务原文列出编号子目标列表。"
ManagerTrigger.STUCK -> "最近连续失败，当前做法卡住了，请修订计划（重排、改写、插入纠正步骤、或换一条完全不同的策略）。"
ManagerTrigger.DONE_CHALLENGED -> "执行者刚才说任务做完了，但核验没通过（上一步结果未知或界面没变），任务其实还没做完，请按剩余工作修订计划。"
}
).append("只输出计划块，不输出动作、不解释。")
}

/**
 * 第十四批·跑一次 Manager 规划：问一次短问答，问到的计划块经 applyPlanUpdate
 * 进子目标状态机（第十三批，与第一批 Executor 回复里的计划段同款语义：
 * ✓ 的项 → SUCCESS，重列的 FAILURE 项 → NOT_STARTED，没重列的 FAILURE 项保留）。
 * Manager 的输出只改 subgoals，不直接发动作、不碰失败计数、不碰历史与笔记。
 * 问不到（网络/解析/超时/被打断）或回复里抠不到计划块时当没规划过、按原流程
 * 继续：只记一条审计，不记失败、不阻塞、不重试（重试由触发旗子拦住，
 * 下一轮 Executor 仍会按第一批机制自己列计划/修订计划）。
 */
private fun runManagerPlanning(
ctx: Context, trigger: ManagerTrigger, taskText: String, pkg: String, compact: String,
history: List<String>, failureNotes: List<String>, subgoals: MutableList<Subgoal>,
isAlive: () -> Boolean = { true }
) {
val reply = askManager(ctx, buildManagerText(trigger, taskText, pkg, compact, history, failureNotes, subgoals), isAlive)
if (reply == null) {
AuditLog.append(ctx, "任务循环", "Manager 规划没问到（${trigger.label}）", "网络/解析/超时，按原流程继续")
return
}
val parsed = parsePlan(reply)
if (parsed == null) {
AuditLog.append(ctx, "任务循环", "Manager 规划无结果（${trigger.label}）", "回复里没抠到计划块，按原流程继续")
return
}
applyPlanUpdate(parsed, subgoals)
AuditLog.append(ctx, "任务循环", "Manager 规划已更新（${trigger.label}）", "新计划 ${parsed.size} 条")
}

/** 第二批·核验提示词（单步）：任务、动作、执行结果说辞、执行前/后界面摘要。 */
private fun buildStepReflectText(
taskText: String, actionDesc: String, resultSummary: String,
beforePkg: String, beforeCompact: String, afterPkg: String, afterCompact: String
): String = buildString {
append("任务：").append(taskText).append('\n')
append("刚才执行了：").append(actionDesc).append('\n')
append("执行结果说：").append(resultSummary.take(120)).append('\n')
append("执行前界面（").append(beforePkg.ifEmpty { "未知应用" }).append("）：\n")
append(beforeCompact.take(REFLECT_COMPACT_CHARS).ifEmpty { "（无可操作元素）" }).append('\n')
append("执行后界面（").append(afterPkg.ifEmpty { "未知应用" }).append("）：\n")
append(afterCompact.take(REFLECT_COMPACT_CHARS).ifEmpty { "（无可操作元素）" }).append('\n')
append("请判断这一步到底成没成，只输出一个字母加一句说明（A 成功 / B 错页 / C 无变化）。")
}

/** 第二批·核验提示词（done 宣称）：任务原文、模型说的完成理由、当前界面摘要。 */
private fun buildDoneReflectText(
taskText: String, doneMessage: String, pkg: String, compact: String
): String = buildString {
append("任务：").append(taskText).append('\n')
append("执行者说任务做完了，理由是：").append(doneMessage.take(120)).append('\n')
append("当前界面（").append(pkg.ifEmpty { "未知应用" }).append("）：\n")
append(compact.take(REFLECT_COMPACT_CHARS).ifEmpty { "（无可操作元素）" }).append('\n')
append("请判断任务是不是真的做完了，只输出一个字母加一句说明（A 确实完成了 / B 还没完成或到了错误页面 / C 界面无变化、无法确认）。")
}

/**
 * 第三批·输入校验：归一化（Minitap 文本输入后校验的 TaskLoop 版，确定性代码、不调模型）。
 *
 * 归一化接受的变换（输入框的正常行为，判成一致不算失败）：
 * - 全角转半角（含全角空格、全角数字字母标点）；
 * - 所有空白字符（空格、换行、首尾空格、不间断空格）；
 * - 手机号/金额自动加的分隔符：半角逗号（千分位，如 1,000）、连字符（电话分段，如 138-0000-0000）；
 * - 大小写（输入法首字母自动大写）。
 * 不接受的变换（判成失败）：
 * - 字段比应打的短（打了一半、根本没打进去）；
 * - 字段比应打的多出后缀（输入法联想补全多打了字、旧文字没清干净又叠了新的）；
 * - 其他任何内容差异（打错地方、打错字）。
 * 注意：大小写折叠会放过验证码这类大小写敏感文本里“只错大小写”的情况，
 * 是为容忍输入法自动大写付的代价；小数点绝不去掉（1.5 与 15 是两回事）。
 */
private fun normalizeTypedText(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) {
        val c = when {
            ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFF01 + 0x21).toChar()
            ch == '\u3000' -> ' '
            else -> ch
        }
        if (c.isWhitespace() || c == '\u00A0' || c == ',' || c == '-') continue
        sb.append(c)
    }
    return sb.toString().lowercase()
}

/** 第三批·归一化后比对：相等才算一致；应打文本归一化后为空（纯空白/纯分隔符）时返回 false，调用方先拦下这种情况（树上看不出空白，无法验证）。 */
private fun typedTextMatches(expected: String, actual: String): Boolean {
    val e = normalizeTypedText(expected)
    if (e.isEmpty()) return false
    return e == normalizeTypedText(actual)
}

/** 第三批·两个字符串的最长公共前缀长度（输入校验挑“最像”的字段时用）。 */
private fun commonPrefixLength(a: String, b: String): Int {
    var i = 0
    while (i < a.length && i < b.length && a[i] == b[i]) i++
    return i
}

/**
 * 第三批·输入校验失败的定性：给模型一句能指导下一轮的话。
 * actual 是 fresh 树里与应打文本最像的那个字段的实际文字（归一化后比较）。
 */
private fun describeTypeMismatch(expected: String, actual: String): String {
    val e = normalizeTypedText(expected)
    val a = normalizeTypedText(actual)
    return when {
        a.isEmpty() -> "字段是空的，没打进去"
        a.length < e.length && e.startsWith(a) -> "只打进去一部分"
        a.length > e.length && a.startsWith(e) -> "框里多了多余文字"
        else -> "框内文字对不上"
    }
}

/**
 * 第三批·从 fresh 树里抽所有输入框的当前文字（labelOf 语义：有文字就是文字，
 * 没文字就是描述或 #内部 id——# 开头的当空字段处理）。
 * 主源是紧凑表里类型为 Input 的行（行格式 sid|Input|标签|[x,y]，标签里没有 |）；
 * 紧凑表里一个 Input 都没有（被 40 行上限截掉）时才扫元素大纲，按类名认 EditText
 * （行格式 e3*|EditText|标签|[x,y]，可能有缩进）。返回空列表 = 一个输入框都没
 * 找到（跳页、输入框已消失、树被扣留），调用方当无法验证。
 * 局限：WebView 内嵌输入框在紧凑表里是 Text 类型、大纲里是 WebView 类，
 * 认不出来，会返回空列表而跳过校验；SeekBar/Spinner 在紧凑表里也标 Input，
 * 它们的标签极少会撞上应打文字，撞上时才会误判为一致。
 */
private fun extractInputFieldTexts(compact: String, elements: String): List<String> {
    val fromCompact = compact.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size >= 3 && parts[1] == "Input") {
                val label = parts[2]
                if (label.startsWith("#")) "" else label
            } else null
        }.toList()
    if (fromCompact.isNotEmpty()) return fromCompact
    return elements.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size >= 3 && parts[1].contains("EditText")) {
                val label = parts[2]
                if (label.startsWith("#")) "" else label
            } else null
        }.toList()
}

/** 从模型回复里抠出第一个 JSON 对象；抠不到返回 null（调用方带原因重试）。 */
private fun parseAction(reply: String): JSONObject? {
val start = reply.indexOf('{')
val end = reply.lastIndexOf('}')
if (start < 0 || end <= start) return null
return try {
val obj = JSONObject(reply.substring(start, end + 1))
if (obj.optString("action").isEmpty()) null
else if (obj.optString("action") == "flow" && !isValidFlow(obj)) null
else obj
} catch (_: Exception) {
null
}
}

/** 第一批·计划项：text 为子目标文字，done 为模型是否标了完成。 */
private data class PlanItem(val text: String, val done: Boolean)

/**
 * 第一批·从模型回复里宽容抠出编号子目标列表。
 * 格式（见 SYSTEM_PROMPT）：回复里先有一行“计划：”，其后每行“1. 文字”或
 * “1. ✓ 文字”（✓ 或 [完成]/[已完成] 表示已完成）。只看 JSON 动作对象之外的
 * 文本；没找到计划块返回 null（调用方保留旧计划，不失败、不报错）。
 * 上限 MAX_PLAN_ITEMS 条，每条截断 60 字；同一条只取一次。
 * 注意：matchEntire 自带两端锚定，正则里不写 ^ 与 $（Kotlin 里 $ 会触发
 * 字符串插值，不能直接写进正则字面量）。
 */
private fun parsePlan(reply: String): List<PlanItem>? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    val outside = if (start >= 0 && end > start) {
        reply.substring(0, start) + "\n" + reply.substring(end + 1)
    } else {
        reply
    }
    val lines = outside.lineSequence().map { it.trim() }.toList()
    val headerIdx = lines.indexOfFirst { it.startsWith("计划") }
    if (headerIdx < 0) return null
    val items = mutableListOf<PlanItem>()
    val numbered = Regex("""(\d{1,2})[.、)]\s*(.*)""")
    for (i in headerIdx + 1 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) continue
        val m = numbered.matchEntire(line) ?: break
        var rest = m.groupValues[2].trim()
        var done = false
        when {
            rest.startsWith("✓") -> { done = true; rest = rest.removePrefix("✓").trim() }
            rest.startsWith("[完成]") -> { done = true; rest = rest.removePrefix("[完成]").trim() }
            rest.startsWith("[已完成]") -> { done = true; rest = rest.removePrefix("[已完成]").trim() }
        }
        if (rest.isEmpty()) continue
        val item = PlanItem(rest.take(60), done)
        if (!items.contains(item)) items += item
        if (items.size >= MAX_PLAN_ITEMS) break
    }
    return if (items.isEmpty()) null else items
}

/**
 * 第十三批·子目标状态（Minitap SubgoalStatus 的 TaskLoop 版）：
 * NOT_STARTED 待做、PENDING 进行中、SUCCESS 已完成、FAILURE 失败。
 * 转移（只在 TaskLoop 的 runBlocking 循环状态里，不另起文件）：
 * - NOT_STARTED → PENDING：真正执行动作时（markSubgoalStarted），一次只做一个；
 * - PENDING → SUCCESS：模型在计划块里标 ✓（applyPlanUpdate），证据是模型看屏后的
 *   观测（元认知规则“只凭观测证据标完成”）；模型标 ✓ 时 NOT_STARTED 也可直接进
 *   SUCCESS（一步做掉多项、或计划到达时已带 ✓）；PENDING → FAILURE：连续失败
 *   升级首次触发时（markSubgoalFailed，每次升级只标一次）；
 * - FAILURE → NOT_STARTED：模型修订计划时重列该项（applyPlanUpdate，换招重试）；
 *   没重列的 FAILURE 项留在 FAILURE 里不再做，提示词里不渲染、断点保存时并入 plan 列。
 * 状态只服务于计划段渲染与升级逻辑，不参与失败计数、不影响 done 判定。
 */
private enum class SubgoalStatus { NOT_STARTED, PENDING, SUCCESS, FAILURE }

/** 第十三批·一个子目标：文字 + 状态（文字是唯一身份，同一条只留一项）。 */
private data class Subgoal(val text: String, val status: SubgoalStatus)

/**
 * 第十三批·提示词里编号待做行用的文字：NOT_STARTED / PENDING 项（FAILURE 项
 * 不渲染——模型没重列 = 不再做；失败经过在文字历史与升级段里）。
 */
private fun subgoalActiveTexts(subgoals: List<Subgoal>): List<String> =
    subgoals.filter { it.status == SubgoalStatus.NOT_STARTED || it.status == SubgoalStatus.PENDING }.map { it.text }

/** 第十三批·提示词里 ✓ 已完成行用的文字：SUCCESS 项。 */
private fun subgoalCompletedTexts(subgoals: List<Subgoal>): List<String> =
    subgoals.filter { it.status == SubgoalStatus.SUCCESS }.map { it.text }

/**
 * 第十三批·断点保存时 plan 列用的文字：SUCCESS 之外的全部（NOT_STARTED /
 * PENDING / FAILURE）。TaskResumeState 仍只存 plan/completed 两个字符串列表，
 * 不改断点格式；恢复时见 runBlocking 开头的注释（plan 列一律重建为 NOT_STARTED）。
 */
private fun subgoalResumePlanTexts(subgoals: List<Subgoal>): List<String> =
    subgoals.filter { it.status != SubgoalStatus.SUCCESS }.map { it.text }

/**
 * 第十三批·NOT_STARTED → PENDING：把最早的待做子目标标为进行中。同一时间
 * 最多一个 PENDING（已有 PENDING 时不另起一个）；没有待做项时什么都不做。
 */
private fun markSubgoalStarted(subgoals: MutableList<Subgoal>) {
    if (subgoals.any { it.status == SubgoalStatus.PENDING }) return
    val idx = subgoals.indexOfFirst { it.status == SubgoalStatus.NOT_STARTED }
    if (idx >= 0) subgoals[idx] = subgoals[idx].copy(status = SubgoalStatus.PENDING)
}

/**
 * 第十三批·PENDING → FAILURE：把进行中的子目标标失败。没有进行中的项时
 * 什么都不做；不碰失败计数（调用方只在连续失败升级首次触发时调一次）。
 */
private fun markSubgoalFailed(subgoals: MutableList<Subgoal>) {
    val idx = subgoals.indexOfFirst { it.status == SubgoalStatus.PENDING }
    if (idx >= 0) subgoals[idx] = subgoals[idx].copy(status = SubgoalStatus.FAILURE)
}

/**
 * 第十三批·把解析出的计划合并进子目标状态机（第一批 applyPlanUpdate 的同款语义，
 * 只是 plan/completed 两个列表换成带状态的 subgoals 一项）：
 * - 解析出的列表为准（模型修订计划时可删改步骤）：✓ 的项 → SUCCESS；
 *   未标 ✓ 的项重列时，旧状态是 FAILURE → NOT_STARTED（换招重试），是 PENDING →
 *   PENDING（还在做），是 SUCCESS → SUCCESS（已完成的不因少个 ✓ 就打回），
 *   全新 → NOT_STARTED；同一条在解析列表里出现两次时 ✓ 的那次说了算。
 * - 旧表里没再出现的项：SUCCESS 照旧累积（第一批语义：completed 只增不减，
 *   只受 MAX_COMPLETED_ITEMS 上限裁剪）；FAILURE 保留（不再做，等模型重列才
 *   回 NOT_STARTED）；NOT_STARTED / PENDING 删掉（计划修订删步）。
 * - 上限：SUCCESS 超 MAX_COMPLETED_ITEMS 时裁最旧的；非 SUCCESS 项超
 *   MAX_PLAN_ITEMS 时裁最旧的 FAILURE（解析出的未完成项本身不超过上限）。
 */
private fun applyPlanUpdate(parsed: List<PlanItem>, subgoals: MutableList<Subgoal>) {
    val oldByText = subgoals.associateBy { it.text }
    val next = mutableListOf<Subgoal>()
    for (item in parsed) {
        val old = oldByText[item.text]
        val status = when {
            item.done -> SubgoalStatus.SUCCESS
            old?.status == SubgoalStatus.FAILURE -> SubgoalStatus.NOT_STARTED
            old?.status == SubgoalStatus.PENDING -> SubgoalStatus.PENDING
            old?.status == SubgoalStatus.SUCCESS -> SubgoalStatus.SUCCESS
            else -> SubgoalStatus.NOT_STARTED
        }
        val at = next.indexOfFirst { it.text == item.text }
        if (at < 0) {
            next += Subgoal(item.text, status)
        } else if (status == SubgoalStatus.SUCCESS) {
            next[at] = next[at].copy(status = SubgoalStatus.SUCCESS)
        }
    }
    val parsedTexts = parsed.map { it.text }.toSet()
    for (old in subgoals) {
        if (old.text !in parsedTexts &&
            (old.status == SubgoalStatus.SUCCESS || old.status == SubgoalStatus.FAILURE)
        ) {
            next += old
        }
    }
    while (next.count { it.status == SubgoalStatus.SUCCESS } > MAX_COMPLETED_ITEMS) {
        val at = next.indexOfFirst { it.status == SubgoalStatus.SUCCESS }
        next.removeAt(at)
    }
    while (next.count { it.status != SubgoalStatus.SUCCESS } > MAX_PLAN_ITEMS) {
        val at = next.indexOfFirst { it.status == SubgoalStatus.FAILURE }
        if (at < 0) break
        next.removeAt(at)
    }
    subgoals.clear()
    subgoals.addAll(next)
}

/**
 * 第十二批·从模型回复里宽容抠出持久笔记段，复用第一批 parsePlan 的解析套路：
 * 只看 JSON 动作对象之外的文本；找一行“笔记”开头的标题头，其后每行以 - 开头
 * （也认 • / * / · 开头，或编号行）即一条笔记；遇到既不是笔记行也不是空行的
 * 行就停（下一段的标题头、其他文字都到此为止）。没找到笔记块、或块里一条都
 * 抠不到时返回 null（调用方保留旧笔记，不失败、不报错）。上限 MAX_NOTE_ITEMS
 * 条，每条截断 MAX_NOTE_CHARS 字；花括号已剔除（笔记块附在 JSON 动作串之后时
 * 花括号会让 parseAction 取 JSON 越界，GLM 侧同款处理）；同一条只取一次。
 * 注意：标题头当行带的内容（如“笔记：账号尾号 1393”）不认，与 parsePlan
 * 同款——模型须把每条笔记写在标题头之后的行上。
 */
private fun parseNotes(reply: String): List<String>? {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    val outside = if (start >= 0 && end > start) {
        reply.substring(0, start) + "\n" + reply.substring(end + 1)
    } else {
        reply
    }
    val lines = outside.lineSequence().map { it.trim() }.toList()
    val headerIdx = lines.indexOfFirst { it.startsWith("笔记") }
    if (headerIdx < 0) return null
    val items = mutableListOf<String>()
    val numbered = Regex("""(\d{1,2})[.、)]\s*(.*)""")
    for (i in headerIdx + 1 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) continue
        val rest = when {
            line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ") || line.startsWith("· ") -> line.substring(2).trim()
            line.startsWith("-") || line.startsWith("•") || line.startsWith("*") || line.startsWith("·") -> line.substring(1).trim()
            else -> numbered.matchEntire(line)?.groupValues?.get(2)?.trim() ?: break
        }
        if (rest.isEmpty()) continue
        val cleaned = rest.replace("{", "").replace("}", "").trim()
        if (cleaned.isEmpty()) continue
        val item = cleaned.take(MAX_NOTE_CHARS)
        if (!items.contains(item)) items += item
        if (items.size >= MAX_NOTE_ITEMS) break
    }
    return if (items.isEmpty()) null else items
}

/**
 * 第十二批·把解析出的笔记合并进循环状态：以新段为准全量替换旧笔记
 * （与第一批的 applyPlanUpdate 同款语义）；调用方只在 parseNotes 非空时调，
 * 没写笔记段的轮次保留旧笔记。笔记不参与失败计数、不触发升级、不影响 done 判定。
 */
private fun applyNotesUpdate(parsed: List<String>, notes: MutableList<String>) {
    notes.clear()
    notes.addAll(parsed.distinct().take(MAX_NOTE_ITEMS))
}

/** 本地流的结构校验：steps 必须是 1–15 项的数组，每项是带 action 的 JSON 对象。
 * 不合规按无法解析处理，模型会收到重试提示。 */
private fun isValidFlow(obj: JSONObject): Boolean {
val steps = obj.optJSONArray("steps") ?: return false
if (steps.length() == 0 || steps.length() > FlowRuntime.MAX_FLOW_STEPS) return false
for (i in 0 until steps.length()) {
val s = steps.optJSONObject(i) ?: return false
if (s.optString("action").isEmpty()) return false
}
return true
}

/** 一步执行的结果（摘要、是否成功、界面是否变了、zoom 放大图）。
 * Phase 5：加错误码 code（回喂模型按码换招）与 outcomeUnknown（变异动作超时
 * 后“不知道成没成”，绝不当成功）；成功时 code 为空、outcomeUnknown 为假。
 * internal：FlowRuntime 的变异步复用这里的执行映射。 */
internal data class StepResult(
val summary: String,
val ok: Boolean,
val screenChanged: Boolean?,
val zoomImage: String?,
val code: String = "",
val outcomeUnknown: Boolean = false
)

/**
* 执行一个动作。动作到 ScreenController 的映射（全部用它自己的瞄准体系，
* 不另造坐标换算）：有 eid 走 tapByEid/longPressByEid（按上一步元素大纲的
* 编号，最稳）；有 sel 走 tapBySelector/longPressBySelector（按多条件选择器
* 找第 N 个匹配，点之前先爬到最近的可点父节点）；有 cell 或 x/y 走
* tapAim/longPressAim（x/y 是所看图片的 0–1000 相对坐标，on 指明读自全屏图
* "screen" 还是放大图 "zoom"）；swipe 走 swipeNorm（0–1000 相对坐标直通，
* 可带 screens 连滑多屏）；zoom 走 zoomAim（只吃 cell，x/y 先换算成 cell）。
* 第九批·坐标契约：坐标类动作（tap/longPress 的 cell/x/y、swipe、zoom 中心点）
* 进 ScreenController 前先过坐标契约（contractSize：范围 + 尺寸已知 + 与最近
* 一次观察一致 + 换算后像素落屏内），不通过直接拒执行、错码 COORD_CONTRACT，
* 记一步失败、模型下一轮改走 eid；eid/sel 不受此限（活树重定位）。
* 第十一批·定位回退：eid/sel 在活树里找不到时按 dump 时记下的三重信息回退
* （resource_id 放宽 → bounds 位置 → 文字，多个同文字不乱点），三重都找不到
* 才报 ELEMENT_NOT_FOUND；回退成功不算失败、但结果里写明走了回退，模型下轮
* 用新编号；纯坐标动作（cell/x/y）不走回退。
* internal：FlowRuntime 的变异步复用这里的映射，单步行为不变。
*/
/**
 * H8：单步执行包一个 deadline（Andee LocalBrain.dispatchWithTimeout 同款）：
 * 默认 60 秒，动作自带 timeout_ms 则加 10 秒 slack。超时不要杀调用——只放弃
 * 等待，把 timed_out 结果回喂模型，让它读屏再决定（调用可能还在后台跑）。
 * FlowRuntime 的流内步也走这里，同样受这个上限保护。
 */
internal fun execute(
ctx: Context, controller: ScreenController, action: JSONObject
): StepResult {
val kind = action.optString("action")
// M19：锁屏下 screen.* 及相关动作直接报错，不执行、不烧步数；附带一句
// “手机锁了，解锁后我再继续”，让模型下轮再试。
if (kind !in setOf("wait", "wait_idle", "wait_change") && isKeyguardLocked(ctx)) {
return StepResult("手机锁了，解锁后我再继续：锁屏下 $kind 不可用（未执行）", false, null, null, "LOCKED")
}
val declared = action.optLong("timeout_ms", 0L)
val budget = if (declared > 0) declared + STEP_TIMEOUT_SLACK_MS else STEP_TIMEOUT_MS
val future = stepExecutor.submit<StepResult> { executeInner(ctx, controller, action) }
return try {
future.get(budget, TimeUnit.MILLISECONDS)
} catch (e: TimeoutException) {
StepResult(
"这一步 ${budget / 1000} 秒没返回（timed_out），可能还在后台跑——先读屏看看成没成，再决定下一步，不要直接重做",
false, null, null, "TIMEOUT", true
)
} catch (e: ExecutionException) {
StepResult("执行 $kind 出错：${e.cause?.message ?: e.message}", false, null, null, "UNKNOWN")
} catch (e: InterruptedException) {
Thread.currentThread().interrupt()
throw e
}
}

internal fun executeInner(
ctx: Context, controller: ScreenController, action: JSONObject
): StepResult {
val kind = action.optString("action")
// H6：交接步骤——动作 JSON 带 "handover":true 时，这一步是在已跳到另一应用的
// 界面上做的（点开分享面板/外部链接/权限弹窗之后，前台自然不是原应用），
// 把标志透传给 ScreenController，让 requireForeground 放行这一步。
// 缺省 false：其余步骤照旧校验，不撒网。流里的步同样走这里，字段原样透传。
val handover = action.optBoolean("handover", false)
return try {
when (kind) {
"tap", "longPress" -> {
val eid = action.optString("eid")
val cell = action.optString("cell").ifEmpty { null}
val part = action.optString("part").ifEmpty { null}
val on = action.optString("on").ifEmpty { "screen"}
val ms = action.optLong("ms", 700)
val result = try {
when {
eid.isNotEmpty() ->
if (kind == "tap") controller.tapByEid(eid, handover = handover)
else controller.longPressByEid(eid, ms, handover = handover)
action.has("sel") -> {
val sel = action.optJSONObject("sel")
?: return StepResult("$kind 的 sel 不是对象", false, null, null, "PARSE_ERROR")
val index = action.optInt("index", 0)
if (kind == "tap") controller.tapBySelector(sel, index, handover = handover)
else controller.longPressBySelector(sel, index, ms, handover = handover)
}
cell!= null ->
if (kind == "tap") controller.tapAim(on, null, null, cell, part, handover = handover)
else controller.longPressAim(on, null, null, cell, part, ms, handover = handover)
action.has("x") && action.has("y") -> {
val x = action.optInt("x", -1)
val y = action.optInt("y", -1)
if (kind == "tap") controller.tapAim(on, x, y, null, part, handover = handover)
else controller.longPressAim(on, x, y, null, null, ms, handover = handover)
}
else -> return StepResult("$kind 缺少 eid、sel、cell 或坐标", false, null, null, "PARSE_ERROR")
}
} catch (e: IllegalStateException) {
// 批三十：树盲应用上 tap 因 zoom 过期被拒时，自动对同一目标重拍一张 zoom 图
// 随拒绝一并返回，模型直接从这张图上用 on=zoom 再点，不用另花一步 zoom，
// 也不会在 zoom 和 tap 之间插导航把 zoom 作废。只认守卫的 refused 文案、
// 只认全屏坐标（on=screen）；参数错误（坏 cell、未知 eid/part）和 zoom 图内
// 坐标（on=zoom）不走这里，模型须先修正参数或手动重拍。
if (kind != "tap" || on != "screen" || e.message?.startsWith("refused:") != true) throw e
val zoomCell = cell ?: if (action.has("x") && action.has("y")) cellFromNorm(action.optInt("x", 500), action.optInt("y", 500)) else null
if (zoomCell == null || zoomCell.any { it.isLowerCase() }) throw e
val zoomed = runCatching { controller.zoomAim(zoomCell, part) }.getOrNull() ?: throw e
val png = zoomed.optString("png_base64")
val jpeg = if (png.isNotEmpty()) {
try {
val bytes = Base64.decode(png, Base64.DEFAULT)
val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
val j = bmp?.let { GridOverlay.toJpegBase64(it) }
bmp?.recycle()
j
} catch (_: Exception) {
null
}
} else {
null
}
// flow 方向的落地（2026-11-05）：zoom 图已到手，目标就在图中央（十字准星处）。
// 直接在 zoom 图中央落点，不用再问模型——autoglm-phone 的 do() 格式
// 表达不出 on="zoom"，等它重试只会再被拒一次，空转到 MAX_REPEATS。
// zoom→点在同一步内原子完成，中间没有模型往返，zoom 不会过期。
// 成了就当这一步成功；没成就回落到原有行为，把 zoom 图交给模型。
val autoTap = runCatching { controller.tapAim("zoom", 500, 500, null, null, handover = handover) }.getOrNull()
if (autoTap != null) {
val autoResult = markTimeoutUnknown("tap", autoTap, summarize("tap", autoTap))
if (autoResult.ok) {
return autoResult.copy(summary = "点按被拒后已自动 zoom 并点中目标（zoom 图中央）。${autoResult.summary}")
}
}
if (jpeg == null) throw e
return StepResult("点按被拒：${e.message}。已自动对同一目标重拍 zoom 图（见附图），请直接从这张图上用 on=\"zoom\" 点目标，不要再做 zoom、导航等中间动作。", false, null, jpeg, "TAP_REFUSED")
}
markTimeoutUnknown(kind, result, summarize(kind, result))
}
"swipe" -> {
val screens = if (action.has("screens")) {
action.optInt("screens").coerceIn(1, 10)
} else {
null
}
val result = controller.swipeNorm(
action.optInt("x1", 500), action.optInt("y1", 700),
action.optInt("x2", 500), action.optInt("y2", 300),
durationMs = 350, fling = false, screens = screens, handover = handover
)
markTimeoutUnknown(kind, result, summarize(kind, result))
}
"type" -> {
val text = action.optString("text")
if (text.isEmpty()) return StepResult("type 动作没有文字", false, null, null, "PARSE_ERROR")
// M6：type 也包 markTimeoutUnknown，超时标“结果未知”，别让模型原样重发一遍。
val rawType = controller.typeText(text, null, handover = handover)
markTimeoutUnknown(kind, rawType, summarize(kind, rawType))
}
"submit" -> {
// M6：与 type 一致，超时标“结果未知”（submit 已进 MUTATING_KINDS）。
val rawSubmit = controller.submitInput(null)
markTimeoutUnknown(kind, rawSubmit, summarize(kind, rawSubmit))
}
"zoom" -> {
val cell = action.optString("cell").ifEmpty {
cellFromNorm(action.optInt("x", 500), action.optInt("y", 500))
}
val result = controller.zoomAim(cell, action.optString("part").ifEmpty { null})
val r = summarize(kind, result)
val full = "已放大 ${result.optString("aimed_at")}。${result.optString("aim_check")}".trim()
// zoom 图是 PNG，转成 JPEG 与全屏截图同一格式再喂回模型
val png = result.optString("png_base64")
val jpeg = if (png.isNotEmpty()) {
try {
val bytes = Base64.decode(png, Base64.DEFAULT)
val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
val j = bmp?.let { GridOverlay.toJpegBase64(it)}
bmp?.recycle()
j
} catch (_: Exception) {
null
}
} else {
null
}
markTimeoutUnknown(
kind, result, StepResult(
if (full.length > 12) full else r.summary,
r.ok || jpeg!= null, r.screenChanged, jpeg, r.code
)
)
}
"back" -> summarize(kind, controller.globalAction("back"))
"home" -> summarize(kind, controller.globalAction("home"))
"openApp" -> {
val app = action.optString("app")
if (app.isEmpty()) return StepResult("openApp 没有应用名", false, null, null, "PARSE_ERROR")
// 跨应用任务循环：前台拉起，直接全屏（jk-v66：不要小窗；反射已死、拖展开靠不住）。
// launchApp 里已等目标窗口出现（最多 10 秒），这里不再睡、不再展开。
val r = LocalCommands.launchApp(ctx, app, fullscreen = true)
if (!r.startsWith("正在打开")) {
return StepResult(r, false, null, null, "UNKNOWN")
}
// S1：光听“正在打开”不够——launchApp 超时也这么报。再复核一次前台包名
// 必须真是目标应用才算成功；目标包名查不到时无法复核，沿用原判定。
// 前台刚切过来可能有一瞬窗口未稳，最多等 1.5 秒（3 次 × 500ms）再判。
val targetPkg = LocalCommands.packageForApp(ctx, app)
if (targetPkg != null) {
var fgPkg = ""
var matched = false
for (i in 0 until 3) {
fgPkg = try {
JarvisAccessibilityService.instance?.foregroundPackage().orEmpty()
} catch (_: Exception) {
""
}
if (fgPkg == targetPkg) { matched = true; break }
if (i < 2) {
try { Thread.sleep(500) } catch (_: InterruptedException) {
Thread.currentThread().interrupt()
break
}
}
}
if (!matched) {
val detail = "打开 $app 后前台不是目标应用（目标 $targetPkg，当前前台 ${fgPkg.ifEmpty { "未知" }}），launchApp 回报：$r"
AuditLog.append(ctx, "任务循环", "openApp 前台复核失败", detail)
return StepResult(detail, false, null, null, "UNKNOWN")
}
}
StepResult(r, true, null, null)
}
"browse" -> {
// 内置浏览器开网页：不用切 Chrome。URL 缺 http(s) 前缀时补 https。
// M7：不再无条件 ok=true——按实际结果判：浏览器没起来（isOpen false）
// 就是失败；起来了但 3 秒内没拿到页面快照，也如实说明。
val url = action.optString("url")
if (url.isEmpty()) return StepResult("browse 没有网址", false, null, null, "PARSE_ERROR")
val msg = BrowserController.open(ctx, url)
Thread.sleep(1500) // 等浏览器起来、页面开始加载
BrowserController.refreshSnapshot(3000)
val snap = BrowserController.lastSnapshot()
if (!BrowserController.isOpen()) return StepResult("$msg，但内置浏览器没能打开", false, null, null, "UNKNOWN")
val snapNote = if (snap != null) {
"（当前页：${snap.url}${if (snap.title.isNotEmpty()) "「${snap.title}」" else ""}）"
} else {
"（页面快照还没拿到，可能还在加载）"
}
StepResult("$msg$snapNote", true, null, null)
}
"browserBack" -> {
val ok = BrowserController.goBack()
StepResult(if (ok) "浏览器已后退" else "浏览器没得退（已在第一页或没开着）", ok, null, null, if (ok) "" else "UNKNOWN")
}
"browserTap" -> {
val index = action.optInt("index", -1)
if (index < 0) return StepResult("browserTap 没有链接编号", false, null, null, "PARSE_ERROR")
val msg = BrowserController.clickLink(index)
StepResult(msg, !msg.startsWith("没有") && !msg.startsWith("内置浏览器没开"), null, null, if (msg.startsWith("已点链接")) "" else "UNKNOWN")
}
"wait" -> {
val ms = action.optLong("ms", 1200).coerceIn(300, 20000)
Thread.sleep(ms)
StepResult("已等待 ${ms}ms", true, null, null)
}
"wait_idle" -> {
// 等界面稳定：连续两次读到的哈希相同才算稳。等到了、等满没稳、读不到树，
// 都如实回报——等待本身没失败，成败看摘要里那句。
val ms = action.optLong("ms", ScreenWait.WAIT_DEFAULT_MS).coerceIn(300, ScreenWait.WAIT_MAX_MS)
val s = ScreenWait.waitForSettle(controller, ms, "idle")
val summary = if (s.readFailed) {
"等界面稳定：读界面失败（等了 ${s.elapsedMs}ms）"
} else if (s.settled) {
"界面已稳定（等了 ${s.elapsedMs}ms）"
} else {
"等了 ${s.elapsedMs}ms，界面还没稳"
}
StepResult(summary, true, null, null)
}
"wait_change" -> {
// 等界面变化：哈希跟现在比不一样了才算变。变了、等满没变、读不到树，都如实回报。
val ms = action.optLong("ms", ScreenWait.WAIT_DEFAULT_MS).coerceIn(300, ScreenWait.WAIT_MAX_MS)
val s = ScreenWait.waitForSettle(controller, ms, "change")
val summary = if (s.readFailed) {
"等界面变化：读界面失败（等了 ${s.elapsedMs}ms）"
} else if (s.changed) {
"界面已变化（等了 ${s.elapsedMs}ms）"
} else {
"等了 ${s.elapsedMs}ms，界面没变"
}
StepResult(summary, true, null, null)
}
"scroll_to" -> {
// 在当前列表里滑动找目标：找到就停；滑到底（连着两次滑完界面都不动）
// 就停下来报没找到；滑满次数还没找到也报没找到。不盲滑过头。
val text = action.optString("text").ifEmpty { null }
val eid = action.optString("eid").ifEmpty { null }
if (text == null && eid == null) {
return StepResult("scroll_to 缺少 text 或 eid", false, null, null, "PARSE_ERROR")
}
val direction = action.optString("direction").ifEmpty { "down" }
val maxSwipes = action.optInt("maxSwipes", ScreenWait.SCROLL_DEFAULT_MAX)
val target = text ?: eid!!
val s = ScreenWait.scrollTo(controller, text, eid, direction, maxSwipes)
val summary = when {
s.found -> "已找到「$target」"
s.endReached -> "滑到底了，没找到「$target」"
else -> "滑了 ${s.swipes} 次，没找到「$target」"
}
StepResult(summary, s.found, null, null, if (s.found) "" else "ELEMENT_NOT_FOUND")
}
"tap_sel" -> {
// 按选择器点第 N 个匹配（流里的 tap_sel 步；单步更常用 tap 加 sel）。
// 找不到或 index 超出，tapBySelector 直接抛错，外层 catch 收成一句。
val sel = action.optJSONObject("sel")
?: return StepResult("tap_sel 缺少 sel", false, null, null, "PARSE_ERROR")
val index = action.optInt("index", 0)
val raw = controller.tapBySelector(sel, index, handover = handover)
markTimeoutUnknown(kind, raw, summarize(kind, raw))
}
"find" -> {
// 按多条件选择器找元素：回前 10 个的稳定编号、类型、文字和中心坐标，
// 外加总数，模型拿到编号就能接着点；一个都找不到直说，不假装有。
val sel = action.optJSONObject("sel")
?: return StepResult("find 缺少 sel", false, null, null, "PARSE_ERROR")
val r = controller.findBySelector(sel)
val total = r.optInt("total", 0)
if (total == 0) return StepResult("没找到符合条件的元素", false, null, null, "ELEMENT_NOT_FOUND")
val matches = r.optJSONArray("matches") ?: JSONArray()
val lines = (0 until minOf(matches.length(), 10)).mapNotNull { i ->
val m = matches.optJSONObject(i) ?: return@mapNotNull null
"${m.optString("sid")}|${m.optString("type")}|${m.optString("text")}|[${m.optInt("cx")},${m.optInt("cy")}]"
}
StepResult("找到 $total 个：\n" + lines.joinToString("\n"), true, null, null)
}
"flow" -> {
// 本地流：一段有界脚本由手机在本地一口气跑完，中间不再问模型；
// 跑完把每步 trace 压成一句摘要回喂历史，模型据此决定下一步。
// 流本身算一步（不额外占 25 步额度），成败按流的整体 ok 判定。
// 流的 outcomeUnknown 原样透传：流里变异步超时，单步一样标未知。
// 第五批·失败即停：流中途停下时整体 ok=false，TaskLoop 侧按一步失败计
// （复用 consecutiveFailures，不按流内步数重复计）；结果未知时 ok=false
// 但 outcomeUnknown=true，失败计数免计、走 Reflector 核验（与单步一致）。
// 中途停下与跑完的区分在 summarizeFlow 的摘要文字里（停在哪一步、为什么、
// 跳过了几步），不要只看 ok。
val result = FlowRuntime.run(ctx, controller, action)
val flowOk = result.optBoolean("ok", false)
val flowUnknown = result.optBoolean("outcomeUnknown", false)
val flowCode = when {
flowUnknown && result.optBoolean("timedOut", false) -> "TIMEOUT"
flowUnknown -> "UNKNOWN"
flowOk -> ""
else -> "UNKNOWN"
}
StepResult(summarizeFlow(result), flowOk, null, null, flowCode, flowUnknown)
}
else -> StepResult("未知动作：$kind", false, null, null, "PARSE_ERROR")
}
} catch (e: InterruptedException) {
throw e
} catch (e: Exception) {
val msg = e.message.orEmpty()
val code = errorCodeFor(msg)
val unknown = code == "TIMEOUT" && kind in MUTATING_KINDS
StepResult("执行 $kind 出错：$msg", false, null, null, code, unknown)
}
}

/**
* 本地流的结果压成一句给模型看的摘要：整体成败、完成几步、耗时、
* 每步的动作与状态（✓ 成、✗ 败、跳过），失败的带一句原因。
* 第五批·失败即停：四种结局必须让模型一眼分清——
* (a) 跑完且全成；(b) 中途停下（某步失败/结果未知/超时），剩余步骤一次没跑；
* (c) 跑完全部步骤，但中间有一步失败过（只有 onError=continue 显式放行才有
* 这种结局）；(d) 跑完全部步骤，但收尾时超时（最后一步跑完才发现时限已到）。
* 尤其 (b) 要写明停在哪一步、为什么、跳过了几步，别让模型以为流程跑完了；
* (b) 的下一轮按失败原因纠正（换瞄准方式、重读界面、或拆成单步做），不要
* 原样重发整个流。摘要本身要短，历史段还会再截断一次。
*/
private fun summarizeFlow(result: JSONObject): String {
val total = result.optInt("totalSteps", 0)
val done = result.optInt("completedSteps", 0)
val elapsed = result.optLong("elapsedMs", 0)
val ok = result.optBoolean("ok", false)
val aborted = result.optBoolean("aborted", false)
val failedStep = result.optInt("failedStep", -1)
val skipped = result.optInt("skippedSteps", 0)
val abortReason = result.optString("abortReason")
val sb = StringBuilder()
when {
ok -> sb.append("本地流完成")
aborted -> {
sb.append("本地流中途失败停下")
if (failedStep >= 0) sb.append("：第 ${failedStep + 1} 步失败")
if (abortReason.isNotEmpty()) sb.append("（${abortReason.take(60)}）")
if (skipped > 0) sb.append("，剩余 ${skipped} 步没跑（已跳过），不要以为流程跑完了")
sb.append("。下一轮按失败原因纠正，不要原样重发整个流")
}
failedStep >= 0 -> sb.append("本地流跑完全部步骤，但中间第 ${failedStep + 1} 步失败过")
result.optBoolean("timedOut", false) -> sb.append("本地流跑完全部步骤，但收尾时超时了")
else -> sb.append("本地流未完成")
}
sb.append("：完成 $done/$total 步，耗时 ${elapsed}ms")
if (result.optBoolean("timedOut", false)) sb.append("（超时）")
if (result.optBoolean("outcomeUnknown", false)) sb.append("（有一步结果未知，别当它成功了）")
val steps = result.optJSONArray("steps")
if (steps != null) {
val parts = mutableListOf<String>()
for (i in 0 until steps.length()) {
val t = steps.optJSONObject(i) ?: continue
val a = t.optString("action")
val mark = when (t.optString("status")) {
"ok" -> "✓"
"skipped" -> "跳过"
else -> "✗"
}
var s = "$a$mark"
val err = t.optString("error")
if (err.isNotEmpty()) s += "（${err.take(20)}）"
parts += s
}
if (parts.isNotEmpty()) sb.append("：").append(parts.joinToString("，"))
}
return sb.toString()
}

/** 0–1000 相对坐标 → 全屏瞄准网格的 cell 标签（8 列 A–H × 12 行），zoom 用。 */
private fun cellFromNorm(x: Int, y: Int): String {
val col = (x.coerceIn(0, 999) * 8 / 1000).coerceIn(0, 7)
val row = (y.coerceIn(0, 999) * 12 / 1000).coerceIn(0, 11) + 1
return "${'A' + col}$row"
}

/**
 * 单步失败的错误码（回喂模型按码换招用）。分类只认控制层实际抛出的文案，
 * 认不出的统一 UNKNOWN，不硬编：
 * - refused: 开头的守卫拒绝（树被扣留的应用上盲点）→ TAP_REFUSED
 * - 坐标契约拒绝：开头的尺寸校验拒绝（尺寸未知/已变/换算后出屏）→ COORD_CONTRACT
 * - 目标被遮住：开头的遮挡拒绝（目标被别的元素完全盖住，点原位置只会点到遮挡物）→ TAP_BLOCKED
 * - no input focus → NO_INPUT_FOCUS；no active window → NO_ACTIVE_WINDOW
 * - 只找到 N 个 / no node with id / bad element id → ELEMENT_NOT_FOUND
 * - 找不到元素（三重回退也没找到、多个同文字分不清）→ ELEMENT_NOT_FOUND
 * - 含 timeout（如 screenshot timeout、手势超时）→ TIMEOUT
 */
private fun errorCodeFor(message: String): String {
return when {
message.startsWith("坐标契约拒绝") -> "COORD_CONTRACT"
message.startsWith("目标被遮住") -> "TAP_BLOCKED"
message.startsWith("refused:") -> "TAP_REFUSED"
message.contains("no input focus") -> "NO_INPUT_FOCUS"
message.contains("no active window") -> "NO_ACTIVE_WINDOW"
message.contains("只找到") || message.contains("no node with id") ||
message.contains("bad element id") || message.contains("is no longer on screen") ||
message.contains("多个同文字") || message.contains("三重回退") -> "ELEMENT_NOT_FOUND"
message.contains("timeout", ignoreCase = true) -> "TIMEOUT"
else -> "UNKNOWN"
}
}

/** 超时后“不知道成没成”的动作种类：只有变异动作才标 outcomeUnknown。 */
private val MUTATING_KINDS = setOf("tap", "longPress", "type", "submit", "swipe", "zoom", "tap_sel", "scroll_to")

/**
 * 手势超时判未知：控制层的手势结果带 completed=false，意思是手势发出去了、
 * 但超时没收到完成回执；错误文案本身含 timeout 也一样。变异动作这时绝不能
 * 当成功，也不能当没做过——标 TIMEOUT + outcomeUnknown，让模型先观察核对
 * 再决定，不要直接重做。raw 里没有 completed 字段、文案也不含 timeout 时
 * 原样返回（performAction 类动作没有这个信号）。
 */
private fun markTimeoutUnknown(kind: String, raw: JSONObject, base: StepResult): StepResult {
if (kind !in MUTATING_KINDS) return base
if (base.outcomeUnknown) return base
val noReceipt = raw.has("completed") && !raw.optBoolean("completed", true)
if (!noReceipt && base.code != "TIMEOUT") return base
val summary = if (base.summary.startsWith("未执行")) {
"手势已发出，但超时没收到完成回执"
} else {
base.summary
}
return base.copy(
ok = false,
code = "TIMEOUT",
outcomeUnknown = true,
summary = "$summary（结果未知：不知道成没成）"
)
}

/**
* 把 ScreenController 的结果 JSON 收成给模型看的摘要：尽量透传它自己的
* 判定（performed / screen_changed / now_pkg / elements_hint / snapped /
* 输入读回），让模型从结果本身就知道界面变成了什么样，而不是再猜。
* 失败时按错误文案附一个错误码（见 errorCodeFor），成功时 code 为空。
*/
private fun summarize(kind: String, result: JSONObject): StepResult {
// 跨应用检查 2026-11-12 断点 #1：手势路径只返回 completed，从不写 performed，
// 这里直接读 performed 会把每次点按都判成“未执行”。performed 有就用它，
// 没有就以 completed 为准（ScreenController 的约定：调用方按 completed 判成败）。
val performed = result.optBoolean("performed", result.optBoolean("completed", false))
val error = result.optString("error")
val changed: Boolean? = if (result.has("screen_changed")) {
result.optBoolean("screen_changed")
} else {
null
}
if (error.isNotEmpty()) return StepResult(error, false, changed, null, errorCodeFor(error))
val parts = mutableListOf<String>()
parts += if (performed) "已执行" else "未执行（无说明）"
if (result.has("now_pkg")) parts += "现在在 ${result.optString("now_pkg")}"
if (changed!= null) parts += if (changed) "界面有变化" else "界面没变化"
val snappedFrom = result.optString("snapped_from")
if (snappedFrom.isNotEmpty()) {
parts += "落点被吸附：从 $snappedFrom 吸到 ${result.optString("snapped_to")}"
}
val divertedBy = result.optString("diverted_by")
if (divertedBy.isNotEmpty()) {
parts += "落点被遮挡改道：原中心被「${divertedBy}」盖住，已改点未遮挡处"
}
// 第十一批·定位回退：走了回退的成功也要明说——旧编号/旧选择器已过期，这次
// 是按 dump 时记下的位置或文字找回的，下轮只用最新紧凑表里的编号。
if (result.optBoolean("fallback", false)) {
val how = if (result.optString("via") == "text") "文字" else "位置"
parts += "定位回退：编号已过期，这次是按 dump 时记下的${how}在活树里找回的，下轮只用最新紧凑表里的编号，不要再用旧编号"
}
val text = result.optString("text")
if (text.isNotEmpty()) parts += "框内文字：${text.take(40)}"
val eh = result.optString("elements_hint")
if (eh.isNotEmpty()) parts += eh.take(120)
return StepResult(parts.joinToString("，"), performed, changed, null)
}

private const val SYSTEM_PROMPT =
"你是 Jarvis 的跨应用任务执行器，在用户的安卓手机上一步一步完成任务。" +
"每一轮你会看到：任务原文、当前前台应用、界面元素紧凑表（只列可操作元素，带稳定编号）、已执行步骤和上一步的结果。树不够用或上一步有问题时还会附一张截图（全屏图带 A1–H12 网格标定；若提示词说明来自录屏通道，则那张图没有网格、只能看不能按图瞄准；没有附图时只按紧凑表操作）。\n" +
"紧凑表是默认且最准的观察：每行格式「稳定编号|类型|文字|[中心x,中心y]」，坐标是系统算好的 0-1000 相对值；稳定编号（s 开头，如 s3f9a2）跨轮不变，同一元素每轮同一个编号，点它就用这个编号；列表型界面（聊天列表、动态流、搜索结果）里同一形状会重复出现，要认准目标文字所在的那一行，只点那一行的编号，绝不把一行的名字配上另一行的按钮。" +
"文字为 # 开头的是控件的内部 id，不是屏幕上印的字，只能用来区分控件，不许当可见文字。" +
"有编号就用编号点（tap 的 eid，优先用紧凑表的稳定编号），绝不拿 [x,y] 或截图位置重算坐标去点一个有编号的元素。" +
"若某步结果说编号已过期、是按位置或文字找回的，那个编号就作废了，下轮只用最新紧凑表里的编号，不要再用旧编号。" +
"没文字没说明的图标按钮靠类型和中心坐标认领（比如某条动态下方的评论图标就是那条动态的），也可以先 zoom 看清再点。" +
"稳定编号只对当前这一屏态有效：元素还在同一位置时每轮同一个编号；滚动或跳页面后编号会变，每轮只用最新紧凑表里的编号。e 数字编号（e3）只对当前这一轮的大纲有效，下一轮就作废了，优先用稳定编号。\n" +
"每轮回复先是计划（见下），再是下一步动作（一个 JSON 对象）；需要记关键信息时在计划后加一段“笔记：”（见下），没有可记的可以不写。除计划、笔记和动作外不要输出其他文字、不要用代码块包裹。" +
"计划（任务开始时列出，之后每轮更新；最多 10 条，每条一句话）：\n" +
"计划：\n" +
"1. 打开微信\n" +
"2. 找到联系人\n" +
"3. 输入消息并发送\n" +
"已完成的子目标在行首标 ✓（如“1. ✓ 打开微信”）；没做完的照常列；卡住时修订计划（重排、改写、插入纠正步骤、换策略），修订后的计划在回复里一并给出。没有计划可列时可以不写计划，只输出下一步动作。" +
"笔记（与“计划：”段并列，记跨步关键信息——账号、位置、已确认的事实；最多 10 条，每条一句话）：\n" +
"笔记：\n" +
"- 账号尾号 1393\n" +
"- 收款人在支付宝通讯录第 3 个\n" +
"每条一行以 - 开头；需要更新笔记时重写整个“笔记：”段（以新段为准全量替换旧笔记），没新内容时不用重写，旧笔记会一直保留；没有可记的可以不写笔记。" +
"{\"action\":\"tap\",\"eid\":\"s3f9a2\"} 点紧凑表里的稳定编号（最优先、最稳；e 数字编号也认，但只对当轮有效）；" +
"或 {\"action\":\"tap\",\"x\":540,\"y\":700} 按 0-1000 相对坐标点你刚看到的全屏截图；坐标只对本轮看到的那张屏有效，屏幕转了向、尺寸变了或还没读到过界面时坐标动作会被直接拒绝并告诉你改走编号 eid，不要重发同一坐标。" +
"或 {\"action\":\"tap\",\"cell\":\"D7\",\"part\":\"tr\"} 按截图上的网格格号点（part 是格内九宫格部位，可省略）。" +
"若这一步是在已跳到另一应用的界面上点的（点开分享面板/外部链接/权限弹窗之后，前台已不是原应用），加 \"handover\":true，否则前台校验会拦住；流里的交接步同样加这个字段。" +
"{\"action\":\"longPress\",\"eid\":\"s3f9a2\"} 长按稳定编号，可加 \"ms\":800。" +
"{\"action\":\"find\",\"sel\":{\"textContains\":\"发送\"}} 按条件找元素（text/textContains/description/descriptionContains/viewId/className/packageName/clickable/editable/enabled/visible 可组合，都满足才算中；默认不区分大小写），回前 10 个的稳定编号、类型、文字和中心坐标，外加总数；一个都找不到会直说。" +
"{\"action\":\"tap\",\"sel\":{\"textContains\":\"发送\"},\"index\":0} 按选择器点第 N 个匹配（index 缺省 0；只找到 N 个、要第 M 个不存在会直说）；点之前会先爬到最近的可点父节点。选择器是兜底：有稳定编号的元素优先用 eid 点，编号没有或要在一堆同名元素里挑时才用 sel。" +
"{\"action\":\"swipe\",\"x1\":500,\"y1\":800,\"x2\":500,\"y2\":300} 滑动，坐标都是 0-1000 相对值，手指移动多远列表就走多远；要连滑多屏加 \"screens\":3（系统会回报实际滑了几屏），不要用一串相同的小滑动去凑。" +
"{\"action\":\"type\",\"text\":\"要输入的文字\"} 往当前已聚焦的输入框输入（系统依次试输入法、直接填字、剪贴板粘贴三档，不用你换方式；输完读回不对会如实报，别原样重发 type，先 zoom 核对或换路线）。" +
"{\"action\":\"submit\"} 提交当前输入框（如搜索、发送）。" +
"{\"action\":\"back\"} 返回；{\"action\":\"home\"} 回桌面；{\"action\":\"wait\",\"ms\":1500} 等待（每次等待要比上一次久，比如 1500→3000→5000→10000，绝不要连续发相同的等待时长，否则会被重复守卫拦停）；{\"action\":\"openApp\",\"app\":\"微信\"} 打开应用。" +
"内置浏览器（不用切 Chrome，登录态由 WebView 自己存）：{\"action\":\"browse\",\"url\":\"https://example.com\"} 在内置浏览器打开网址；打开后每轮你会看到当前页的地址、标题、正文和链接列表（编号|文字）。{\"action\":\"browserTap\",\"index\":2} 点页面里第 index 个链接。{\"action\":\"browserBack\"} 浏览器里后退一步。网页任务优先用内置浏览器，不要去开 Chrome；登录、验证码挡住了就 fail 说明、不要硬闯。" +
"{\"action\":\"wait_idle\",\"ms\":5000} 等界面稳定下来（连续两次读到的界面一模一样才算稳），稳了就回；等满 ms 还没稳也回，告诉你没稳。{\"action\":\"wait_change\",\"ms\":5000} 等界面发生变化（跟现在比不一样了才算变），变了就回；等满 ms 没变也回，告诉你没变。这两个是按变化等的，不用固定等几秒；连着等时 ms 换个数字，不然会被判重复。" +
"{\"action\":\"scroll_to\",\"text\":\"目标文字\"} 在当前列表里向下滑动找目标，找到就停；滑到底（连着两次滑完界面都不动）就停下来告诉你没找到；也可以加 \"direction\":\"up\" 往上滑，或 \"maxSwipes\":8 限制最多滑几次；也可以用 \"eid\":\"s3f9a2\" 按稳定编号找。" +
"{\"action\":\"zoom\",\"cell\":\"D7\"} 放大某个网格格看清小字小图标（也可以给 x/y）；下一轮你会看到放大图，" +
"若要按放大图点，动作里加 \"on\":\"zoom\"，坐标和格号都按放大图自身的 0-1000 与小写格号读。" +
"{\"action\":\"flow\",\"steps\":[{\"action\":\"tap\",\"eid\":\"e3\"},{\"action\":\"type\",\"text\":\"你好\"},{\"action\":\"tap\",\"eid\":\"e5\"}]} 本地流：一段确定性的多步操作由手机在本地一口气跑完、中间不再问你，跑完一次把每步结果交回来，算一步、不额外占 25 步的额度。适合同一屏上的固定套路（比如点输入框→输文字→点发送按钮，或点一下→等界面变→再点一下），最多 15 步、总共最多 20 秒（可加 \"timeoutMs\"，但不许超过 20000）。流里的每一步用和单步动作一样的字段（tap/longPress 用 eid/cell/x/y，eid 优先用紧凑表的稳定编号、e 数字编号也认，type 用 text，swipe 用 x1/y1/x2/y2，back/home/zoom 照旧）；另外还有七种流内专用步：{\"action\":\"wait\",\"ms\":3000} 等到界面变化或等满时长，{\"action\":\"wait_idle\",\"ms\":5000} 等到界面稳定，{\"action\":\"wait_change\",\"ms\":5000} 等到界面变化，{\"action\":\"find\",\"text\":\"发送\"} 只检查元素在不在、不动手（也可以给 \"sel\":{...} 按选择器查），{\"action\":\"observe\"} 重新读一遍界面，{\"action\":\"scroll_to\",\"text\":\"目标文字\"} 在列表里滑动找目标（可加 \"direction\":\"up\"、\"maxSwipes\":8，滑到底没找到就停），{\"action\":\"tap_sel\",\"sel\":{\"textContains\":\"发送\"}} 按选择器点第 N 个匹配（可加 \"index\":1）。每一步可加 \"ifPresent\":{\"text\":\"…\"}（元素不在就跳过这步）或 \"ifAbsent\"（元素在就跳过），以及 \"onError\":\"continue\"（这步失败也继续往下跑，缺省是失败就停）。流里某步失败或结果未知会立刻停下，剩余步骤不跑，结果会告诉你停在哪一步、为什么、跳过了几步——不要以为流程跑完了，也不要原样重发整个流，先按失败原因纠正（换瞄准方式、重读界面、或拆成单步做）；守卫跳过的步不算失败，流会继续往下跑。注意：稳定编号只对当前这一屏态有效，流里如果会跳到新页面（打开新应用、进新页面），编号就作废了——这种流要尽量短，或者拆成单步做；e 数字编号只对当轮有效，流里尽量用稳定编号；流里也可以 zoom，但放大图不会中途给你看，只能按你已经知道的格号点。" +
"{\"action\":\"done\",\"message\":\"完成情况一句话\"} 任务真正完成时用。" +
"{\"action\":\"fail\",\"reason\":\"卡在哪一步、什么原因\"} 做不下去时用，禁止假装完成。\n" +
"做决定前先读已执行步骤，找重复失败和计划与现实的矛盾。硬规则：绝不重复失败的动作——先搞清它为什么失败，再换一种做法；只凭屏幕上观测到的证据判断子目标完成：紧凑表或截图里确实出现了目标内容才算完成，绝不凭感觉提前标完成；数据类任务（金额、账号、验证码、数量、日期）原文照抄，不改格式、不加标点。" +
"一步只做一个动作。" +
"输入文字：先用编号点输入框，下一个动作必须紧接着就是 type，不许在点框和输入之间插别的动作，也不许重复点同一个输入框；如果上一步的点击已经把回复框/评论框带起来并聚焦（结果会说明），直接 type，不要再点一次输入条（树被扣留的应用里输入条很薄，点它容易误点到下面的内容）。" +
"开屏广告、会员弹窗、更新提示、权限请求这类挡路的弹窗，先处理掉再继续任务。" +
"上一步结果说「界面没变化」就是大概率没点中：不要把同一个动作原样再发一遍（连着重复同一个动作会被判定为原地打转、任务直接中止），换瞄准方式（先 zoom 核对、改用编号、或先滑动把目标挪到清楚的位置）。" +
"上一步的问题会带一个错误码：ELEMENT_NOT_FOUND（要找的元素不存在）、TAP_REFUSED（系统拒绝了这次点按，先 zoom 再点）、TAP_BLOCKED（目标被别的元素完全遮住，先把遮挡物处理掉——关弹窗、收起悬浮窗、滑开遮挡——再重新读屏点目标，不要原样重发）、COORD_CONTRACT（坐标契约拒绝：屏幕尺寸未知或已变，坐标不可信，改走编号 eid 点目标，不要重发同一坐标）、TIMEOUT（动作超时，不知道成没成）、NO_INPUT_FOCUS（没有输入框获得焦点，先点输入框）、NO_ACTIVE_WINDOW（当前没有可操作的窗口）、PARSE_ERROR（你发的动作缺字段或格式不对）、UNKNOWN（其他原因）。按码换招，别对着同一个码重复同一个动作。" +
"上一步标「结果未知」时，那个动作可能动了也可能没动——不要直接重做同样的动作，先看紧凑表和截图确认成没成，再决定下一步。" +
"屏幕下方有个「停止」按钮，用户随时可以点它停下任务；任务可能因此提前结束，不用你处理。" +
"wait 只在页面真的在加载时用；需要连着等时，每次必须比上一次等得更久（如 1500→3000→6000），发相同毫秒数的 wait 同样会被判打转；等到了上限界面还是一动不动，那不是在加载，是卡住了或被弹窗/登录挡住了，换路线或 fail 说明。" +
"有些应用不给元素大纲（只有截图或干脆是空的）：节点总数为 0 说明这一屏是自绘的（小程序、网页、游戏画布），优先用 back/home 退出换路线，不要盲点坐标；节点有数但带名的为 0 时，大纲的分组和中心还在，拿截图和大纲的缩进、坐标对位，用编号点，别降级成坐标点。" +
"树被扣留的应用（如微信）先 zoom 再点是硬规矩：没经过新放大的盲点会被系统拒绝，一个界面一次放大。" +
"发消息这类任务，要依次完成：打开应用、找到联系人、点开会话、输入消息、点发送，发送完成才能 done；" +
"绝不输入密码、验证码；绝不点付款、转账、删除类按钮，除非任务原文明确要求。"
}
