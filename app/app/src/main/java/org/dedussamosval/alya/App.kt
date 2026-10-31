package org.dedussamosval.alya

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.dedussamosval.alya.data.Api
import org.dedussamosval.alya.data.CatalogRepository
import org.dedussamosval.alya.data.ChapterRepository
import org.dedussamosval.alya.data.FileStore
import org.dedussamosval.alya.data.NewChaptersWorker
import org.dedussamosval.alya.data.Notifications
import org.dedussamosval.alya.data.Push
import org.dedussamosval.alya.data.SettingsStore
import java.util.concurrent.TimeUnit

/** Простой контейнер зависимостей: один экземпляр на процесс, ViewModel'и берут всё отсюда. */
class AppContainer(app: App) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS) // держит WebSocket чата живым
        .build()

    val api = Api(http, BuildConfig.SERVER_URL)
    val files = FileStore(app)
    val settings = SettingsStore(app)
    val catalog = CatalogRepository(api, files)
    val chapters = ChapterRepository(app, api, files, settings)
}

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // Включение/выключение уведомлений о новых главах сразу меняет фоновую задачу
        container.scope.launch {
            container.settings.flow
                .map { it.notifyChapters }
                .distinctUntilChanged()
                .collect { enabled ->
                    NewChaptersWorker.schedule(this@App, enabled)   // запасной путь: проверка по расписанию
                    Push.setSubscribed(this@App, enabled)           // мгновенные push (если подключён Firebase)
                }
        }
    }
}
