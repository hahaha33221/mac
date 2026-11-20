package com.example.sshremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.sshremote.ui.screens.HostListScreen
import com.example.sshremote.ui.screens.SnippetScreen
import com.example.sshremote.ui.theme.SshRemoteTheme
import com.example.sshremote.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as SshRemoteApp).repository
        val factory = viewModelFactory { initializer { MainViewModel(repository) } }

        setContent {
            SshRemoteTheme {
                val vm: MainViewModel = viewModel(factory = factory)
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "hosts") {
                    composable("hosts") {
                        HostListScreen(vm = vm, onOpenHost = { id -> nav.navigate("host/$id") })
                    }
                    composable(
                        "host/{hostId}",
                        arguments = listOf(navArgument("hostId") { type = NavType.LongType }),
                    ) { entry ->
                        val hostId = entry.arguments?.getLong("hostId") ?: return@composable
                        SnippetScreen(vm = vm, hostId = hostId, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
