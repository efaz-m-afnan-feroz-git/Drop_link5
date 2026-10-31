package app.droplink.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droplink.BuildConfig
import app.droplink.R
import app.droplink.core.protocol.ConnectionMode
import app.droplink.core.security.Fingerprints
import app.droplink.data.ThemeMode
import app.droplink.di.AppContainer
import app.droplink.transfer.SelfTest
import app.droplink.ui.components.SectionTitle
import app.droplink.ui.components.label
import app.droplink.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val trustedFlow = remember { container.database.trusted().observe() }
    val trusted by trustedFlow.collectAsStateWithLifecycle(emptyList())
    var editName by remember { mutableStateOf(false) }
    var selfTest by remember { mutableStateOf<String?>(null) }
    var selfTestRunning by remember { mutableStateOf(false) }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            container.settings.setSaveTree(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle(stringResource(R.string.settings_section_device))
            ListItem(
                modifier = Modifier.selectable(selected = false, role = Role.Button, onClick = { editName = true }),
                leadingContent = { Icon(Icons.Outlined.Badge, null) },
                headlineContent = { Text(stringResource(R.string.settings_device_name)) },
                supportingContent = { Text(settings.deviceName) },
            )
            val treeName = settings.saveTreeUri?.let { u ->
                runCatching { DocumentFile.fromTreeUri(context, Uri.parse(u))?.name }.getOrNull() ?: u
            }
            ListItem(
                modifier = Modifier.selectable(selected = false, role = Role.Button, onClick = { pickTree.launch(null) }),
                leadingContent = { Icon(Icons.Outlined.Folder, null) },
                headlineContent = { Text(stringResource(R.string.settings_save_location)) },
                supportingContent = { Text(treeName ?: stringResource(R.string.settings_save_default)) },
                trailingContent = {
                    if (settings.saveTreeUri != null) {
                        TextButton(onClick = {
                            settings.saveTreeUri?.let { u ->
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                    )
                                }
                            }
                            container.settings.setSaveTree(null)
                        }) { Text(stringResource(R.string.action_reset)) }
                    }
                },
            )
            Text(stringResource(R.string.settings_save_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))

            SectionTitle(stringResource(R.string.settings_section_trusted))
            ListItem(
                leadingContent = { Icon(Icons.Outlined.VerifiedUser, null) },
                headlineContent = { Text(stringResource(R.string.settings_auto_accept)) },
                supportingContent = { Text(stringResource(R.string.settings_auto_accept_hint)) },
                trailingContent = { Switch(checked = settings.autoAcceptTrusted, onCheckedChange = { container.settings.setAutoAcceptTrusted(it) }) },
            )
            if (trusted.isEmpty()) {
                Text(stringResource(R.string.settings_no_trusted), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
            trusted.forEach { d ->
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(stringResource(R.string.settings_key, Fingerprints.short(d.fingerprint))) },
                    trailingContent = {
                        IconButton(onClick = { scope.launch(Dispatchers.IO) { container.database.trusted().delete(d.deviceId) } }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.settings_untrust, d.name))
                        }
                    },
                )
            }

            SectionTitle(stringResource(R.string.settings_section_connection))
            Column(Modifier.selectableGroup()) {
                listOf<ConnectionMode?>(null, ConnectionMode.USB, ConnectionMode.WIFI_DIRECT, ConnectionMode.LAN, ConnectionMode.QR_HOTSPOT).forEach { mode ->
                    RadioRow(mode.label(), settings.forcedMode == mode) { container.settings.setForcedMode(mode) }
                }
            }
            Text(stringResource(R.string.settings_mode_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))

            SectionTitle(stringResource(R.string.settings_section_appearance))
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp))
            Column(Modifier.selectableGroup()) {
                RadioRow(stringResource(R.string.theme_system), settings.theme == ThemeMode.SYSTEM) { container.settings.setTheme(ThemeMode.SYSTEM) }
                RadioRow(stringResource(R.string.theme_light), settings.theme == ThemeMode.LIGHT) { container.settings.setTheme(ThemeMode.LIGHT) }
                RadioRow(stringResource(R.string.theme_dark), settings.theme == ThemeMode.DARK) { container.settings.setTheme(ThemeMode.DARK) }
            }
            Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            Column(Modifier.selectableGroup()) {
                RadioRow(stringResource(R.string.language_system), currentLang.isEmpty()) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                }
                RadioRow("English", currentLang.startsWith("en")) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                }
                RadioRow("বাংলা", currentLang.startsWith("bn")) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("bn"))
                }
            }

            SectionTitle(stringResource(R.string.settings_section_reliability))
            ListItem(
                modifier = Modifier.selectable(selected = false, role = Role.Button, onClick = onSetup),
                leadingContent = { Icon(Icons.Outlined.BatterySaver, null) },
                headlineContent = { Text(stringResource(R.string.setup_title)) },
                supportingContent = { Text(stringResource(R.string.settings_setup_hint)) },
            )
            ListItem(
                modifier = Modifier.selectable(selected = false, role = Role.Button, enabled = !selfTestRunning, onClick = {
                    selfTestRunning = true
                    selfTest = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { SelfTest.run(context, container.identity, container.settings.current.saveTreeUri) }
                        selfTest = if (r.ok) {
                            context.getString(R.string.selftest_ok, Format.megabytesPerSecond(context, r.bytesPerSecond))
                        } else {
                            context.getString(R.string.selftest_failed, r.detail ?: "?")
                        }
                        selfTestRunning = false
                    }
                }),
                leadingContent = { Icon(Icons.Outlined.Science, null) },
                headlineContent = { Text(stringResource(R.string.selftest_title)) },
                supportingContent = { Text(selfTest ?: stringResource(R.string.selftest_hint)) },
            )
            if (selfTestRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            ListItem(
                modifier = Modifier.selectable(selected = false, role = Role.Button, onClick = { app.droplink.ui.components.copyDiagLog(context) }),
                leadingContent = { Icon(Icons.Outlined.BugReport, null) },
                headlineContent = { Text(stringResource(R.string.diag_copy)) },
                supportingContent = { Text(stringResource(R.string.diag_copy_hint)) },
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            val fingerprint = remember { runCatching { container.identity.get().fingerprint }.getOrNull() }
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Info, null) },
                headlineContent = { Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME)) },
                supportingContent = {
                    Text(
                        stringResource(R.string.about_body) +
                            (fingerprint?.let { "\n" + stringResource(R.string.settings_key, Fingerprints.short(it)) } ?: ""),
                    )
                },
            )
        }
    }

    if (editName) {
        var value by remember { mutableStateOf(settings.deviceName) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text(stringResource(R.string.settings_device_name)) },
            text = {
                OutlinedTextField(value = value, onValueChange = { value = it.take(40) }, singleLine = true, label = { Text(stringResource(R.string.settings_device_name_hint)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    container.settings.setDeviceName(value)
                    editName = false
                }, enabled = value.isNotBlank()) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editName = false }) { Text(stringResource(R.string.action_not_now)) } },
        )
    }
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, Modifier.padding(start = 16.dp))
    }
}
