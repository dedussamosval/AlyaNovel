package org.dedussamosval.alya.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import org.dedussamosval.alya.App
import org.dedussamosval.alya.MainActivity
import org.dedussamosval.alya.R
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val CHANNEL_CHAPTERS = "chapters"
private const val WORK_NAME = "new_chapters"
private const val CHANNEL_UPDATES = "updates"
private const val CHANNEL_IMPORTANT = "important"

object Notifications {

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CHAPTERS, "Новые главы", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Сообщения о выходе новых глав" })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Новая версия AlyaNovel" })
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_IMPORTANT, "Важные уведомления", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Важные сообщения от автора" })
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun describe(item: LatestChapter): String {
        val kind = if (item.kind == KIND_RANOBE) "Ранобэ" else "Манга"
        return "$kind · том ${item.volume}, ${chapterLabel(item.type, item.number).lowercase()}"
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, title: String, text: String, channel: String = CHANNEL_CHAPTERS) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_alya_chibi)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    /** Сводка из фоновой проверки: одно уведомление «последняя глава и ещё N». */
    fun newChapters(context: Context, items: List<LatestChapter>) {
        if (items.isEmpty()) return
        post(context, 1001, "Новая глава", describe(items.last()) + if (items.size > 1) " и ещё ${items.size - 1}" else "")
    }

    /** Мгновенное уведомление (push): по одному на главу, поэтому несколько подряд не затирают друг друга. */
    fun newChapter(context: Context, item: LatestChapter) {
        val text = describe(item) + if (item.title.isNotBlank()) " · ${item.title}" else ""
        post(context, 2000 + item.id, "Новая глава", text)
    }

    fun notice(context: Context, id: Int, kind: String, version: String, title: String, body: String) {
        if (kind == NOTICE_CHANGELOG) {
            post(context, 4000 + id, "Доступна новая версия", "Версия $version", CHANNEL_UPDATES)
        } else {
            post(context, 4000 + id, title.ifBlank { "AlyaNovel" }, body, CHANNEL_IMPORTANT)
        }
    }

    /** Проверочное уведомление: python -m server.push */
    fun test(context: Context, title: String, body: String) {
        post(context, 1999, title.ifBlank { "Тест" }, body)
    }
}

/** Раз в ~15 минут (минимум WorkManager) спрашивает сервер, не вышли ли главы новее, чем «последнее виденное». */
class NewChaptersWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as App).container
        val prefs = c.settings.flow.first()
        if (!prefs.notifyChapters) return Result.success()
        if (prefs.lastSeen == 0L) {   // первый запуск: ничего не сообщаем о старом, просто запоминаем «сейчас»
            c.settings.advanceLastSeen(System.currentTimeMillis() / 1000)
            return Result.success()
        }
        return try {
            val items = c.api.latest(prefs.lastSeen)
            if (items.isNotEmpty()) {
                Notifications.newChapters(applicationContext, items)
                c.settings.advanceLastSeen(items.maxOf { it.created })
            }
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: Exception) {
            Result.success()
        }
    }

    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<NewChaptersWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
