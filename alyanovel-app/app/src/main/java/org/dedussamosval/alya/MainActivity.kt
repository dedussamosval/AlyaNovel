package org.dedussamosval.alya

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dedussamosval.alya.data.Prefs
import org.dedussamosval.alya.data.TOS_VERSION
import org.dedussamosval.alya.ui.AppRoot
import org.dedussamosval.alya.ui.onboarding.OnboardingScreen
import org.dedussamosval.alya.ui.theme.AlyaNovelTheme

class MainActivity : ComponentActivity() {

    // Системный сплэш держится, пока не прочитаны настройки
    @Volatile private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()          // строго до super.onCreate
        super.onCreate(savedInstanceState)

        splash.setKeepOnScreenCondition { !ready }
        // Страховка: системный сплэш не висит дольше 5 с, даже если диск не ответил
        lifecycleScope.launch { delay(5000); ready = true }

        // Полноэкранную картинку показываем только при холодном старте (не при повороте экрана)
        val coldStart = savedInstanceState == null

        val settings = (application as App).container.settings
        setContent {
            // null — настройки ещё читаются с диска; пока так, ничего не рисуем (иначе у принявших условия
            // на долю секунды мелькнул бы экран первого запуска)
            val prefs: Prefs? by settings.flow.collectAsState(initial = null)
            val scope = rememberCoroutineScope()
            val systemDark = isSystemInDarkTheme()
            var showSplash by rememberSaveable { mutableStateOf(coldStart) }

            if (prefs != null) SideEffect { ready = true }
            // Настройки прочитаны → картинка держится ещё 1.5 с и растворяется
            LaunchedEffect(prefs != null) {
                if (prefs != null && showSplash) {
                    delay(1000)
                    showSplash = false
                }
            }

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
                Box(Modifier.fillMaxSize()) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val p = prefs
                        when {
                            p == null -> Unit
                            // Условия не приняты (или вышла новая редакция) — экран первого запуска
                            p.tosAccepted != TOS_VERSION -> OnboardingScreen(
                                onAccept = { notify -> scope.launch { settings.acceptTos(TOS_VERSION, notify) } },
                            )
                            else -> AppRoot()           // Иначе запускаем главное меню
                        }
                    }
                    AnimatedVisibility(visible = showSplash, exit = fadeOut(tween(500))) {
                        FullScreenSplash()
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF14141C))   // тот же цвет, что splash_bg и фон картинки
            // пока сплэш на экране, тапы не проваливаются в интерфейс под ним
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.splash),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(0.8f)      // 80% ширины экрана: ничего не обрезается
                .widthIn(max = 520.dp),  // на планшетах не раздувается
        )
    }
}