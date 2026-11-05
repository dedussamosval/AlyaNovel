package org.dedussamosval.alya.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "settings")


// * Редакция Пользовательского соглашения и Политики конфиденциальности (дата из server/legal/*.md).
// * Принятая редакция хранится в настройках: изменили тексты — поменяйте эту строку, и при следующем
// * запуске приложение снова попросит принять условия.



const val TOS_VERSION = "2026-10-23"

object Keys {
    val NOTIFY_CHAPTERS = booleanPreferencesKey("notify_chapters")
    val NOTIFY_CHAT = booleanPreferencesKey("notify_chat")
    val SAVE_OFFLINE = booleanPreferencesKey("save_offline")
    val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    val THEME = stringPreferencesKey("theme")          // "system" | "light" | "dark"
    val FONT_SIZE = intPreferencesKey("font_size")     // sp, размер текста в читалке
    val FONT_FAMILY = stringPreferencesKey("font_family") // "sans" | "serif" | "mono"
    val LINE_SPACING = intPreferencesKey("line_spacing")  // межстрочный интервал в % от размера шрифта (120..200)
    val SESSION = stringPreferencesKey("session")
    val USER_NAME = stringPreferencesKey("user_name")
    val LAST_SEEN = longPreferencesKey("last_seen")    // unix-секунды: новее этого — «новая глава»
    val READ_IDS = stringSetPreferencesKey("read_ids")
    val TOS_ACCEPTED = stringPreferencesKey("tos_accepted") // редакция условий, которую принял пользователь
}

data class Prefs(
    val notifyChapters: Boolean = true,
    val notifyChat: Boolean = true,
    val saveOffline: Boolean = true,
    val wifiOnly: Boolean = false,
    val theme: String = "system",
    val fontSize: Int = 17,
    val fontFamily: String = "sans",
    val lineSpacing: Int = 160,
    val session: String = "",
    val userName: String = "",
    val lastSeen: Long = 0L,
    val readIds: Set<Int> = emptySet(),
    val tosAccepted: String = "",
)

class SettingsStore(private val context: Context) {

    val flow: Flow<Prefs> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            Prefs(
                notifyChapters = p[Keys.NOTIFY_CHAPTERS] ?: true,
                notifyChat = p[Keys.NOTIFY_CHAT] ?: true,
                saveOffline = p[Keys.SAVE_OFFLINE] ?: false,
                wifiOnly = p[Keys.WIFI_ONLY] ?: false,
                theme = p[Keys.THEME] ?: "system",
                fontSize = p[Keys.FONT_SIZE] ?: 17,
                fontFamily = p[Keys.FONT_FAMILY] ?: "sans",
                lineSpacing = p[Keys.LINE_SPACING] ?: 160,
                session = p[Keys.SESSION] ?: "",
                userName = p[Keys.USER_NAME] ?: "",
                lastSeen = p[Keys.LAST_SEEN] ?: 0L,
                readIds = (p[Keys.READ_IDS] ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet(),
                tosAccepted = p[Keys.TOS_ACCEPTED] ?: "",
            )
        }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    suspend fun saveSession(session: String, name: String) {
        context.dataStore.edit {
            it[Keys.SESSION] = session
            it[Keys.USER_NAME] = name
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.SESSION)
            it.remove(Keys.USER_NAME)
        }
    }

    suspend fun markRead(chapterId: Int) {
        context.dataStore.edit {
            it[Keys.READ_IDS] = (it[Keys.READ_IDS] ?: emptySet()) + chapterId.toString()
        }
    }

    /** Первый запуск: запоминаем принятую редакцию условий и сразу выставляем переключатель уведомлений. */
    suspend fun acceptTos(version: String, notifyChapters: Boolean) {
        context.dataStore.edit {
            it[Keys.TOS_ACCEPTED] = version
            it[Keys.NOTIFY_CHAPTERS] = notifyChapters
        }
    }

    /** Двигает «последнее виденное» только вперёд. */
    suspend fun advanceLastSeen(ts: Long) {
        context.dataStore.edit {
            if (ts > (it[Keys.LAST_SEEN] ?: 0L)) it[Keys.LAST_SEEN] = ts
        }
    }
}

object Net {
    /** Wi-Fi или Ethernet (не мобильная сеть). Для режима «только по Wi-Fi». */
    fun isUnmetered(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
