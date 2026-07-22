package app.vellum.reader.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.vellum.reader.VellumApp
import app.vellum.reader.core.data.ReadingSessionEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class BookTime(val bookUuid: String, val title: String, val ms: Long)

data class InsightsState(
    val streakDays: Int = 0,
    val todayMs: Long = 0,
    val weekMs: Long = 0,
    val totalMs: Long = 0,
    val totalPages: Int = 0,
    val pagesPerHour: Int? = null,
    val perBook: List<BookTime> = emptyList(),
    val yearMs: Long = 0,
    val yearlyGoalMinutes: Int = 0,
)

/** Derives every stat from raw session rows — nothing is stored precomputed. */
class InsightsViewModel(private val app: VellumApp) : ViewModel() {

    val state = combine(
        app.sessionDao.observeAll(),
        app.bookDao.observeShelf(),
        app.settingsStore.settings,
    ) { sessions, books, settings ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        val readingDays = sessions.map { dayOf(it.startedAt) }.toSet()
        var streak = 0
        var cursor = today
        // Today counts if read today; otherwise the streak may still be alive from yesterday.
        if (cursor !in readingDays) cursor = cursor.minusDays(1)
        while (cursor in readingDays) {
            streak++
            cursor = cursor.minusDays(1)
        }

        val weekStart = today.minusDays(6)
        val todayMs = sessions.filter { dayOf(it.startedAt) == today }.sumOf(ReadingSessionEntity::msRead)
        val weekMs = sessions.filter { dayOf(it.startedAt) >= weekStart }.sumOf(ReadingSessionEntity::msRead)
        val totalMs = sessions.sumOf(ReadingSessionEntity::msRead)
        val yearMs = sessions.filter {
            Instant.ofEpochMilli(it.startedAt).atZone(zone).year == today.year
        }.sumOf(ReadingSessionEntity::msRead)
        val totalPages = sessions.sumOf(ReadingSessionEntity::pagesTurned)
        val pagesPerHour =
            if (totalMs > 10 * 60_000L && totalPages > 0) ((totalPages * 3_600_000.0) / totalMs).toInt()
            else null

        val titles = books.associateBy({ it.uuid }, { it.title })
        val perBook = sessions.groupBy { it.bookUuid }
            .mapNotNull { (uuid, rows) -> titles[uuid]?.let { BookTime(uuid, it, rows.sumOf(ReadingSessionEntity::msRead)) } }
            .sortedByDescending { it.ms }
            .take(8)

        InsightsState(
            streak, todayMs, weekMs, totalMs, totalPages, pagesPerHour, perBook,
            yearMs, settings.yearlyGoalMinutes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())

    fun setYearlyGoalHours(hours: Int) {
        viewModelScope.launch { app.settingsStore.setYearlyGoalMinutes(hours.coerceAtLeast(0) * 60) }
    }
}

/** Reading insights — presented as quiet numbers, never as a game. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(onBack: (() -> Unit)? = null) {
    val app = LocalContext.current.applicationContext as VellumApp
    val viewModel: InsightsViewModel = viewModel { InsightsViewModel(app) }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading", fontFamily = FontFamily.Serif) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 1000.dp).fillMaxWidth().fillMaxHeight().align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    StatCard("Streak", if (state.streakDays > 0) "${state.streakDays}d" else "—", Modifier.weight(1f))
                    StatCard("Today", formatDuration(state.todayMs), Modifier.weight(1f))
                    StatCard("This week", formatDuration(state.weekMs), Modifier.weight(1f))
                }
            }
            item {
                YearGoalCard(
                    yearMs = state.yearMs,
                    goalMinutes = state.yearlyGoalMinutes,
                    onGoalHours = viewModel::setYearlyGoalHours,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    StatCard("All time", formatDuration(state.totalMs), Modifier.weight(1f))
                    StatCard("Pages turned", "${state.totalPages}", Modifier.weight(1f))
                    StatCard("Pages/hour", state.pagesPerHour?.toString() ?: "—", Modifier.weight(1f))
                }
            }
            if (state.perBook.isNotEmpty()) {
                item {
                    Text(
                        "Time by book",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                items(state.perBook, key = { it.bookUuid }) { book ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            book.title,
                            fontFamily = FontFamily.Serif,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatDuration(book.ms),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            if (state.totalMs == 0L) {
                item {
                    Text(
                        "Numbers appear after your first proper sitting (30 seconds or more).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun YearGoalCard(
    yearMs: Long,
    goalMinutes: Int,
    onGoalHours: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Yearly reading goal", style = MaterialTheme.typography.titleSmall)
            if (goalMinutes > 0) {
                val goalMs = goalMinutes * 60_000L
                val percent = (yearMs.toDouble() / goalMs).coerceIn(0.0, 1.0).toFloat()
                Text("${formatDuration(yearMs)} of ${goalMinutes / 60}h")
                LinearProgressIndicator(progress = { percent }, modifier = Modifier.fillMaxWidth())
            } else {
                Text(
                    "Choose a quiet annual target. Progress counts active reading time only.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(50, 100, 200).forEach { hours ->
                    TextButton(onClick = { onGoalHours(hours) }) { Text("${hours}h") }
                }
                if (goalMinutes > 0) TextButton(onClick = { onGoalHours(0) }) { Text("Off") }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "—"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
