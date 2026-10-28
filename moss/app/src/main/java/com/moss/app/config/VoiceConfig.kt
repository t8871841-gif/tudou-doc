package com.moss.app.config

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.moss.app.BuildConfig
import java.util.UUID


/**
 * App configuration, read straight off SharedPreferences by the companion
 * functions — there is no snapshot object to go stale. Voice is Edge-only
 * (Edge neural voices for speaking, the system recogniser for hearing) and
 * takes no configuration, so the voice fields that used to live here are
 * gone with the removed providers.
 */
class VoiceConfig {
    companion object {


        private const val PREFS = "voice_prefs"


        /**
         * Where the agent loop runs. Deliberately an explicit setting rather
         * than "hub_url is blank ⇒ go local": [save] drops blank values, so a
         * URL that has ever been saved cannot be cleared from the UI, and
         * inferring the mode from it would leave the user with no way back.
         *
         * [BRAIN_CODEFLYING] is a hosted backend that proxies the LLM
         * from one domain. In this mode the LLM endpoint and credential
         * are derived from `BuildConfig.CODEFLYING_*` and the user does not
         * fill in any LLM fields; voice is Edge on-device in every mode.
         */
        const val BRAIN_HUB = "hub"
        const val BRAIN_LOCAL = "local"
        const val BRAIN_CODEFLYING = "codeflying"

        /** Which engine hears: the on-device sherpa model, or the system recognition service. */
        const val ASR_OFFLINE = "offline"
        const val ASR_SYSTEM = "system"

        /**
         * The out-of-box default for a blank 本机 tab: Jarvis's setup — the
         * Agnes endpoint and `agnes-3.0-flash`, the same key and model
         * Jarvis itself uses. The rows stay freely editable: typing any
         * base URL / model / key (DeepSeek, OpenRouter, OpenAI, …)
         * overrides this, and a saved value is never rewritten by a
         * default change.
         */
        const val DEFAULT_LLM_BASE_URL = "https://apihub.agnes-ai.com/v1"
        const val DEFAULT_LLM_MODEL = "agnes-3.0-flash"
        const val DEFAULT_REASONING_EFFORT = "high"

        /**
         * The same two defaults for an **English** card. They match the
         * Chinese card: both default to Jarvis's setup (Agnes), so there is
         * one default, not one per language — the split used to point the
         * English card at OpenAI and the Chinese card at DeepSeek.
         *
         * The 本机 tab's hint *is* the value a blank row resolves to, so the two
         * cannot be allowed to disagree: a card that advertises one `Model` /
         * `Base URL` and then, when the user leaves the boxes empty, quietly
         * calls another would be lying in the one place a user reads to decide
         * what to type. [llmBaseUrlDefault] / [llmModelDefault] return the
         * Agnes defaults, and both the hint and the resolved config go
         * through them.
         *
         * **CodeFlying is deliberately not switched by the defaults above.**
         * Its model is whatever the hosted backend serves; pointing it at
         * `agnes-3.0-flash` would name a model CodeFlying does not have, so
         * its fallback stays [DEFAULT_CODEFLYING_MODEL]. See [brainConfig].
         */
        const val DEFAULT_LLM_BASE_URL_EN = "https://apihub.agnes-ai.com/v1"
        const val DEFAULT_LLM_MODEL_EN = "agnes-3.0-flash"

        /**
         * The model the hosted CodeFlying backend serves, used only as the
         * fallback when no model was ever saved. Kept separate from
         * [DEFAULT_LLM_MODEL] so changing the out-of-box default cannot
         * misname the model at CodeFlying's endpoint.
         */
        const val DEFAULT_CODEFLYING_MODEL = "deepseek-flash"

        /** The Jarvis/Agnes default base URL, for either card. */
        fun llmBaseUrlDefault(context: Context): String =
            if (uiLanguage(context) == LANG_EN) DEFAULT_LLM_BASE_URL_EN else DEFAULT_LLM_BASE_URL

        /** The Jarvis/Agnes default model, for either card. */
        fun llmModelDefault(context: Context): String =
            if (uiLanguage(context) == LANG_EN) DEFAULT_LLM_MODEL_EN else DEFAULT_LLM_MODEL

        /**
         * CodeFlying is available iff the build was configured with ENABLED and
         * a non-empty DOMAIN. ENABLED alone with no DOMAIN silently falls back
         * to [BRAIN_LOCAL] — the picker hides the CodeFlying tab and the
         * default brain reads as local.
         */
        fun isCodeFlyingAvailable(): Boolean =
            BuildConfig.CODEFLYING_ENABLED && BuildConfig.CODEFLYING_DOMAIN.isNotBlank()

        /**
         * The CodeFlying API key in effect for this install.
         *
         * Read from `assets/andee_key.txt` first: production APKs are built once
         * from shared source (empty key) and the per-user `kft_` token is written
         * into that asset at package time, so a Gradle rebuild is not needed per
         * user. Dev builds that never had the file (or have a blank one) fall
         * back to [BuildConfig.CODEFLYING_KEY] from local.properties.
         */
        fun codeFlyingKey(context: Context): String =
            runCatching {
                context.assets.open("andee_key.txt").bufferedReader().use { it.readText().trim() }
            }.getOrDefault("").ifEmpty { BuildConfig.CODEFLYING_KEY }


        /** `https` / `http` for the LLM REST base URL. */
        private fun codeFlyingHttpScheme(): String =
            if (BuildConfig.CODEFLYING_SSL_ENABLED) "https" else "http"

        /** Bare host, no scheme and no trailing slash. One place, so the three URLs below cannot drift. */
        private fun codeFlyingHost(): String =
            BuildConfig.CODEFLYING_DOMAIN.trim().trimEnd('/')

        /** Bare CodeFlying origin, e.g. `http://124.71.176.202`. */
        fun codeFlyingBaseUrl(): String = "${codeFlyingHttpScheme()}://${codeFlyingHost()}"

        /**
         * Origin for the market tool endpoints, e.g. `http://host/baas-api`.
         *
         * `/baas-api/` exists because the bare `/api/` on that host is the
         * front-end's static directory — nginx only forwards that tree to baas
         * under this prefix. This is a separate accessor rather than folded
         * into [codeFlyingBaseUrl] so the market calls cannot drift onto the
         * LLM's path.
         *
         * The `/baas-api` segment is a deployment detail, like the `/voice/llm`
         * suffix above it — it belongs to whichever nginx the build points at.
         */
        fun codeFlyingMarketBaseUrl(): String = "${codeFlyingBaseUrl()}/baas-api"


        /** Full LLM REST base URL for CodeFlying, e.g. `https://foo.net/voice/llm`. */
        fun codeFlyingLlmBaseUrl(): String = "${codeFlyingBaseUrl()}/voice/llm"


        /**
         * Which palette the UI draws with. `true` = night, and false = day.
         *
         * **Day (light) is the default.** A fresh install comes up light, and so does
         * a device that has never touched the setting — which is why the test is
         * "is night" rather than "is not day". The dark palette is one tap away in
         * 设置 → 外观 and is remembered from then on.
         *
         * The inversion is on purpose and it is the whole content of this function.
         * The test is written against the *non-default* value rather than the default
         * one so that an explicit choice always wins and nothing else has to: only
         * the string the picker actually wrote can turn night on, so no rewrite of
         * the default can silently overrule a user who picked dark.
         *
         * Read straight off disk rather than out of [current], and that matters here:
         * [com.moss.app.ui.Theme] reads this once at process start and again on
         * every change, so the answer has to be the file's, not a snapshot of the
         * other rows that happens to be in memory.
         *
         * Only the two strings are meaningful; the "night" one is written by the
         * picker as [com.moss.app.ui.Theme.NIGHT].
         */
        fun nightTheme(context: Context): Boolean {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("theme", "").orEmpty()
            return nightFromSaved(saved)
        }

        /**
         * The rule [nightTheme] implements, separated so it can be tested without a
         * device — there is no Robolectric here, and the default this decides is not
         * observable until something is drawn.
         *
         * `internal` rather than private for that reason alone. A theme default is
         * exactly the kind of value that gets inverted by a later "make it dark again"
         * request, and the two things that must move together (this and `Theme.night`'s
         * initial value) are in different files.
         */
        internal fun nightFromSaved(saved: String): Boolean =
            // Only an explicit "night" turns night on; absent or unrecognised reads as
            // day, so the default cannot be lost by a build that never wrote it, and an
            // explicit choice can never be overruled by a change to the default.
            saved.trim().lowercase() == "night"

        /** Whether to show the 云端 (hub) tab in the backend picker. Default off. */
        fun showHub(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("show_hub", "")?.trim()?.lowercase() == "on"

        /**
         * Hub connection settings. Not part of the voice pipeline but stored
         * in the same SharedPreferences file to keep the settings UI simple.
         * Read via [hubConfig] instead of [load].
         */
        data class HubConfig(
            val url: String,           // empty = don't dial out
            val deviceId: String,      // stable, generated once
            val deviceName: String,    // human-friendly, editable
        )

        fun hubConfig(context: Context): HubConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var deviceId = p.getString("device_id", null)
            if (deviceId.isNullOrEmpty()) {
                deviceId = "body-${UUID.randomUUID().toString().take(8)}"
                p.edit().putString("device_id", deviceId).apply()
            }
            return HubConfig(
                url = p.getString("hub_url", "").orEmpty().trim(),
                deviceId = deviceId,
                deviceName = p.getString("device_name", "").orEmpty().ifEmpty { deviceId },
            )
        }

        /**
         * Which brain answers, and how to reach it if it is the local one.
         *
         * Read via [brainConfig]; like [hubConfig] this never throws and never
         * requires the user to have configured anything — an unconfigured
         * device reads as [BRAIN_LOCAL], which is the out-of-box default.
         */
        data class BrainConfig(
            /** [BRAIN_HUB], [BRAIN_LOCAL] or [BRAIN_CODEFLYING]. */
            val mode: String,
            val apiKey: String,        // empty = on-device brain cannot run
            val baseUrl: String,
            val model: String,
            val thinking: Boolean,
            val reasoningEffort: String,
        ) {
            /**
             * The agent loop runs on this device (local DeepSeek or CodeFlying
             * — both are "the brain is here", only the backend differs). Only
             * [BRAIN_HUB] is "run elsewhere, listen on a socket".
             */
            val isLocal: Boolean get() = mode == BRAIN_LOCAL || mode == BRAIN_CODEFLYING

            /**
             * Same backend, possibly asked differently. [mode], [apiKey] and
             * [baseUrl] say *who* answers; [model], [thinking] and
             * [reasoningEffort] only say how they are asked, and a running brain
             * can take those on the next request. A config change that is only
             * the second kind must not rebuild the brain, because rebuilding it
             * is what discards the conversation.
             */
            fun sameBackend(other: BrainConfig): Boolean =
                mode == other.mode && apiKey == other.apiKey && baseUrl == other.baseUrl
        }

        /**
         * Which recognition engine a turn uses. Offline is the default: the
         * system services on this device take the session and never answer,
         * so the default must be the engine that cannot be refused one.
         * Unknown saved values fail closed to offline, same rule as
         * [brainMode].
         */
        fun asrEngine(context: Context): String {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return when (p.getString("asr_engine", "").orEmpty().trim().lowercase()) {
                ASR_SYSTEM -> ASR_SYSTEM
                else -> ASR_OFFLINE
            }
        }

        /**
         * The brain mode in effect, with CodeFlying availability already
         * reconciled. Reads the saved `brain` pref; falls back to CodeFlying
         * if the build enables it and otherwise to local. Unknown saved values
         * fail closed to local — same rule as [groundingEnabled].
         */
        fun brainMode(context: Context): String {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return resolveBrainMode(p.getString("brain", ""))
        }

        /**
         * [raw] — a saved value, or one the user has just picked and **not yet
         * saved** — put through the rule above.
         *
         * Split out because the settings card has to ask about the second
         * kind. The brain picker does not write on tap, so when the user
         * selects 本机 out of CodeFlying the card is holding a value no pref
         * reflects yet, and asking [brainMode] there answers about the mode
         * being left — which is how the voice block came to stay hidden until
         * 保存 rebuilt the card.
         *
         * **One rule, one place.** A second copy of "is CodeFlying really in
         * play" is how a build that does not enable it ends up hiding the very
         * voice settings it is reading from.
         */
        fun resolveBrainMode(raw: String?): String {
            val saved = raw?.trim()?.lowercase().orEmpty()
            return when (saved) {
                BRAIN_HUB -> BRAIN_HUB
                BRAIN_LOCAL -> BRAIN_LOCAL
                BRAIN_CODEFLYING -> if (isCodeFlyingAvailable()) BRAIN_CODEFLYING else BRAIN_LOCAL
                "" -> if (isCodeFlyingAvailable()) BRAIN_CODEFLYING else BRAIN_LOCAL
                else -> BRAIN_LOCAL
            }
        }

        fun brainConfig(context: Context): BrainConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mode = brainMode(context)
            // CodeFlying derives everything from the build — the user does not
            // see or edit the endpoints. llm_model / thinking / effort are
            // deliberately *still* read from prefs so the same picker in
            // [SettingsUi] applies to both local and CodeFlying.
            if (mode == BRAIN_CODEFLYING) {
                return BrainConfig(
                    mode = BRAIN_CODEFLYING,
                    apiKey = codeFlyingKey(context),
                    baseUrl = codeFlyingLlmBaseUrl(),
                    model = p.getString("llm_model", DEFAULT_CODEFLYING_MODEL)
                        .orEmpty().trim().ifEmpty { DEFAULT_CODEFLYING_MODEL },
                    thinking = p.getString("llm_thinking", defaultThinking())
                        ?.trim()?.lowercase() != "disabled",
                    reasoningEffort = p.getString("llm_reasoning_effort", DEFAULT_REASONING_EFFORT)
                        .orEmpty().trim().ifEmpty { DEFAULT_REASONING_EFFORT },
                )
            }
            // Language-dependent defaults: the blank-row fallback must be the
            // value the 本机 tab's hint advertised, or an English card would
            // promise OpenAI in the placeholder and then call DeepSeek.
            val defaultBaseUrl = llmBaseUrlDefault(context)
            val defaultModel = llmModelDefault(context)
            return BrainConfig(
                mode = mode,
                apiKey = p.getString("llm_api_key", "").orEmpty().trim(),
                // trimEnd('/') because the client appends "/chat/completions"
                // and a trailing slash from the settings field would produce a
                // double slash, which some gateways 404 on.
                baseUrl = p.getString("llm_base_url", "")
                    .orEmpty().trim().trimEnd('/').ifEmpty { defaultBaseUrl },
                model = p.getString("llm_model", defaultModel)
                    .orEmpty().trim().ifEmpty { defaultModel },
                thinking = p.getString("llm_thinking", defaultThinking())
                    ?.trim()?.lowercase() != "disabled",
                reasoningEffort = p.getString("llm_reasoning_effort", DEFAULT_REASONING_EFFORT)
                    .orEmpty().trim().ifEmpty { DEFAULT_REASONING_EFFORT },
            )
        }

        /**
         * The quiet-hour review: after the user has stopped talking for a
         * while, the local brain reads the conversation back and files what is
         * worth keeping.
         *
         * On by default (it is the feature that makes the device feel like it
         * remembers you) but always switchable, because it spends the user's
         * own API key on a call nobody asked for. [dailyCap] is the other half
         * of that bargain: a conversation that keeps stopping and restarting
         * cannot run up an unbounded bill overnight.
         */
        data class SweepConfig(
            val enabled: Boolean,
            val quietMinutes: Int,
            val dailyCap: Int,
        )

        const val DEFAULT_SWEEP_QUIET_MINUTES = 20
        const val DEFAULT_SWEEP_DAILY_CAP = 12

        fun sweepConfig(context: Context): SweepConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return SweepConfig(
                // Anything other than "off" is on: a typo should leave the
                // feature working, since off is the value a user has to go out
                // of their way to choose.
                enabled = p.getString("sweep", "on")?.trim()?.lowercase() != "off",
                quietMinutes = (p.getString("sweep_quiet_minutes", "")
                    ?.trim()?.toIntOrNull() ?: DEFAULT_SWEEP_QUIET_MINUTES).coerceIn(5, 240),
                dailyCap = (p.getString("sweep_daily_cap", "")
                    ?.trim()?.toIntOrNull() ?: DEFAULT_SWEEP_DAILY_CAP).coerceIn(0, 100),
            )
        }

        /** Nothing pushes. Notifications still land in the ring buffer. */
        const val NOTIFY_OFF = "off"

        /** The chat/SMS/mail apps — the set that already interrupted the user. */
        const val NOTIFY_CHAT = "chat"

        /** Every app, minus the structural noise — see the relay's own filter. */
        const val NOTIFY_ALL = "all"

        /**
         * Which notifications the device is allowed to think about by itself.
         *
         * **The default is [NOTIFY_CHAT] and the fallback is [NOTIFY_OFF], and
         * those are deliberately different values.** An unset preference is a
         * device nobody has configured, and on it the honest scope is the one
         * that already interrupted the user before triage existed — so turning
         * the feature on changed *how* a chat message is handled, not *which*
         * apps are watched. An unrecognised string is a different thing
         * entirely: a typo, a downgrade, a setting written by a version that
         * knew a value this one does not. Nothing is known about what it was
         * meant to permit, so it permits nothing. Same fail-closed direction as
         * [groundingEnabled], and the picker lists 关 leftmost to match — see
         * [com.moss.app.ui.SettingsUi.segmented], whose leftmost option is
         * the fallback for exactly this case.
         *
         * Scope is all this decides. What *happens* to an in-scope notification
         * is [com.moss.app.brain.NotificationTriage]'s call, and when that
         * cannot run the signboard comes back — the user is the filter again,
         * which is what this device did before any of it existed.
         */
        fun notifyScope(context: Context): String {
            val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("notify", NOTIFY_CHAT)?.trim()?.lowercase()
            return when (v) {
                NOTIFY_ALL -> NOTIFY_ALL
                NOTIFY_CHAT -> NOTIFY_CHAT
                else -> NOTIFY_OFF
            }
        }


        fun save(context: Context, updates: Map<String, String>) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            for ((k, v) in updates) {
                if (k !in ALLOWED_KEYS) continue
                // Blank means "the user left this row alone", and is dropped
                // so it cannot wipe a saved value.
                if (v.isBlank()) continue
                p.putString(k, v.trim())
            }
            p.apply()
        }

        /** What [SettingsUi] shows. Only the keys it can actually edit. */
        fun current(context: Context): Map<String, String> {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hc = hubConfig(context)
            val bc = brainConfig(context)
            return mapOf(
                "hub_url" to hc.url,
                "device_id" to hc.deviceId,
                "device_name" to hc.deviceName,
                "brain" to bc.mode,
                "show_hub" to if (showHub(context)) "on" else "off",
                // llm_api_key / llm_base_url / llm_model are the 本机 tab's rows —
                // the user's own credentials and the endpoint they point at.
                // They stay visible no matter which backend is active, because
                // switching to CodeFlying and back must not look like the
                // local-tab fields were wiped. The compiled CodeFlying key never
                // flows through these prefs; it comes from BuildConfig at
                // [brainConfig] time, so showing the saved values here cannot
                // leak it.
                //
                // All three show saved-or-empty, never the resolved value. The
                // empty box is how the row says "the default is in use", and it
                // is the only state in which the hint is visible at all —
                // printing the resolved default into the box would be text, not
                // a placeholder, and the row's language-aware hint
                // ([llmModelDefault]: Agnes, Jarvis's setup, for either card) could never be seen.
                "llm_api_key" to p.getString("llm_api_key", "").orEmpty(),
                "llm_base_url" to p.getString("llm_base_url", "").orEmpty(),
                "llm_model" to p.getString("llm_model", "").orEmpty(),
                // Round-tripped as the wire words rather than a boolean: this map
                // feeds SettingsUi's pickers and the picker's value is what comes
                // back to [save], which only speaks String.
                "llm_thinking" to if (bc.thinking) "enabled" else "disabled",
                "llm_reasoning_effort" to bc.reasoningEffort,
                "grounding" to p.getString("grounding", "off")!!,
                "notify" to notifyScope(context),
                "lang" to uiLanguage(context),
                "sweep" to p.getString("sweep", "on")!!,
                "sweep_quiet_minutes" to p.getString(
                    "sweep_quiet_minutes", DEFAULT_SWEEP_QUIET_MINUTES.toString(),
                )!!,
                "sweep_daily_cap" to p.getString(
                    "sweep_daily_cap", DEFAULT_SWEEP_DAILY_CAP.toString(),
                )!!,
                "max_steps" to maxSteps(context).toString(),
                "asr_service" to p.getString("asr_service", "").orEmpty(),
                "asr_engine" to asrEngine(context),
            )
        }

        /**
         * Which recognition service a session is handed to, or null for the
         * system default. The stored value is the flattened component name;
         * a value naming a service that is no longer installed falls back
         * to the default rather than failing the turn, so an uninstalled
         * app cannot strand hearing.
         */
        fun asrService(context: Context): ComponentName? {
            val flat = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("asr_service", "").orEmpty().trim()
            if (flat.isEmpty()) return null
            val component = ComponentName.unflattenFromString(flat) ?: return null
            val installed = runCatching {
                context.packageManager.queryIntentServices(
                    Intent("android.speech.RecognitionService"),
                    0,
                )
            }.getOrDefault(emptyList())
            val present = installed.any {
                it.serviceInfo.packageName == component.packageName &&
                    it.serviceInfo.name == component.className
            }
            return if (present) component else null
        }

        /**
         * Whether [com.moss.app.screen.GroundingLog] records taps and
         * screenshots to disk. Off unless explicitly turned on: it writes
         * pictures of whatever the user is doing.
         *
         * Stored as "on"/"off" rather than a boolean because [save] takes a
         * `Map<String, String>` from [SettingsUi]'s text fields, drops blanks,
         * and only routes `tts_sample_rate` through Int — a real boolean has
         * nowhere to enter the pipeline. Anything other than "on" is off, so a
         * typo fails closed.
         */
        fun groundingEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("grounding", "off")
                ?.trim()?.lowercase() == "on"

        /**
         * Thinking for a device that has never saved the setting: the build's
         * `DEFAULT_THINKING` (local.properties, `on` / `off`), else on. Only the
         * unset case — a saved choice always wins, same as [uiLanguage].
         */
        private fun defaultThinking(): String =
            if (BuildConfig.DEFAULT_THINKING.trim().lowercase() == "off") "disabled" else "enabled"

        /**
         * Hard cap on tool-call steps per task. A real task is a dozen steps;
         * this is the runaway guard, and hitting it produces a final answer
         * rather than silence. Editable from the settings card so a user who
         * routinely runs longer tasks can raise it; coerced to a sane range
         * (1..200) so a stray zero doesn't make the brain refuse everything
         * and a stray thousand doesn't burn an unbounded bill.
         */
        const val DEFAULT_MAX_STEPS = 25

        fun maxSteps(context: Context): Int =
            (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("max_steps", "")?.trim()?.toIntOrNull()
                ?: DEFAULT_MAX_STEPS).coerceIn(1, 200)

        const val LANG_ZH = "zh"
        const val LANG_EN = "en"

        /**
         * Which language the user-facing UI speaks. [LANG_ZH] or [LANG_EN].
         *
         * Unset means *unset*, not Chinese: it falls through to the device's own
         * locale, because a tablet set to English showing a Chinese settings
         * card is the same bug as the reverse. Once the user picks one, the
         * saved value wins over the locale forever — they have said what they
         * want and a system language change should not overrule them.
         *
         * Not part of [current] merely for display: [com.moss.app.ui.SettingsUi]
         * writes this one the moment it is tapped rather than on 保存, because
         * the card redraws in the new language and that redraw *is* the
         * confirmation. Nothing else on this device is written eagerly.
         */
        fun uiLanguage(context: Context): String {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("lang", "").orEmpty().trim().lowercase()
            if (saved == LANG_EN) return LANG_EN
            if (saved == LANG_ZH) return LANG_ZH
            // The build's `DEFAULT_LANG` (local.properties) is the language of a
            // device nobody has picked for yet. It sits below the saved choice
            // and above the system locale; anything but zh/en reads as unset.
            when (BuildConfig.DEFAULT_LANG.trim().lowercase()) {
                LANG_EN -> return LANG_EN
                LANG_ZH -> return LANG_ZH
            }
            return if (java.util.Locale.getDefault().language == "zh") LANG_ZH else LANG_EN
        }

        /**
         * Which face the ball is wearing — a [com.moss.app.ui.ball.BallLook]
         * name, or blank for the first one.
         *
         * Like [uiLanguage] and unlike everything else here, it is written the
         * moment the user swipes rather than on 保存, and for the same reason:
         * the ball changing *is* the confirmation, and there is no card to
         * press a button on. It is also not in [current] — it has no settings
         * row, because the control is the ball itself.
         *
         * It is read from more than the ball now: the look carries the voice and
         * the manner as well as the drawing, so `LocalBrain` resolves it
         * through [com.moss.app.ui.ball.BallLooks.byName].
         */
        fun ballLook(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("ball", "").orEmpty().trim()

        /**
         * Whether the wake phrase is still listened for with the screen off.
         *
         * **On unless the user turns it off.** That default is the whole point
         * of the switch: the screen being off is the normal state of a phone in
         * a pocket, so the older rule — hold the microphone only while the
         * screen was on — meant the assistant could not be called from exactly
         * the place a person keeps their phone. The gate in
         * [com.moss.app.wake.WakeWord] used to spell that rule out
         * unconditionally; this is the user's override of it.
         *
         * What it costs, and why the settings row that flips it says so out
         * loud: with the screen off the microphone is held continuously, so the
         * platform's recording indicator stays lit and the segmenter runs for as
         * long as the device is awake. Nothing recoverable as speech leaves the
         * phone either way — see [com.moss.app.wake.WakeWord] — but
         * "listening all the time" is a claim the user is entitled to see stated
         * before they agree to it rather than discover on a status bar.
         *
         * Read from the wake supervisor's gate, which reconsiders ten times a
         * second. Cheap (SharedPreferences answers from an already-loaded map)
         * and deliberately not cached anywhere: the settings card writes it and
         * the very next poll has to see it.
         */
        fun wakeWhileLocked(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("wake_locked", true)

        fun setWakeWhileLocked(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("wake_locked", on).apply()
        }

        /**
         * The scene the assistant is in, by [Notebook.Scene.name], or null.
         *
         * Persisted for the same reason as [ballLook]: a service restart is not
         * the user leaving the scene. Written directly rather than through
         * [save], because [save] skips blank values and leaving a scene *is*
         * writing a blank.
         */
        fun activeScene(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("scene", "").orEmpty().trim().ifEmpty { null }

        /**
         * [byBrain] is true only for the `scene.*` tools. It exists for one
         * reader: the scene-ended event, which must not tell the brain what the
         * brain itself just did (a second turn to say "you left the scene").
         */
        fun setActiveScene(context: Context, name: String?, byBrain: Boolean = false) {
            val next = name?.trim().orEmpty()
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prev = p.getString("scene", "").orEmpty()
            if (prev == next) return
            p.edit().putString("scene", next).apply()
            onSceneChanged?.invoke(prev.ifEmpty { null }, next.ifEmpty { null }, byBrain)
        }

        /**
         * `(left, entered, byBrain)`, either name null. Set by the service so the
         * card's chip and the scrollback follow whoever changed it — the brain's
         * tool, the scenes card, the chip's ✕ or an app trigger. Called on the
         * writer's thread.
         */
        @Volatile
        var onSceneChanged: ((String?, String?, Boolean) -> Unit)? = null

        private val ALLOWED_KEYS = setOf(
            // Which palette the UI draws with — see [com.moss.app.ui.Theme].
            // In this list because [save] drops anything that is not: a key missing
            // here is a write that silently does nothing, which on a theme means the
            // picker moves, the card redraws and the setting is gone next launch.
            "theme",
            "hub_url", "device_name",
            "brain",
            "show_hub",
            "llm_api_key", "llm_base_url", "llm_model",
            "llm_thinking", "llm_reasoning_effort",
            "grounding",
            "notify",
            "lang",
            "ball",
            "asr_service",
            "asr_engine",
            "sweep", "sweep_quiet_minutes", "sweep_daily_cap",
            "max_steps",
        )
    }
}
