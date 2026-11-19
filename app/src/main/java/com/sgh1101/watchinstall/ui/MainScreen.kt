package com.sgh1101.watchinstall.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sgh1101.watchinstall.adb.FoundService
import com.sgh1101.watchinstall.install.ApkFile
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::selectApk)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("워치 설치") },
                actions = {
                    IconButton(onClick = vm::refreshDiscovery) {
                        Icon(Icons.Default.Refresh, contentDescription = "시계 다시 찾기")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionCard(state, vm) }
            item {
                ApkCard(
                    state = state,
                    onPick = {
                        pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
                    },
                )
            }
            item { InstallCard(state, onInstall = vm::install, onLaunch = vm::launchOnWatch) }
            item { HelpCard(initiallyExpanded = !state.pairedBefore) }
        }
    }

    state.dialog?.let { dialog ->
        AddressDialogView(
            dialog = dialog,
            pairing = state.pairing,
            onDismiss = vm::dismissDialog,
            onPair = vm::pair,
            onConnect = vm::connectManual,
        )
    }
}

// -------------------------------------------------------------------- 1. 연결

@Composable
private fun ConnectionCard(state: UiState, vm: MainViewModel) {
    SectionCard(step = 1, title = "시계 연결", icon = Icons.Default.Watch) {
        when (val c = state.connection) {
            is ConnectionState.Connected -> {
                StatusLine(Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary, "${c.info.model} 연결됨")
                Text(
                    buildString {
                        append("${c.host}:${c.port}")
                        if (c.info.androidVersion.isNotEmpty()) append(" · Android ${c.info.androidVersion}")
                        c.info.sdk?.let { append(" (API $it)") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = vm::disconnect) { Text("연결 끊기") }
                return@SectionCard
            }

            is ConnectionState.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("${c.address} 에 연결하는 중…")
                }
            }

            is ConnectionState.Failed -> {
                StatusLine(Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error, c.message)
            }

            ConnectionState.Disconnected -> {
                Text(
                    if (state.pairedBefore) "같은 Wi-Fi 에서 무선 디버깅이 켜진 시계를 찾고 있어요."
                    else "처음이라면 아래 \"처음 설정하기\" 를 따라 시계의 무선 디버깅을 켜 주세요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        if (state.pairingServices.isNotEmpty()) {
            Text("페어링 대기 중인 시계", style = MaterialTheme.typography.labelLarge)
            state.pairingServices.forEach { s ->
                ServiceRow(s, actionLabel = "페어링") { vm.openPairDialog(s) }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (state.connectServices.isNotEmpty()) {
            Text("무선 디버깅이 켜진 시계", style = MaterialTheme.typography.labelLarge)
            state.connectServices.forEach { s ->
                ServiceRow(s, actionLabel = "연결") { vm.connect(s) }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (state.services.isEmpty() && state.discovering) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "같은 Wi-Fi 에서 시계를 찾는 중…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.openPairDialog(null) }) { Text("코드로 페어링") }
            OutlinedButton(onClick = vm::openConnectDialog) { Text("IP로 연결") }
        }
    }
}

@Composable
private fun ServiceRow(service: FoundService, actionLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(service.address, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(
                service.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Button(onClick = onClick) { Text(actionLabel) }
    }
}

// -------------------------------------------------------------------- 2. APK

@Composable
private fun ApkCard(state: UiState, onPick: () -> Unit) {
    SectionCard(step = 2, title = "APK 선택", icon = Icons.Default.FolderOpen) {
        val apk = state.apk
        when {
            state.apkLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("APK 를 읽는 중…")
            }

            state.apkError != null ->
                StatusLine(Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error, state.apkError)

            apk != null -> ApkDetails(apk, watchSdk = (state.connection as? ConnectionState.Connected)?.info?.sdk)

            else -> Text(
                "시계에 설치할 APK 를 골라 주세요. 파일 관리자에서 APK 를 \"공유 → 워치 설치\" 로 보내도 돼요.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = onPick) { Text(if (apk == null) "APK 파일 고르기" else "다른 파일 고르기") }
    }
}

@Composable
private fun ApkDetails(apk: ApkFile, watchSdk: Int?) {
    Text(apk.label ?: apk.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    apk.packageName?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
    Text(
        listOfNotNull(
            apk.versionName?.let { "버전 $it" },
            apk.versionCode?.let { "($it)" },
            formatSize(apk.sizeBytes),
            apk.minSdk?.let { "minSdk $it" },
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    if (apk.isWatchApp) {
        StatusLine(Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary, "워치용 앱이에요")
    } else {
        StatusLine(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "매니페스트에 워치 기능(android.hardware.type.watch)이 없어요. 폰용 앱이면 시계에서 제대로 안 보일 수 있어요.",
        )
    }
    if (watchSdk != null && apk.minSdk != null && apk.minSdk > watchSdk) {
        StatusLine(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.error,
            "이 앱은 API ${apk.minSdk} 이상이 필요한데 시계는 API $watchSdk 예요. 설치되지 않을 거예요.",
        )
    }
}

// -------------------------------------------------------------------- 3. 설치

@Composable
private fun InstallCard(state: UiState, onInstall: () -> Unit, onLaunch: () -> Unit) {
    SectionCard(step = 3, title = "설치", icon = Icons.Default.InstallMobile) {
        when (val i = state.install) {
            InstallState.Idle -> Text(
                when {
                    !state.isConnected -> "먼저 시계를 연결해 주세요."
                    state.apk == null -> "설치할 APK 를 골라 주세요."
                    else -> "준비됐어요!"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            is InstallState.Uploading -> {
                Text("시계로 보내는 중… ${(i.progress * 100).toInt()}%")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { i.progress }, modifier = Modifier.fillMaxWidth())
            }

            InstallState.Installing -> {
                Text("시계에서 설치하는 중…")
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            is InstallState.Succeeded -> {
                StatusLine(Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary, "${i.appName} 설치 완료!")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onLaunch) { Text("시계에서 실행") }
            }

            is InstallState.Failed -> {
                StatusLine(Icons.Default.ErrorOutline, MaterialTheme.colorScheme.error, i.title)
                i.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                i.raw?.let {
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onInstall, enabled = state.canInstall, modifier = Modifier.fillMaxWidth()) {
            Text("시계에 설치")
        }
    }
}

// -------------------------------------------------------------------- 도움말

@Composable
private fun HelpCard(initiallyExpanded: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("처음 설정하기 (갤럭시 워치)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HelpStep(1, "시계 설정 → 시계 정보 → 소프트웨어 정보 → \"소프트웨어 버전\" 을 7번 눌러 개발자 옵션을 켜요.")
                    HelpStep(2, "설정 → 개발자 옵션 에서 \"ADB 디버깅\" 과 \"무선 디버깅\" 을 켜요.")
                    HelpStep(3, "시계를 이 폰과 같은 Wi-Fi 에 연결해요. (설정 → 연결 → Wi-Fi)")
                    HelpStep(4, "무선 디버깅 → \"새 기기 페어링\" 을 누르면 6자리 코드가 나와요. 이 앱에 시계가 나타나면 \"페어링\" 을 눌러 코드를 입력해요.")
                    HelpStep(5, "페어링이 끝나면 자동으로 연결돼요. 다음부터는 무선 디버깅만 켜 두면 바로 연결돼요.")
                    HorizontalDivider()
                    Text(
                        "팁: 설치하는 동안 시계 화면을 켜 두세요. 시계 화면이 꺼지면 Wi-Fi 가 꺼져서 연결이 끊길 수 있어요. " +
                            "무선 디버깅 포트는 시계를 다시 켤 때마다 바뀌어요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpStep(n: Int, text: String) {
    Row {
        Text("$n.", fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// -------------------------------------------------------------------- 입력 창

@Composable
private fun AddressDialogView(
    dialog: AddressDialog,
    pairing: PairingState,
    onDismiss: () -> Unit,
    onPair: (String, String, String) -> Unit,
    onConnect: (String, String) -> Unit,
) {
    val isPair = dialog.mode == AddressDialog.Mode.PAIR
    var host by remember(dialog) { mutableStateOf(dialog.host) }
    var port by remember(dialog) { mutableStateOf(dialog.port) }
    var code by remember(dialog) { mutableStateOf("") }
    val busy = pairing is PairingState.Pairing

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (isPair) "시계와 페어링" else "IP 주소로 연결") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (isPair) "시계의 무선 디버깅 → \"새 기기 페어링\" 화면에 나온 정보를 입력하세요."
                    else "시계의 무선 디버깅 화면 위쪽에 나온 \"IP 주소 및 포트\" 를 입력하세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("IP 주소") },
                    placeholder = { Text("192.168.0.12") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("포트") },
                    placeholder = { Text("37123") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (isPair) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("페어링 코드 (6자리)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
                if (pairing is PairingState.Failed) {
                    Text(pairing.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = { if (isPair) onPair(host, port, code) else onConnect(host, port) },
            ) { Text(if (isPair) "페어링" else "연결") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("취소") }
        },
    )
}

// -------------------------------------------------------------------- 공통

@Composable
private fun SectionCard(step: Int, title: String, icon: ImageVector, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("$step. $title", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
