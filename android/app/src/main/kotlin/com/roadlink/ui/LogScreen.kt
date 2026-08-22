package com.roadlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The run log.
 *
 * Every delivery decision is written here in plain language, including the
 * ones that failed. Lines produced by the simulator say SIMULATED explicitly -
 * a log that reads the same whether a relay was real or imaginary would make
 * the distinction impossible to audit after the fact.
 *
 * Also mirrored to logcat under the tag RLINK.
 */
@Composable
fun LogScreen(vm: RoadLinkViewModel) {
    val lines by vm.log.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SectionLabel("Run log  ·  logcat tag: RLINK")
        if (lines.isEmpty()) {
            EmptyState("Nothing logged yet.")
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        line.contains("SIMULATED") -> RlViolet
                        line.contains("DELIVERED") -> RlGreen
                        line.contains("FAILED") || line.contains("ERROR") -> RlOrange
                        line.contains("PERSISTED") -> RlBlue
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                )
            }
        }
    }
}
