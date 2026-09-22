package com.cleaner.filter

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cleaner.filter.dns.DnsVpnController
import com.cleaner.filter.service.CaptureForegroundService
import com.cleaner.filter.settings.AudioMode
import com.cleaner.filter.settings.DnsProvider
import com.cleaner.filter.ui.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CleanerSettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerSettingsScreen(vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    var pinInput by remember { mutableStateOf("") }
    var pinUnlocked by remember { mutableStateOf(settings.parentalPinHash == null) }

    androidx.compose.runtime.LaunchedEffect(settings) {
        FilterEngine.updateSettings(context, settings)
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureForegroundService.start(context, result.resultCode, result.data!!)
            vm.setFilterEnabled(true)
        }
    }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val vpnPrepare = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && settings.dnsEnabled) {
            DnsVpnController.start(context, settings.dnsProvider)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Cleaner") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "On-device live filter. Touches pass through; you see a delayed, filtered mirror.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (!pinUnlocked) {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("Parental PIN") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    vm.verifyPin(pinInput) { ok ->
                        if (ok) pinUnlocked = true
                    }
                }) { Text("Unlock settings") }
                return@Column
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK,
                            ),
                        )
                    }) { Text("Open accessibility settings") }

                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            val mgr = context.getSystemService(MediaProjectionManager::class.java)
                            projectionLauncher.launch(mgr.createScreenCaptureIntent())
                        },
                    ) { Text("Start filter") }

                    Button(onClick = {
                        CaptureForegroundService.stop(context)
                        vm.setFilterEnabled(false)
                    }) { Text("Stop filter") }
                }
            }

            FilterToggle("Visual nudity filter", settings.visualNudityEnabled, vm::setVisualNudity)
            Text("Score threshold ${"%.0f".format(settings.visualSensitivity * 100)}%")
            Slider(
                value = settings.visualSensitivity.coerceIn(0.2f, 0.8f),
                onValueChange = vm::setVisualSensitivity,
                valueRange = 0.2f..0.8f,
            )

            FilterToggle("Text filter", settings.textFilterEnabled, vm::setTextFilter)
            FilterToggle("Audio filter", settings.audioFilterEnabled, vm::setAudioFilter)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Synced delayed audio")
                Switch(
                    checked = settings.audioMode == AudioMode.SYNCED_DELAY,
                    onCheckedChange = {
                        vm.setAudioMode(if (it) AudioMode.SYNCED_DELAY else AudioMode.LEAKY_REALTIME)
                    },
                )
            }

            Text("Presentation delay ${settings.presentationDelayMs} ms")
            Slider(
                value = settings.presentationDelayMs.toFloat(),
                onValueChange = { vm.setPresentationDelay(it.toLong()) },
                valueRange = 33f..120f,
            )

            FilterToggle("DNS filter (VPN)", settings.dnsEnabled) { enabled ->
                vm.setDnsEnabled(enabled)
                if (enabled) {
                    val prep = DnsVpnController.prepareIntent(context)
                    if (prep != null) vpnPrepare.launch(prep) else {
                        DnsVpnController.start(context, settings.dnsProvider)
                    }
                } else {
                    DnsVpnController.stop(context)
                }
            }

            DnsProvider.entries.filter { it != DnsProvider.OFF }.forEach { provider ->
                Button(onClick = { vm.setDnsProvider(provider) }) {
                    Text(
                        if (settings.dnsProvider == provider) "✓ ${provider.label}" else provider.label,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Private DNS (no VPN conflict)", style = MaterialTheme.typography.titleSmall)
                    Text(DnsVpnController.privateDnsInstructions(settings.dnsProvider))
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Parental PIN", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { pinInput = it },
                        label = { Text("New PIN") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (pinInput.length >= 4) vm.setPin(pinInput) }) {
                            Text("Set PIN")
                        }
                        Button(onClick = vm::clearPin) { Text("Clear PIN") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FilterToggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
