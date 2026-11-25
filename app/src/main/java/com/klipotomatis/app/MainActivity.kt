package com.klipotomatis.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private val Bg = Color(0xFF0E0F14)
private val CardBg = Color(0xFF181A22)
private val Accent = Color(0xFF7C5CFF)
private val Muted = Color(0xFFA0A3B1)

class MainActivity : ComponentActivity() {
    private val vm: ClipViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent,
                    background = Bg,
                    surface = CardBg,
                    onSurface = Color.White,
                    onBackground = Color.White
                )
            ) {
                AppScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && i.type == "text/plain") {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val m = Regex("https?://\\S+").find(t) ?: return
            vm.setLink(m.value)
        }
    }
}

private fun queryName(ctx: Context, uri: Uri): String {
    return try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: "video"
    } catch (e: Exception) {
        "video"
    }
}

private fun playClip(ctx: Context, uri: Uri) {
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(ctx, "Tidak ada aplikasi pemutar video.", Toast.LENGTH_SHORT).show()
    }
}

private fun shareClip(ctx: Context, uri: Uri) {
    val i = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Bagikan klip"))
}

@Composable
fun AppScreen(vm: ClipViewModel) {
    val ui = vm.ui
    val settings = vm.settings
    val ctx = LocalContext.current
    val view = LocalView.current
    SideEffect { view.keepScreenOn = ui.running }
    var showSettings by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.setPicked(uri, queryName(ctx, uri))
    }

    Scaffold(containerColor = Bg) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Klip Otomatis", style = MaterialTheme.typography.headlineSmall)
                        Text("Video panjang → klip vertikal siap upload", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Pengaturan")
                    }
                }
            }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Sumber video", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = ui.ytLink,
                            onValueChange = { vm.setLink(it) },
                            enabled = !ui.running,
                            label = { Text("Link YouTube") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !ui.running, onClick = { picker.launch(arrayOf("video/*")) }) {
                                Text("Pilih file video")
                            }
                            if (ui.pickedUri != null) {
                                Text(ui.pickedName, color = Muted, maxLines = 1, modifier = Modifier.weight(1f))
                                TextButton(enabled = !ui.running, onClick = { vm.clearPicked() }) { Text("Hapus") }
                            }
                        }
                        Text(
                            "${settings.clipCount} klip • ${settings.minSec}–${settings.maxSec} dtk • " +
                                (if (settings.high1080) "1080p" else "720p") + " • " +
                                (if (settings.cropFill) "crop tengah" else "muat penuh"),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                if (ui.running) {
                    Button(
                        onClick = { vm.cancel() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))
                    ) { Text("Batalkan") }
                } else {
                    Button(onClick = { vm.start() }, modifier = Modifier.fillMaxWidth()) {
                        Text("✂  CLIP!", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            if (ui.running || ui.step.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ui.step, style = MaterialTheme.typography.bodyMedium)
                            if (ui.running) {
                                if (ui.progress in 0f..1f) {
                                    LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            ui.logs.takeLast(6).forEach {
                                Text(it, color = Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            val err = ui.error
            if (err != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1A1A)), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Terjadi masalah", style = MaterialTheme.typography.titleSmall, color = Color(0xFFFFB4AB))
                            Text(err, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.dismissError() }) { Text("Tutup") }
                        }
                    }
                }
            }

            items(ui.clips) { clip ->
                Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(clip.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Skor ${clip.score} • ${ClipViewModel.fmt(clip.start)}–${ClipViewModel.fmt(clip.end)}",
                            color = Accent,
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (clip.reason.isNotEmpty()) {
                            Text(clip.reason, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { playClip(ctx, clip.uri) }) { Text("Putar") }
                            OutlinedButton(onClick = { shareClip(ctx, clip.uri) }) { Text("Bagikan") }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            initial = settings,
            engineStatus = ui.engineStatus,
            onSave = {
                vm.saveSettings(it)
                showSettings = false
            },
            onDismiss = { showSettings = false },
            onUpdateEngine = { vm.updateEngine() }
        )
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (txt, v) ->
                if (v == selected) {
                    Button(
                        onClick = { onSelect(v) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(txt, maxLines = 1) }
                } else {
                    OutlinedButton(
                        onClick = { onSelect(v) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text(txt, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsDialog(
    initial: AppSettings,
    engineStatus: String,
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
    onUpdateEngine: () -> Unit
) {
    var key by remember { mutableStateOf(initial.geminiKey) }
    var model by remember { mutableStateOf(initial.model) }
    var count by remember { mutableStateOf(initial.clipCount) }
    var range by remember { mutableStateOf(Pair(initial.minSec, initial.maxSec)) }
    var high by remember { mutableStateOf(initial.high1080) }
    var crop by remember { mutableStateOf(initial.cropFill) }
    var caps by remember { mutableStateOf(initial.captionsOn) }
    var titleOn by remember { mutableStateOf(initial.titleOn) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pengaturan") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Gemini API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model Gemini") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Choice("Jumlah klip", listOf("3" to 3, "5" to 5, "8" to 8, "10" to 10), count) { count = it }
                Choice(
                    "Durasi tiap klip",
                    listOf("15–30" to Pair(15, 30), "30–60" to Pair(30, 60), "60–90" to Pair(60, 90)),
                    range
                ) { range = it }
                Choice("Kualitas", listOf("720p (cepat)" to false, "1080p" to true), high) { high = it }
                SwitchRow("Crop tengah (isi layar)", crop) { crop = it }
                SwitchRow("Subtitle otomatis", caps) { caps = it }
                SwitchRow("Judul hook di awal klip", titleOn) { titleOn = it }
                OutlinedButton(onClick = onUpdateEngine, modifier = Modifier.fillMaxWidth()) {
                    Text("Perbarui mesin yt-dlp")
                }
                if (engineStatus.isNotEmpty()) {
                    Text(engineStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    AppSettings(
                        geminiKey = key.trim(),
                        model = model.trim().ifEmpty { "gemini-flash-latest" },
                        clipCount = count,
                        minSec = range.first,
                        maxSec = range.second,
                        high1080 = high,
                        cropFill = crop,
                        captionsOn = caps,
                        titleOn = titleOn
                    )
                )
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
