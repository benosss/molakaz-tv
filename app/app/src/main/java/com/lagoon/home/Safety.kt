package com.lagoon.home

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * The safety net: if MoLakaz TV crashes three times within five minutes, the box's original home screen comes back by
 * itself (switched on again and made the home screen through the box's own adb), so a bad update or an odd box can
 * never leave a TV without a working home screen. MoLakaz TV stays installed: open it from the apps list and
 * Settings › Original home screen switches back.
 *
 * Only real crashes count. Android also ends Home's process when memory runs low while another app is in front, and
 * when the app is updated; neither is a crash, and neither should take MoLakaz TV away.
 */
object Safety {
    private const val TAG = "Safety"
    private const val WINDOW = 5 * 60_000L
    private const val LIMIT = 3
    private lateinit var app: Context

    /** Set when the safety net brought the original home screen back (cleared when MoLakaz TV is chosen again). */
    val tripped get() = app.getSharedPreferences("safety", Context.MODE_PRIVATE).getBoolean("tripped", false)

    fun install(ctx: Context) {
        app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { onCrash(e) }
            previous?.uncaughtException(t, e)
        }
    }

    private fun onCrash(e: Throwable) {
        val sp = app.getSharedPreferences("safety", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val recent = (sp.getString("crashes", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.filter { now - it < WINDOW } + now
        sp.edit().putString("crashes", recent.joinToString(",")).commit()
        Log.e(TAG, "crash ${recent.size} of $LIMIT in five minutes: $e")
        if (recent.size >= LIMIT && OriginalHome.exists(app)) {
            // We're still alive in the handler: give it four seconds to hand the TV back, then let the crash go on.
            val t = thread(name = "safety") { restore() }
            t.join(4000)
            sp.edit().putBoolean("tripped", true).putString("crashes", "").commit()
        }
    }

    /** The original home screen on, and the home screen again. */
    fun restore() {
        val pkgs = OriginalHome.present(app)
        Remote.shell(pkgs.joinToString("; ") { "pm enable $it" })
        val stock = app.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo }.firstOrNull { it.packageName != app.packageName && it.packageName in pkgs }
        if (stock != null) Remote.shell("cmd package set-home-activity ${stock.packageName}/${stock.name}")
        Log.w(TAG, "crashed $LIMIT times in five minutes: the original home screen is back (${stock?.packageName})")
    }

    /** MoLakaz TV was chosen as home again (Settings › Original home screen): start counting afresh. */
    fun reset() { app.getSharedPreferences("safety", Context.MODE_PRIVATE).edit().putBoolean("tripped", false).putString("crashes", "").apply() }
}
