package pl.pk.mp3player

import android.content.Context
import android.content.SharedPreferences

/**
 * Trwale ustawienia aplikacji (SharedPreferences).
 *
 * Przechowuje:
 *  - URI wybranego drzewa katalogow (SAF),
 *  - identyfikator i pozycje ostatnio odtwarzanego utworu,
 *  - stan trybu losowego i zapetlenia,
 *  - ziarno (seed) ostatnio uzytej permutacji losowej - aby przy kazdym
 *    uruchomieniu wygenerowac INNA strategie odtwarzania losowego.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var treeUri: String?
        get() = sp.getString(KEY_TREE_URI, null)
        set(value) = sp.edit().putString(KEY_TREE_URI, value).apply()

    var lastTrackId: String?
        get() = sp.getString(KEY_LAST_TRACK, null)
        set(value) = sp.edit().putString(KEY_LAST_TRACK, value).apply()

    var lastPositionMs: Long
        get() = sp.getLong(KEY_LAST_POSITION, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_POSITION, value).apply()

    var shuffleEnabled: Boolean
        get() = sp.getBoolean(KEY_SHUFFLE, false)
        set(value) = sp.edit().putBoolean(KEY_SHUFFLE, value).apply()

    /** Wartosci zgodne z androidx.media3.common.Player.RepeatMode. */
    var repeatMode: Int
        get() = sp.getInt(KEY_REPEAT, 0)
        set(value) = sp.edit().putInt(KEY_REPEAT, value).apply()

    var lastShuffleSeed: Long
        get() = sp.getLong(KEY_SHUFFLE_SEED, 0L)
        set(value) = sp.edit().putLong(KEY_SHUFFLE_SEED, value).apply()

    companion object {
        private const val FILE_NAME = "mp3player_prefs"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_LAST_TRACK = "last_track_id"
        private const val KEY_LAST_POSITION = "last_position_ms"
        private const val KEY_SHUFFLE = "shuffle_enabled"
        private const val KEY_REPEAT = "repeat_mode"
        private const val KEY_SHUFFLE_SEED = "shuffle_seed"
    }
}
