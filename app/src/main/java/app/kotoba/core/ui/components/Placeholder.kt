package app.kotoba.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Temporary screen used while a feature module is being built. */
@Composable
fun PlaceholderScreen(title: String, onBack: (() -> Unit)? = null) {
    Scaffold(topBar = { KotobaTopBar(title, onBack) }) { p ->
        Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { Text(title) }
    }
}
