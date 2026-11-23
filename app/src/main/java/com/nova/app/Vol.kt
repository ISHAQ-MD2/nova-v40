package com.nova.app

import android.os.Handler
import android.os.Looper

/** NOVA ki awaaz ka boost + mute flags + avatar/robot ke liye shared state. */
object Vol {
    @Volatile var boost = 1f
    @Volatile var level = 0f        // NOVA ki bolti awaaz ka asli level 0..1 (wallpaper waveform)
    @Volatile var micLevel = 0f     // mic ka asli level 0..1
    @Volatile var micMuted = false       // meri awaaz NOVA tak nahi jayegi
    @Volatile var speakerMuted = false   // NOVA ki awaaz band (text se baat)
    @Volatile var onEmote: ((String) -> Unit)? = null

    /** NOVA bol rahi hai? Avatar aur chhota robot dono isse mooh hilate hain. */
    @Volatile var speaking = false
        private set
    private val speakListeners = ArrayList<(Boolean) -> Unit>()
    private val emoteListeners = ArrayList<(String) -> Unit>()
    private val ui = Handler(Looper.getMainLooper())

    @Synchronized fun addSpeakListener(l: (Boolean) -> Unit) { if (!speakListeners.contains(l)) speakListeners.add(l) }
    @Synchronized fun removeSpeakListener(l: (Boolean) -> Unit) { speakListeners.remove(l) }
    @Synchronized fun addEmoteListener(l: (String) -> Unit) { if (!emoteListeners.contains(l)) emoteListeners.add(l) }
    @Synchronized fun removeEmoteListener(l: (String) -> Unit) { emoteListeners.remove(l) }

    fun setSpeaking(v: Boolean) {
        speaking = v
        val copy = synchronized(this) { ArrayList(speakListeners) }
        ui.post { copy.forEach { try { it(v) } catch (_: Exception) {} } }
    }
    fun emote(name: String) {
        val copy = synchronized(this) { ArrayList(emoteListeners) }
        ui.post { copy.forEach { try { it(name) } catch (_: Exception) {} }; try { onEmote?.invoke(name) } catch (_: Exception) {} }
    }
}
