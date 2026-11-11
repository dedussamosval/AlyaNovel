package org.dedussamosval.alya.data

import org.dedussamosval.alya.BuildConfig

object Notices {
    fun relevant(n: Notice) =
        n.kind != NOTICE_CHANGELOG || Updates.isNewer(n.version, BuildConfig.VERSION_NAME)

    /** Блокирующая, вызывать из Dispatchers.IO. Докачивает только то, чего ещё нет в архиве. Возвращает ссылку на APK. */
    fun sync(store: NoticeStore, api: Api): String {
        val fresh = !store.initialized
        val batch = api.notices(after = store.maxId(), limit = 50)
        store.merge(batch.items, readUpTo = if (fresh) batch.top else null)
        store.downloadUrl = batch.url
        return batch.url
    }
}