package org.dedussamosval.alya.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import org.dedussamosval.alya.BuildConfig
import org.dedussamosval.alya.data.Notifications
import org.dedussamosval.alya.ui.components.Hero
import org.dedussamosval.alya.ui.components.Pill
import org.dedussamosval.alya.ui.components.PrimaryButton
import org.dedussamosval.alya.ui.components.SiteCard
import org.dedussamosval.alya.ui.components.openUrl

/**
 * Экран первого запуска (и повторного — если поменялась редакция условий, см. TOS_VERSION):
 *  1. принять Пользовательское соглашение и Политику конфиденциальности (без галочки «Продолжить» неактивна);
 *  2. разрешить уведомления (только Android 13+, необязательно).
 *
 * [onAccept] получает true, если уведомления о новых главах можно включить (разрешение выдано или не требуется).
 */
@Composable
fun OnboardingScreen(onAccept: (notificationsEnabled: Boolean) -> Unit) {
    val context = LocalContext.current
    val serverUrl = BuildConfig.SERVER_URL.trimEnd('/')
    val needsNotificationPermission = Build.VERSION.SDK_INT >= 33

    var accepted by rememberSaveable { mutableStateOf(false) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var granted by remember { mutableStateOf(Notifications.canPost(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
    }
    // Вернулись из системных настроек — перепроверяем, дали ли разрешение
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = Notifications.canPost(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                PrimaryButton(
                    text = "Продолжить",
                    onClick = { onAccept(granted || !needsNotificationPermission) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = accepted,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Hero(title = "AlyaNovel", subtitle = "Добро пожаловать! Два коротких шага перед началом.")

            // ── Шаг 1: условия использования ──
            SiteCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. Условия использования", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "AlyaNovel — некоммерческий проект без рекламы. Материалы — неофициальный любительский перевод. " +
                            "Пожалуйста, ознакомьтесь с документами:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Pill("Пользовательское соглашение", onClick = { context.openUrl("$serverUrl/tos") })
                    Pill("Политика конфиденциальности", onClick = { context.openUrl("$serverUrl/privacy") })
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = accepted, role = Role.Checkbox, onValueChange = { accepted = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = accepted, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Я прочитал(а) и принимаю Пользовательское соглашение и Политику конфиденциальности",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ── Шаг 2: уведомления (на Android 12 и старше разрешение не нужно) ──
            if (needsNotificationPermission) {
                SiteCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("2. Уведомления о новых главах", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(
                            "Чтобы сообщать о выходе новых глав, приложению нужно разрешение на уведомления. " +
                                "Это необязательно — позже их можно включить в настройках.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (granted) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text("Уведомления разрешены", style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            PrimaryButton(
                                text = "Разрешить уведомления",
                                onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            )
                            if (asked) {
                                Text(
                                    "Разрешение не выдано. Включить уведомления можно позже в настройках системы.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
