package com.akay.feature.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akay.core.ui.components.GalaxyBackground
import com.akay.core.ui.theme.LocalAccentColor

private data class Shortcut(val label: String, val url: String, val icon: ImageVector)

@Composable
fun NewTabPage(onSearch: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val accent = LocalAccentColor.current

    val shortcuts = listOf(
        Shortcut("YouTube", "https://m.youtube.com", Icons.Outlined.PlayCircle),
        Shortcut("Reddit", "https://www.reddit.com", Icons.Outlined.Forum),
        Shortcut("Wikipedia", "https://www.wikipedia.org", Icons.Outlined.Public),
        Shortcut("GitHub", "https://github.com", Icons.Outlined.Bookmark)
    )

    GalaxyBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 4 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Wordmark with accent glow
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .background(
                                    Brush.radialGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent)),
                                    CircleShape
                                )
                        )
                        Text("Ax", style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Black, letterSpacing = (-1).sp), color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "AxBrowser",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        "Fast · Private · Yours",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f)
                    )

                    Spacer(Modifier.height(40.dp))

                    // Search pill
                    Surface(
                        shape = RoundedCornerShape(30.dp),
                        color = Color.White.copy(alpha = 0.06f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.12f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Search, null, tint = accent, modifier = Modifier.size(20.dp))
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search or enter address", color = Color.White.copy(0.4f)) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = accent
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { if (query.isNotBlank()) onSearch(query) })
                            )
                        }
                    }

                    Spacer(Modifier.height(28.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        shortcuts.forEach { s ->
                            ShortcutTile(
                                shortcut = s,
                                accent = accent,
                                modifier = Modifier.weight(1f),
                                onClick = { onSearch(s.url) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutTile(
    shortcut: Shortcut,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "s")

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = RoundedCornerShape(18.dp),
            color = Color.White.copy(alpha = 0.06f),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
            modifier = Modifier.size(56.dp).scale(scale)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(shortcut.icon, shortcut.label, tint = accent, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(shortcut.label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(0.7f),
            textAlign = TextAlign.Center, maxLines = 1)
    }
}
