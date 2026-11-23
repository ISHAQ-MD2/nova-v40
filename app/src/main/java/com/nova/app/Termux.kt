package com.nova.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Termux mein command chalana (RUN_COMMAND) aur output/error wapas lena.
 * Termux mein ek baar:  mkdir -p ~/.termux && echo "allow-external-apps=true" >> ~/.termux/termux.properties
 * phir Termux band karke dobara kholo, aur NOVA Setup mein "Termux permission" Allow karo.
 */
object TermuxBridge {
    @Volatile var latch: CountDownLatch? = null
    @Volatile var result: String = ""

    private const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    fun installed(ctx: Context) = try { ctx.packageManager.getPackageInfo("com.termux", 0); true } catch (_: Exception) { false }

    fun run(ctx: Context, cmd: String, visible: Boolean): String {
        if (!installed(ctx)) return "Termux install nahi hai."
        WallBus.termux(cmd, null)
        val l = CountDownLatch(1); latch = l; result = ""
        val pi = PendingIntent.getBroadcast(ctx, 77, Intent(ctx, TermuxResultReceiver::class.java),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val i = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", !visible)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        try { ctx.startForegroundService(i) }
        catch (e: SecurityException) {
            return "Termux permission nahi hai. 1) NOVA Setup mein Termux permission Allow karo. 2) Termux mein chalao: " +
                "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties  phir Termux dobara kholo."
        } catch (e: Exception) { return "Termux command start nahi hui: ${e.message}" }
        val ok = l.await(if (visible) 10 else 120, TimeUnit.SECONDS)
        val res = if (ok) result.ifEmpty { "(koi output nahi)" }
        else if (visible) "Command Termux ki screen par chal rahi hai - read_screen se output dekho."
        else "Timeout: command 2 minute mein khatam nahi hui."
        WallBus.termux(cmd, res)
        return res
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val b = i.getBundleExtra("result")
        val out = if (b == null) "(Termux ne result nahi bheja)" else
            "exit=${b.getInt("exitCode", -1)}\nstdout:\n${b.getString("stdout", "")}\nstderr:\n${b.getString("stderr", "")}" +
                (b.getString("errmsg")?.takeIf { it.isNotEmpty() }?.let { "\nerror: $it" } ?: "")
        TermuxBridge.result = out.take(3500)
        TermuxBridge.latch?.countDown()
    }
}
