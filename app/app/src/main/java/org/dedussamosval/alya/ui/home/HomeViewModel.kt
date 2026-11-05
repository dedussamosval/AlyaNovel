package org.dedussamosval.alya.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import org.dedussamosval.alya.data.Chapter
import org.dedussamosval.alya.data.KIND_MANGA
import org.dedussamosval.alya.data.Volume
import org.dedussamosval.alya.data.WifiOnlyException

data class HomeUi(
    val kind: String = KIND_MANGA,
    val volumes: List<Volume> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    val downloaded: Set<Int> = emptySet(),
    val progress: Map<Int, Float> = emptyMap(),
    val readIds: Set<Int> = emptySet(),
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {

    private val state = MutableStateFlow(HomeUi(volumes = c.catalog.cached(KIND_MANGA).orEmpty()))

    val ui: StateFlow<HomeUi> = combine(state, c.settings.flow) { s, p -> s.copy(readIds = p.readIds) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUi())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var loadJob: Job? = null

    init {
        load(pull = false)
    }

    fun selectKind(kind: String) {
        if (kind == state.value.kind) return
        state.update {
            it.copy(kind = kind, volumes = c.catalog.cached(kind).orEmpty(), loading = true, error = null, offline = false)
        }
        load(pull = false)
    }

    fun refresh() = load(pull = true)

    private fun load(pull: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val kind = state.value.kind
            state.update { it.copy(loading = !pull, refreshing = pull) }
            try {
                val result = c.catalog.load(kind)
                if (state.value.kind != kind) return@launch
                val downloaded = withContext(Dispatchers.IO) { downloadedIds(result.volumes) }
                state.update {
                    it.copy(
                        volumes = result.volumes, offline = result.offline, loading = false,
                        refreshing = false, error = null, downloaded = downloaded,
                    )
                }
                // Всё, что мы только что показали, считается «увиденным» — по нему фоновая проверка не уведомит повторно
                if (!result.offline) {
                    result.volumes.flatMap { it.chapters }.maxOfOrNull { it.created }?.let { c.settings.advanceLastSeen(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Ошибка загрузки") }
            }
        }
    }

    /** Вызывать при возврате на экран (например, из читалки): мог скачаться файл или измениться набор. */
    fun refreshDownloaded() {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { downloadedIds(state.value.volumes) }
            state.update { it.copy(downloaded = ids) }
        }
    }

    private fun downloadedIds(volumes: List<Volume>): Set<Int> =
        volumes.flatMap { it.chapters }.filter { c.chapters.isDownloaded(it) }.map { it.id }.toSet()

    fun download(ch: Chapter) {
        val s = state.value
        if (ch.id in s.downloaded) {
            _messages.tryEmit("Глава уже скачана")
            return
        }
        if (ch.id in s.progress) return
        viewModelScope.launch {
            setProgress(ch.id, 0f)
            try {
                c.chapters.ensure(ch) { p -> setProgress(ch.id, p) }
                state.update { it.copy(downloaded = it.downloaded + ch.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: WifiOnlyException) {
                _messages.tryEmit("Включён режим «только по Wi-Fi»")
            } catch (e: Exception) {
                _messages.tryEmit("Не удалось скачать главу")
            } finally {
                state.update { it.copy(progress = it.progress - ch.id) }
            }
        }
    }

    private fun setProgress(id: Int, p: Float) {
        state.update { it.copy(progress = it.progress + (id to p)) }
    }
}
