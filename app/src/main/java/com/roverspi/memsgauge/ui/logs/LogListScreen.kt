package com.roverspi.memsgauge.ui.logs

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.roverspi.memsgauge.R
import com.roverspi.memsgauge.logging.LogFileEntry
import com.roverspi.memsgauge.ui.LanguageToggleButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogListScreen(onBack: () -> Unit, onOpenChart: (LogFileEntry) -> Unit) {
    val context = LocalContext.current
    val viewModel: LogListViewModel = viewModel(factory = LogListViewModel.Factory(context.applicationContext))
    val files by viewModel.files.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                actions = { LanguageToggleButton() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                stringResource(R.string.logs_folder_note),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            if (files.isEmpty()) {
                Text(
                    stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(files) { file -> LogFileRow(file, onOpenChart = { onOpenChart(file) }) }
                }
            }

            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { viewModel.refresh() }) { Text(stringResource(R.string.action_refresh)) }
                Button(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
        }
    }
}

@Composable
private fun LogFileRow(file: LogFileEntry, onOpenChart: () -> Unit) {
    val context = LocalContext.current
    val dateText = remember(file.lastModifiedMs) {
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(file.lastModifiedMs))
    }
    val sizeText = "%.1f KB".format(file.sizeBytes / 1024.0)

    Card(modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(file.displayName, style = MaterialTheme.typography.bodyLarge)
            Text("$dateText ・ $sizeText", style = MaterialTheme.typography.labelMedium)
            Row {
                TextButton(onClick = onOpenChart) { Text(stringResource(R.string.logs_view_chart)) }
                val shareChooserTitle = stringResource(R.string.logs_share_chooser_title)
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, file.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, shareChooserTitle))
                }) {
                    Text(stringResource(R.string.action_share))
                }
            }
        }
    }
}
