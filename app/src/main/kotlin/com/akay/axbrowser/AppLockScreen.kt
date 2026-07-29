package com.akay.axbrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.akay.core.ui.security.BiometricAuth

@Composable
fun AppLockScreen(
    viewModel: AppLockViewModel,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val config by viewModel.config.collectAsState()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var triedBiometricThisSession by remember { mutableStateOf(false) }

    fun tryBiometric() {
        if (!config.useBiometric || !BiometricAuth.isAvailable(context)) return
        BiometricAuth.authenticate(
            context = context,
            title = "Unlock AxBrowser",
            onSuccess = {
                viewModel.markUnlocked()
                onUnlocked()
            },
            onFailure = { /* fall back to PIN entry, already visible */ }
        )
    }

    LaunchedEffect(Unit) {
        if (!triedBiometricThisSession) {
            triedBiometricThisSession = true
            tryBiometric()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0B12)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, tint = Color(0xFFB388FF), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("AxBrowser is locked", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(
                                if (index < pin.length) Color(0xFFB388FF) else Color(0xFF2A2433),
                                CircleShape
                            )
                    )
                }
            }
            if (error) {
                Spacer(Modifier.height(8.dp))
                Text("Incorrect PIN", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(32.dp))

            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf(if (config.useBiometric) "bio" else "", "0", "back")
            )
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { key ->
                        Box(
                            modifier = Modifier.size(64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            when (key) {
                                "" -> {}
                                "back" -> IconButton(onClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1) }) {
                                    Icon(Icons.Default.Backspace, "Backspace", tint = Color.White)
                                }
                                "bio" -> IconButton(onClick = { tryBiometric() }) {
                                    Icon(Icons.Default.Fingerprint, "Use biometric", tint = Color(0xFFB388FF))
                                }
                                else -> TextButton(onClick = {
                                    if (pin.length < 4) {
                                        pin += key
                                        error = false
                                        if (pin.length == 4) {
                                            if (viewModel.checkPin(pin)) {
                                                viewModel.markUnlocked()
                                                onUnlocked()
                                            } else {
                                                error = true
                                                pin = ""
                                            }
                                        }
                                    }
                                }) {
                                    Text(key, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
