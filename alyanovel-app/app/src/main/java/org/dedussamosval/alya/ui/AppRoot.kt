package org.dedussamosval.alya.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.dedussamosval.alya.ui.chat.ChatScreen
import org.dedussamosval.alya.ui.chat.ChatViewModel
import org.dedussamosval.alya.ui.home.HomeScreen
import org.dedussamosval.alya.App
import org.dedussamosval.alya.ui.notices.NoticesScreen
import org.dedussamosval.alya.ui.reader.ReaderScreen
import org.dedussamosval.alya.ui.settings.SettingsScreen

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "tabs") {
        composable("tabs") {
            MainTabs(onOpenChapter = { id -> nav.navigate("reader/$id") })
        }
        composable(
            route = "reader/{id}",
            arguments = listOf(navArgument("id") { type = NavType.IntType }),
        ) { entry ->
            val id = entry.arguments?.getInt("id") ?: 0
            ReaderScreen(
                chapterId = id,
                onBack = { nav.popBackStack() },
                // «Следующая/предыдущая глава» заменяют текущий экран читалки, а не наслаиваются
                onOpenChapter = { other ->
                    nav.navigate("reader/$other") { popUpTo("reader/{id}") { inclusive = true } }
                },
            )
        }
    }
    NoticePrompt()
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Главная", Icons.Rounded.Home),
    Tab("Чат", Icons.Rounded.Forum),
    Tab("Уведомления", Icons.Rounded.Notifications),
    Tab("Настройки", Icons.Rounded.Settings),
)

@Composable
private fun MainTabs(onOpenChapter: (Int) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val homeListState = rememberLazyListState() // чтобы позиция списка не терялась при смене вкладки

    // Чат живёт, пока приложение на экране; о новых сообщениях на других вкладках говорит значок
    val chatVm = appViewModel { ChatViewModel(it) }
    val chat by chatVm.state.collectAsState()
    val noticeStore = (LocalContext.current.applicationContext as App).container.notices
    val notices by noticeStore.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { chatVm.setForeground(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { chatVm.setForeground(false) }
    LaunchedEffect(tab) { chatVm.setChatTabVisible(tab == 1) }

    // Когда открыта клавиатура, нижнюю панель прячем — поле ввода чата остаётся над клавиатурой
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!imeVisible) {
                NavigationBar {
                    tabs.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = {
                                val badge = when (index) { 1 -> chat.unread; 2 -> notices.unread.size; else -> 0 }
                                if (badge > 0) {
                                    BadgedBox(badge = { Badge { Text(badge.coerceAtMost(99).toString()) } }) {
                                        Icon(item.icon, contentDescription = item.label)
                                    }
                                } else {
                                    Icon(item.icon, contentDescription = item.label)
                                }
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                0 -> HomeScreen(listState = homeListState, onOpenChapter = onOpenChapter, snackbar = snackbar)
                1 -> ChatScreen(vm = chatVm, snackbar = snackbar)
                2 -> NoticesScreen()
                else -> SettingsScreen(snackbar = snackbar)
            }
        }
    }
}
