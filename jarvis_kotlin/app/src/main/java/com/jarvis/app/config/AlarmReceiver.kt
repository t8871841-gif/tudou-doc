/*
 * Copyright 2026 KUAFUAI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from Andee: net.kuafuai.andee.config.AlarmReceiver
 * (~/workspace/tudou-git/andee/app/src/main/java/net/kuafuai/andee/config/AlarmReceiver.kt).
 * Same dispatcher shape — it decides nothing, it only routes a firing alarm
 * or a boot into [Scheduler]. Two Jarvis differences: there is no
 * BodyCoreService / sweep timer to wake (Jarvis has no quiet-hour sweep), and
 * day-to-day reminder firing already goes through the existing
 * [com.jarvis.app.ReminderReceiver], so the alarm path here is the entry
 * point for alarms armed directly against this receiver and for boot
 * catch-up; the stored row is the single source of truth either way.
 */

package com.jarvis.app.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The alarm clock, and the only entry point into this app that does not come
 * from the user.
 *
 * It decides nothing. A firing alarm is turned into a notification (and a
 * tap target that opens the app at the alarm), or, after a reboot, into a
 * re-arm of every live reminder via [Scheduler.catchUp]. The state
 * transitions themselves live in [Scheduler] and
 * [com.jarvis.app.ReminderScheduler] so that this class stays a dispatcher
 * — an alarm firing during a reboot is exactly the situation where "clever"
 * code loses someone's data.
 */
class AlarmReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "JarvisSched"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_ALARM ->
                fire(context, intent.getIntExtra(Scheduler.EXTRA_ID, 0), intent.getStringExtra(Scheduler.EXTRA_LABEL))

            Intent.ACTION_BOOT_COMPLETED, Scheduler.QUICKBOOT ->
                onBoot(context)

            else ->
                Log.w(TAG, "AlarmReceiver got an unexpected action: ${intent.action}")
        }
    }

    /**
     * An alarm came due.
     *
     * The row is looked up first: a fired alarm whose row is gone (deleted,
     * or already delivered by [com.jarvis.app.ReminderReceiver]) is ignored
     * rather than re-announced. A live row is delivered as a notification
     * and dropped from the store, mirroring what ReminderReceiver does for
     * reminders armed through it — one store, one delivery shape.
     */
    private fun fire(context: Context, id: Int, label: String?) {
        if (id == 0) {
            Log.w(TAG, "alarm without an id")
            return
        }
        val row = Scheduler.listReminders(context).firstOrNull { it["id"] == id }
        if (row == null) {
            Log.i(TAG, "alarm for unknown or already-delivered id=$id; ignored")
            return
        }
        val text = label ?: (row["body"] as? String).orEmpty()
        Scheduler.cancelReminder(context, id)
        Log.i(TAG, "alarm due: ${text.take(60)} — posting notification")
        Scheduler.notifyMissed(context, id, text)
    }

    /**
     * After a reboot.
     *
     * Every live reminder has lost its alarm (AlarmManager is not
     * persistent), and whatever came due while the device was off never fired
     * at all. [Scheduler.catchUp] re-arms the future; the past is dropped by
     * the existing store (see the gap noted in [Scheduler]'s header) rather
     * than replayed — a backlog of "it's 8am!" at 3pm is noise, not service.
     */
    private fun onBoot(context: Context) {
        Log.i(TAG, "boot: re-arming reminders")
        runCatching { Scheduler.catchUp(context) }.onFailure {
            Log.e(TAG, "catch-up after boot failed", it)
        }
        // The sweep timer is deliberately NOT armed here: Jarvis has no
        // quiet-hour sweep, and arming one at boot would serve nothing.
    }
}
