package org.dedussamosval.alya

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.dedussamosval.alya.data.Prefs
import org.dedussamosval.alya.data.TOS_VERSION
import org.dedussamosval.alya.ui.AppRoot
import org.dedussamosval.alya.ui.onboarding.OnboardingScreen
import org.dedussamosval.alya.ui.theme.AlyaNovelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as App).container.settings
        setContent {
            // null — настройки ещё читаются с диска; пока так, ничего не рисуем (иначе у принявших условия
            // на долю секунды мелькнул бы экран первого запуска)
            val prefs: Prefs? by settings.flow.collectAsState(initial = null)
            val scope = rememberCoroutineScope()
            val systemDark = isSystemInDarkTheme()
            val dark = when (prefs?.theme) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            // Иконки в статус-баре/навигации подстраиваются под выбранную тему, а не только под системную
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                )
                onDispose { }
            }
            AlyaNovelTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val p = prefs
                    when {
                        p == null -> Unit
                        // Условия не приняты (или вышла новая редакция) — экран первого запуска
                        p.tosAccepted != TOS_VERSION -> OnboardingScreen(
                            onAccept = { notify -> scope.launch { settings.acceptTos(TOS_VERSION, notify) } },
                        )
                        else -> AppRoot()
                    }
                }
            }
        }
    }
}
