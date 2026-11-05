package org.dedussamosval.alya.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dedussamosval.alya.AppContainer
import org.dedussamosval.alya.BuildConfig
import org.dedussamosval.alya.data.AppVersion
import org.dedussamosval.alya.data.Keys
import org.dedussamosval.alya.data.Prefs

data class SettingsUi(
    val prefs: Prefs = Prefs(),
    val downloadsSize: Long = 0,
    val cacheSize: Long = 0,
    val update: AppVersion? = null, // не null — показываем диалог «Доступно обновление»
)

private data class Extra(val downloadsSize: Long = 0, val cacheSize: Long = 0, val update: AppVersion? = null)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {

    private val extra = MutableStateFlow(Extra())

    val ui: StateFlow<SettingsUi> = combine(c.settings.flow, extra) { p, e ->
        SettingsUi(p, e.downloadsSize, e.cacheSize, e.update)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUi())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        refreshSizes()
    }

    fun refreshSizes() {
        viewModelScope.launch {
            val (d, k) = withContext(Dispatchers.IO) { c.files.downloadsSize() to c.files.cacheSize() }
            extra.update { it.copy(downloadsSize = d, cacheSize = k) }
        }
    }

    fun setNotifyChapters(v: Boolean) = launchSet { c.settings.set(Keys.NOTIFY_CHAPTERS, v) }
    fun setNotifyChat(v: Boolean) = launchSet { c.settings.set(Keys.NOTIFY_CHAT, v) }
    fun setSaveOffline(v: Boolean) = launchSet { c.settings.set(Keys.SAVE_OFFLINE, v) }
    fun setWifiOnly(v: Boolean) = launchSet { c.settings.set(Keys.WIFI_ONLY, v) }
    fun setTheme(v: String) = launchSet { c.settings.set(Keys.THEME, v) }
    fun setFontSize(v: Int) = launchSet { c.settings.set(Keys.FONT_SIZE, v) }

    private fun launchSet(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { c.files.clearCache() }
            refreshSizes()
            _messages.tryEmit("Кэш очищен")
        }
    }

    fun clearDownloads() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                c.files.clearDownloads()
                c.files.clearCache()
            }
            refreshSizes()
            _messages.tryEmit("Скачанные главы удалены")
        }
    }

    fun checkUpdates() {
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { c.api.appVersion() }
                if (isNewer(info.latest, BuildConfig.VERSION_NAME)) {
                    extra.update { it.copy(update = info) }
                } else {
                    _messages.tryEmit("У вас последняя версия")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Не удалось проверить обновления")
            }
        }
    }

    fun dismissUpdate() {
        extra.update { it.copy(update = null) }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
