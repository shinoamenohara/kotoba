package app.kotoba.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.data.Achievement
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.LabeledProgress
import app.kotoba.core.ui.components.ProgressRing
import app.kotoba.core.ui.components.achievementDescription
import app.kotoba.core.ui.components.achievementTitle
import java.text.DateFormat
import java.util.Date

@Composable
fun AchievementsScreen(onBack: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val p = state.profile
    Scaffold(topBar = { KotobaTopBar(stringResource(R.string.home_achievements), onBack) }) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (p != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProgressRing(p.level.progress, size = 72.dp) {
                                Text("${p.level.level}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.home_level, p.level.level), style = MaterialTheme.typography.titleMedium)
                                Text("${p.level.totalXp} XP · 🔥 ${p.streak}", style = MaterialTheme.typography.bodyMedium)
                                LabeledProgress(stringResource(R.string.home_next_level), p.level.xpIntoLevel, p.level.xpForLevel)
                            }
                        }
                    }
                }
            }
            items(Achievement.entries) { a ->
                val unlockedAt = p?.unlocked?.get(a)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (unlockedAt != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(14.dp).alpha(if (unlockedAt != null) 1f else 0.55f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (unlockedAt != null) a.icon else "🔒", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(6.dp))
                        Text(achievementTitle(a), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                        Text(achievementDescription(a), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (unlockedAt != null) {
                            Text(
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(unlockedAt)),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
