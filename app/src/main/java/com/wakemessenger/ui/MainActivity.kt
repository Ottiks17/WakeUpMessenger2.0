package com.wakemessenger.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wakemessenger.AppGraph
import com.wakemessenger.core.PowerSaveChecker
import com.wakemessenger.ui.screens.ApiScreen
import com.wakemessenger.ui.screens.ChatListScreen
import com.wakemessenger.ui.screens.ChatScreen
import com.wakemessenger.ui.screens.LogScreen
import com.wakemessenger.ui.screens.SettingsScreen
import com.wakemessenger.ui.theme.WakeUpTheme
import java.net.URLDecoder

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat_jid"
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)

        requestNotificationPermissionIfNeeded()

        // Первый запуск: если исключения не настроены — открываем экран настройки (п. 8.1 ТЗ)
        if (!PowerSaveChecker.check(this).allPassed) {
            startActivity(Intent(this, BatterySetupActivity::class.java))
        }
        AppGraph.startService(this)

        val openChat = intent?.getStringExtra(EXTRA_OPEN_CHAT)

        setContent {
            WakeUpTheme {
                AppNavigation(openChatJid = openChat)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Фабрика ViewModel чата — параметр jid. */
class ChatViewModelFactory(private val jid: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(jid) as T
}

@Composable
fun AppNavigation(openChatJid: String?) {
    val nav = rememberNavController()

    LaunchedEffect(openChatJid) {
        if (!openChatJid.isNullOrBlank()) nav.navigate(Nav.chat(openChatJid))
    }

    NavHost(navController = nav, startDestination = Nav.CHATS) {
        composable(Nav.CHATS) {
            ChatListScreen(
                onOpenChat = { jid -> nav.navigate(Nav.chat(jid)) },
                onOpenSettings = { nav.navigate(Nav.SETTINGS) }
            )
        }
        composable(
            route = Nav.CHAT,
            arguments = listOf(navArgument("jid") { type = NavType.StringType })
        ) { entry ->
            val raw = entry.arguments?.getString("jid").orEmpty()
            val jid = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            ChatScreen(
                jid = jid,
                vm = viewModel(factory = ChatViewModelFactory(jid)),
                onBack = { nav.popBackStack() }
            )
        }
        composable(Nav.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenLog = { nav.navigate(Nav.LOG) },
                onOpenApi = { nav.navigate(Nav.API) }
            )
        }
        composable(Nav.LOG) {
            LogScreen(onBack = { nav.popBackStack() })
        }
        composable(Nav.API) {
            ApiScreen(onBack = { nav.popBackStack() })
        }
    }
}
