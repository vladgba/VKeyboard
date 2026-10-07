package x.vladgba.keyboard.keyboard

import android.media.MediaPlayer
import android.util.Log

/**
 * Lazily created, shared sound players. The old code created two MediaPlayers for every key
 * (≈150 native players per layout) even when no sounds were configured.
 */
class KeySounds {
    private val players = HashMap<String, MediaPlayer?>()

    fun play(path: String) {
        if (path.isBlank()) return
        if (!players.containsKey(path)) players[path] = try {
            MediaPlayer().apply { setDataSource(path); prepare() }
        } catch (e: Exception) {
            Log.w("KeySounds", "Sound file $path not loaded", e)
            null
        }
        val mp = players[path] ?: return
        try {
            if (mp.isPlaying) mp.seekTo(0) else mp.start()
        } catch (_: Exception) {
        }
    }

    fun release() {
        for (p in players.values) p?.release()
        players.clear()
    }
}
