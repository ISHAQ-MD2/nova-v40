package com.nova.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * v30: Live wallpaper ke liye ASLI events ka chhota noticeboard.
 * Kuch bhi nakli nahi: sab yahan wahi aata hai jo app mein sach mein hua (awaaz ka text, reminder, nayi file, Termux command, download).
 * Naya event dikhana ho to bas WallBus.xxx() bulao - wallpaper khud utha leti hai.
 */
object WallBus {
    class FileEvt(val name: String, val size: Long, val at: Long, val fresh: Boolean)
    class FeedEvt(val at: Long, val tag: String, val text: String)

    @Volatile var userLine = ""; @Volatile var userAt = 0L
    @Volatile var novaLine = ""; @Volatile var novaAt = 0L
    @Volatile var actionLine = ""; @Volatile var actionAt = 0L
    @Volatile var termuxCmd = ""; @Volatile var termuxOut = ""; @Volatile var termuxAt = 0L
    @Volatile var alertText = ""; @Volatile var alertUntil = 0L
    @Volatile var flashFile = ""; @Volatile var flashUntil = 0L
    @Volatile var toolCount = 0; @Volatile var msgCount = 0; @Volatile var lastTool = ""
    @Volatile var dlLine = ""; @Volatile var dlAt = 0L
    val files = CopyOnWriteArrayList<FileEvt>()
    val feed = CopyOnWriteArrayList<FeedEvt>()

    /** Memory.log se aata hai (user / nova / tool / action / termux ...). */
    fun onLog(kind: String, text: String) {
        val now = System.currentTimeMillis()
        when (kind) {
            "user" -> { userLine = text; userAt = now; msgCount++ }
            "nova" -> { novaLine = text; novaAt = now; msgCount++ }
            "tool", "action", "agent-tool", "task-result" -> {
                actionLine = kind + " > " + text; actionAt = now
                if (kind == "tool" || kind == "agent-tool") { toolCount++; lastTool = text.substringBefore(' ').take(24) }
                pushFeed(kind.uppercase().take(6), text)
            }
            "crash" -> pushFeed("CRASH", text)
            else -> if (kind != "code") pushFeed(kind.uppercase().take(6), text)
        }
    }

    /** LiveClient bolte waqt ka adhoora text bhi bhejta hai (jab tak turn poora na ho). */
    fun live(kind: String, text: String) {
        val now = System.currentTimeMillis()
        if (kind == "user") { userLine = text; userAt = now } else { novaLine = text; novaAt = now }
    }

    fun pushFeed(tag: String, text: String) {
        feed.add(0, FeedEvt(System.currentTimeMillis(), tag, text.replace('\n', ' ').trim().take(120)))
        while (feed.size > 14) feed.removeAt(feed.size - 1)
    }

    fun alert(text: String, ms: Long = 25_000L) { alertText = text; alertUntil = System.currentTimeMillis() + ms; pushFeed("ALERT", text) }

    fun download(line: String) { dlLine = line; dlAt = System.currentTimeMillis(); pushFeed("DL", line) }

    fun termux(cmd: String, out: String?) {
        val now = System.currentTimeMillis()
        if (out == null) { termuxCmd = cmd; termuxOut = "running..."; termuxAt = now; pushFeed("TERMUX", "$ " + cmd.replace('\n', ' ')) }
        else { termuxOut = out; termuxAt = now }
    }

    fun fileEvent(name: String, size: Long, fresh: Boolean) {
        files.add(0, FileEvt(name, size, System.currentTimeMillis(), fresh))
        while (files.size > 8) files.removeAt(files.size - 1)
        if (fresh) { flashFile = name; flashUntil = System.currentTimeMillis() + 6000L; pushFeed("FILE", name + " (" + WallData.fmtBytes(size) + ")") }
    }
}
