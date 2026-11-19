package com.lagoon.home

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.util.Log

/**
 * The TV's inputs (HDMI, the tuner, composite…) as Android knows them, for TVs and boxes that have them. An input opens
 * through the TV's own Live TV app ("passthrough"), and can sit on the quick-access bar like an app: the bar stores it
 * as "input:<id>".
 */
object Inputs {
    class Input(val id: String, val label: String, val type: Int)

    const val PREFIX = "input:"
    fun isInput(entry: String) = entry.startsWith(PREFIX)

    /** The inputs to show, in the TV's order; hidden ones (set so in the TV's own settings) are left out. */
    fun list(ctx: Context): List<Input> = runCatching {
        val tm = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return emptyList()
        tm.tvInputList.filter { !it.isHidden(ctx) && it.parentId == null }
            .map { Input(it.id, label(ctx, it), it.type) }
    }.onFailure { Log.w("Inputs", "list: $it") }.getOrDefault(emptyList())

    private fun label(ctx: Context, i: TvInputInfo): String =
        i.loadCustomLabel(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: i.loadLabel(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: typeName(i.type)

    fun typeName(type: Int) = when (type) {
        TvInputInfo.TYPE_HDMI -> "HDMI"; TvInputInfo.TYPE_TUNER -> "TV"; TvInputInfo.TYPE_COMPONENT -> "Component"
        TvInputInfo.TYPE_COMPOSITE -> "AV"; TvInputInfo.TYPE_VGA -> "VGA"; TvInputInfo.TYPE_DISPLAY_PORT -> "DisplayPort"
        TvInputInfo.TYPE_DVI -> "DVI"; TvInputInfo.TYPE_SCART -> "SCART"; TvInputInfo.TYPE_SVIDEO -> "S-Video"; else -> "Input"
    }

    fun find(ctx: Context, entry: String): Input? = list(ctx).firstOrNull { PREFIX + it.id == entry }

    /** Switch to the input (the TV's Live TV app shows it). */
    fun open(ctx: Context, id: String): Boolean = runCatching {
        val uri = TvContract.buildChannelUriForPassthroughInput(id)
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Log.w("Inputs", "open $id: $it") }.isSuccess

    /** A bar tile for an input: its short name ("HDMI 2") on a dark tile (the glass style turns it into glass). */
    fun tile(label: String, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        c.drawColor(0xFF1C2A35.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textAlign = Paint.Align.CENTER }
        val words = label.split(' ').filter { it.isNotBlank() }
        val lines = if (words.size >= 2 && label.length > 6) listOf(words.dropLast(1).joinToString(" "), words.last()) else listOf(label)
        p.textSize = size * (if (lines.size == 1) 0.26f else 0.22f)
        while (lines.any { p.measureText(it) > size * 0.82f } && p.textSize > size * 0.12f) p.textSize *= 0.92f
        val lh = p.textSize * 1.1f; var y = size / 2f - lh * (lines.size - 1) / 2f + p.textSize * 0.35f
        for (l in lines) { c.drawText(l, size / 2f, y, p); y += lh }
        // A thin connector line under the name, so it reads as an input rather than an app.
        p.strokeWidth = size * 0.03f
        c.drawRoundRect(RectF(size * 0.36f, size * 0.80f, size * 0.64f, size * 0.83f), size * 0.02f, size * 0.02f, p)
        return b
    }
}
