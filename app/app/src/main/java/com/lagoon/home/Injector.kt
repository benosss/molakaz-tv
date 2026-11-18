package com.lagoon.home

import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Presses keys for the phone remote. Started by the app through the box's own adb as
 * `CLASSPATH=<this apk> app_process / com.lagoon.home.Injector <port> <token>`, so it runs as the shell user, which may
 * inject input (an ordinary app can't, and there's no root). It listens on 127.0.0.1 only and every line must start
 * with the token the app generated, so nothing on the network or on the box can drive it except the home app.
 *
 * Protocol, one command per line, one reply line each:
 *   `<token> ping`                      → `ok <version>`
 *   `<token> key <code> tap|down|repeat|up` → `ok`
 *   `<token> text <characters>`         → `ok`
 *   `<token> quit`
 */
object Injector {
    const val VERSION = 1

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args[0].toInt(); val token = args[1]
        val im = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        val injectM = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val downAt = HashMap<Int, Long>(); val repeats = HashMap<Int, Int>()

        fun inject(e: InputEvent) { injectM.invoke(im, e, 0 /* async */) }
        fun key(action: Int, code: Int, repeat: Int, since: Long, flags: Int = 0) {
            inject(KeyEvent(since, SystemClock.uptimeMillis(), action, code, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM or flags, InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD))
        }

        val server = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        while (true) {
            val sock = server.accept()
            thread {
                sock.use { s ->
                    s.tcpNoDelay = true
                    val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8)); val w = s.getOutputStream()
                    while (true) {
                        val line = r.readLine() ?: break
                        val p = line.split(' ', limit = 3)
                        if (p.size < 2 || p[0] != token) { w.write("denied\n".toByteArray()); break }
                        val reply = runCatching {
                            when (p[1]) {
                                "ping" -> "ok $VERSION"
                                "quit" -> { w.write("bye\n".toByteArray()); w.flush(); exitProcess(0) }
                                "text" -> { kcm.getEvents(p.getOrElse(2) { "" }.toCharArray())?.forEach { inject(it) }; "ok" }
                                "key" -> {
                                    val a = p[2].split(' '); val code = a[0].toInt()
                                    synchronized(downAt) {
                                        when (a.getOrElse(1) { "tap" }) {
                                            "down" -> { val t = SystemClock.uptimeMillis(); downAt[code] = t; repeats[code] = 0; key(KeyEvent.ACTION_DOWN, code, 0, t) }
                                            // Held keys repeat like a real remote; the first repeat counts as a long press.
                                            "repeat" -> { val t = downAt[code] ?: SystemClock.uptimeMillis().also { downAt[code] = it }
                                                val n = (repeats[code] ?: 0) + 1; repeats[code] = n
                                                key(KeyEvent.ACTION_DOWN, code, n, t, if (n == 1) KeyEvent.FLAG_LONG_PRESS else 0) }
                                            "up" -> { val t = downAt.remove(code) ?: SystemClock.uptimeMillis(); repeats.remove(code); key(KeyEvent.ACTION_UP, code, 0, t) }
                                            else -> { val t = SystemClock.uptimeMillis(); key(KeyEvent.ACTION_DOWN, code, 0, t); key(KeyEvent.ACTION_UP, code, 0, t) }
                                        }
                                    }
                                    "ok"
                                }
                                else -> "err unknown"
                            }
                        }.getOrElse { "err ${it.javaClass.simpleName}" }
                        w.write("$reply\n".toByteArray()); w.flush()
                    }
                }
            }
        }
    }
}
