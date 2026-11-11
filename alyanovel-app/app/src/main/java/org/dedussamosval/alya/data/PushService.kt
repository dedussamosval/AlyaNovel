package org.dedussamosval.alya.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dedussamosval.alya.App
import org.dedussamosval.alya.BuildConfig

/**
 * Мгновенные уведомления о новых главах через Firebase Cloud Messaging.
 * Устройство подписывается на тему [TOPIC]; сервер при публикации главы шлёт сообщение в эту тему (server/push.py).
 * Фоновая проверка по расписанию (NewChaptersWorker) остаётся запасным путём — для телефонов без сервисов Google.
 */
object Push {
    /** Должно совпадать с FCM_TOPIC и NOTICE_TOPIC на сервере (по умолчанию new_chapters). */
    const val TOPIC = "new_chapters"
    const val NOTICE_TOPIC = "app_notices"

    /** Подписка следует за переключателем «Новые главы». Без google-services.json Firebase не инициализирован — тихо выходим. */
    fun setSubscribed(context: Context, enabled: Boolean) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val messaging = FirebaseMessaging.getInstance()
        if (enabled) { messaging.subscribeToTopic(TOPIC); messaging.subscribeToTopic(NOTICE_TOPIC) }
        else { messaging.unsubscribeFromTopic(TOPIC); messaging.unsubscribeFromTopic(NOTICE_TOPIC) }
    }
}

class PushService : FirebaseMessagingService() {

    /** Вызывается FCM в фоновом потоке (даже если приложение закрыто, но не принудительно остановлено). */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val container = (applicationContext as App).container
        when (data["type"]) {
            "chapter" -> {
                val item = LatestChapter(
                    id = data["chapter_id"]?.toIntOrNull() ?: return,
                    kind = data["kind"].orEmpty(),
                    volume = data["volume"]?.toIntOrNull() ?: 0,
                    number = data["number"]?.toIntOrNull() ?: 0,
                    title = data["title"].orEmpty(),
                    created = data["created"]?.toLongOrNull() ?: return,
                    type = data["chapter_type"] ?: TYPE_CHAPTER,
                )
                val prefs = runBlocking { container.settings.flow.first() }
                if (!prefs.notifyChapters) return
                // Уже сообщили (фоновой проверкой или главой в списке) — не дублируем
                if (prefs.lastSeen != 0L && item.created <= prefs.lastSeen) return
                Notifications.newChapter(applicationContext, item)
                // Двигаем «последнее виденное», чтобы проверка по расписанию не повторила то же уведомление
                runBlocking { container.settings.advanceLastSeen(item.created) }
            }
            "notice" -> {
                val id = data["id"]?.toIntOrNull() ?: return
                val kind = data["kind"].orEmpty()
                val version = data["version"].orEmpty()
                if (kind == NOTICE_CHANGELOG && !Updates.isNewer(version, BuildConfig.VERSION_NAME)) return
                Notifications.notice(applicationContext, id, kind, version, data["title"].orEmpty(), data["body"].orEmpty())
            }
            "test" -> Notifications.test(applicationContext, data["title"].orEmpty(), data["body"].orEmpty())
        }
    }
}
