package com.aurix.agent.features.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurix.agent.core.ai.routing.ProviderEntry
import com.aurix.agent.core.tools.screen.AurixAccessibilityService
import com.aurix.agent.core.tools.storage.storageAccessGranted
import com.aurix.agent.core.ai.routing.RoutingPolicy
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var budget by remember(ui.dailyBudget) { mutableStateOf(if (ui.dailyBudget == 0L) "" else ui.dailyBudget.toString()) }
    LaunchedEffect(Unit) { while (true) { vm.refreshStatus(); delay(2_000) } }
    val cs = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API keys") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹", color = cs.onBackground) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Paste a key from OpenAI, Anthropic, Gemini, Groq, OpenRouter or xAI. AURIX detects the provider, checks the key and picks the best available model automatically.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        key, { key = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text("Paste API key") }, visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(onClick = { vm.addKey(key, custom); key = "" }, enabled = key.isNotBlank() && !ui.busy) { Text(if (ui.busy) "…" else "Add") }
                }
            }
            item { message?.let { Text(it, color = cs.tertiary, style = MaterialTheme.typography.bodySmall) } }
            items(ui.providers, key = { it.id }) { p -> ProviderBlock(p, status, vm) }
            item { ApprovalAndPermissions(ui.strictApprovals) { vm.setStrict(it) } }
            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Advanced ▾" else "Advanced ▸") } }
            if (advanced) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            custom, { custom = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Custom base URL (only for other OpenAI-compatible providers)") },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = ui.policy == RoutingPolicy.BALANCED, onCheckedChange = { vm.setSaveCost(it) })
                            Text("Save cost: use the faster model for simple steps", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                budget, { budget = it.filter { c -> c.isDigit() } }, singleLine = true, modifier = Modifier.weight(1f),
                                label = { Text("Daily token budget (blank = unlimited)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedButton(onClick = { vm.setBudget(budget.toLongOrNull() ?: 0L) }) { Text("Set") }
                        }
                        Text("Used today: ${ui.todayTokens} tokens", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderBlock(p: ProviderEntry, status: Map<String, String>, vm: SettingsViewModel) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, fontWeight = FontWeight.Bold)
            Text("Auto-selected · best: ${p.strongModel} · fast: ${p.fastModel}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            p.keys.forEach { k ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${k.label}  ••••${k.secret.takeLast(4)}")
                        Text(status[k.id].orEmpty(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.removeKey(p.id, k.id) }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = { vm.recheck(p.id) }) { Text("Re-check models") }
        }
    }
}


private data class Perm(val label: String, val permission: String, val why: String)

private val PERMS = listOf(
    Perm("Send SMS", Manifest.permission.SEND_SMS, "to send texts you ask for (always asks approval)"),
    Perm("Phone calls", Manifest.permission.CALL_PHONE, "to place calls you ask for (always asks approval)"),
    Perm("Contacts", Manifest.permission.READ_CONTACTS, "to find a number by name"),
    Perm("Location", Manifest.permission.ACCESS_FINE_LOCATION, "to tell you where you are / navigate"),
)

@Composable
private fun ApprovalAndPermissions(strict: Boolean, onStrict: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Approvals & phone permissions", style = MaterialTheme.typography.titleSmall, color = cs.primary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = strict, onCheckedChange = onStrict)
            Text("Strict: ask before every phone action (otherwise only SMS, calls and other sensitive actions ask)", style = MaterialTheme.typography.bodySmall)
        }
        PERMS.forEach { p ->
            val granted = remember(tick) { ContextCompat.checkSelfPermission(ctx, p.permission) == PackageManager.PERMISSION_GRANTED }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(p.label); Text(p.why, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                if (granted) Text("✓ granted", color = cs.primary) else OutlinedButton(onClick = { launcher.launch(p.permission) }) { Text("Grant") }
            }
        }
        val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Display over other apps")
                Text("lets AURIX open apps while it works in the background", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            if (overlay) Text("✓ granted", color = cs.primary) else OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            }) { Text("Open") }
        }
        val a11y = remember(tick) { AurixAccessibilityService.enabledInSettings(ctx) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Screen control (Accessibility)")
                Text(
                    "lets AURIX read the screen and tap/type/scroll in other apps. If Android says 'Restricted setting': App info → ⋮ → Allow restricted settings, then enable AURIX here. Never works inside AURIX, banking/UPI/password apps need approval each time.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
            if (a11y) Text("✓ on", color = cs.primary) else OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open") }
        }
        val files = remember(tick) { storageAccessGranted(ctx) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("All files access")
                Text("lets AURIX list, search, zip, move and (after approval) delete files and photos", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            if (files) Text("✓ granted", color = cs.primary) else OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                else launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }) { Text("Grant") }
        }
    }
}
