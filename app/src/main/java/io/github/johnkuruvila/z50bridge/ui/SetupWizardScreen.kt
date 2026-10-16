package io.github.johnkuruvila.z50bridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.johnkuruvila.z50bridge.R
import io.github.johnkuruvila.z50bridge.data.BridgeSettings
import io.github.johnkuruvila.z50bridge.ui.common.AppToasts
import io.github.johnkuruvila.z50bridge.ui.components.PipelineDiagram
import io.github.johnkuruvila.z50bridge.ui.components.SwitchItem
import io.github.johnkuruvila.z50bridge.ui.components.ListGroup
import io.github.johnkuruvila.z50bridge.ui.theme.Numeric
import io.github.johnkuruvila.z50bridge.ui.theme.signals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val STEP_WELCOME = 0
private const val STEP_HOMELAB = 1
private const val STEP_CAMERA = 2
private const val STEP_DONE = 3
private const val STEP_COUNT = 4

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SetupWizardScreen(
    settingsFlow: Flow<BridgeSettings>,
    onTest: suspend (BridgeSettings) -> String,
    onSave: suspend (BridgeSettings) -> Unit,
    onBack: () -> Unit,
    initialStep: Int = STEP_WELCOME,
) {
    var draft by remember { mutableStateOf<BridgeSettings?>(null) }
    LaunchedEffect(Unit) { draft = settingsFlow.first() }
    val settings = draft ?: return
    var step by remember { mutableIntStateOf(initialStep) }
    var testing by remember { mutableStateOf(false) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val connectionFailed = stringResource(R.string.settings_connection_failed)

    val portValid = settings.destPort in 1..65535
    val hostValid = settings.destHost.isNotBlank()

    fun back() = if (step == STEP_WELCOME) onBack() else step--
    BackHandler { back() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::back) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            StepIndicator(step = step, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text(stringResource(R.string.setup_not_now)) }
        }

        AnimatedContent(
            targetState = step,
            // Steps switch in place, like the app's screens.
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            label = "step",
            modifier = Modifier.weight(1f),
        ) { current ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (current) {
                    STEP_WELCOME -> WelcomeStep()
                    STEP_HOMELAB -> {
                        StepHeader(
                            title = stringResource(R.string.setup_homelab_title),
                            body = stringResource(R.string.setup_header_body),
                        )
                        WizardField(
                            label = stringResource(R.string.setup_host),
                            value = settings.destHost,
                            keyboardType = KeyboardType.Uri,
                            isError = !hostValid,
                            supporting = stringResource(if (hostValid) R.string.setup_host_help else R.string.setup_host_error),
                        ) {
                            draft = settings.copy(destHost = it.trim())
                            testOk = null
                        }
                        WizardField(
                            label = stringResource(R.string.setup_port),
                            value = settings.destPort.toString(),
                            keyboardType = KeyboardType.Number,
                            isError = !portValid,
                            supporting = stringResource(if (portValid) R.string.setup_port_help else R.string.setup_port_error),
                        ) { value ->
                            draft = settings.copy(destPort = value.toIntOrNull() ?: 0)
                            testOk = null
                        }
                        WizardField(
                            label = stringResource(R.string.setup_token),
                            value = settings.destToken,
                            password = true,
                            supporting = stringResource(R.string.setup_token_help),
                        ) {
                            draft = settings.copy(destToken = it)
                            testOk = null
                        }
                        FilledTonalButton(
                            onClick = {
                                testing = true
                                testOk = null
                                message = null
                                scope.launch {
                                    try {
                                        onSave(settings)
                                        message = onTest(settings)
                                        testOk = true
                                    } catch (exc: Exception) {
                                        message = exc.message ?: connectionFailed
                                        testOk = false
                                        AppToasts.show(context, message.orEmpty())
                                    } finally {
                                        testing = false
                                    }
                                }
                            },
                            enabled = !testing && hostValid && portValid,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) {
                            if (testing) {
                                LoadingIndicator(modifier = Modifier.size(28.dp))
                            } else {
                                Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(if (testing) R.string.setup_testing else R.string.setup_test))
                        }
                        AnimatedVisibility(visible = testOk != null, enter = fadeIn() + expandVertically()) {
                            TestResult(ok = testOk == true, message = message.orEmpty(), settings = settings)
                        }
                    }
                    STEP_CAMERA -> {
                        StepHeader(
                            title = stringResource(R.string.setup_camera_title),
                            body = stringResource(R.string.setup_camera_body),
                        )
                        ListGroup {
                            SwitchItem(
                                title = stringResource(R.string.settings_camera_wifi_auto),
                                supporting = stringResource(R.string.settings_camera_wifi_auto_sub),
                                icon = Icons.Outlined.Sensors,
                                checked = settings.cameraWifiAutoConnect,
                                onCheckedChange = { draft = settings.copy(cameraWifiAutoConnect = it) },
                            )
                        }
                        WizardField(
                            label = stringResource(R.string.settings_camera_ssid),
                            value = settings.cameraSsid,
                            supporting = stringResource(R.string.settings_camera_ssid_help),
                        ) { draft = settings.copy(cameraSsid = it.trim()) }
                        WizardField(
                            label = stringResource(R.string.settings_camera_wifi_password),
                            value = settings.cameraWifiPassword,
                            password = true,
                            supporting = stringResource(R.string.setup_camera_password_help),
                        ) { draft = settings.copy(cameraWifiPassword = it) }
                    }
                    STEP_DONE -> DoneStep()
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step == STEP_CAMERA) {
                TextButton(onClick = { step = STEP_DONE }) { Text(stringResource(R.string.setup_skip)) }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    when (step) {
                        STEP_WELCOME -> step = STEP_HOMELAB
                        STEP_HOMELAB, STEP_CAMERA -> scope.launch {
                            onSave(settings)
                            step++
                        }
                        else -> scope.launch {
                            onSave(settings)
                            onBack()
                        }
                    }
                },
                enabled = step != STEP_HOMELAB || (hostValid && portValid && !testing),
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.MediumContentPadding,
                modifier = Modifier.height(56.dp),
            ) {
                Text(
                    stringResource(
                        when (step) {
                            STEP_WELCOME -> R.string.setup_get_started
                            STEP_HOMELAB -> if (testOk == true) R.string.setup_continue else R.string.setup_continue_untested
                            STEP_DONE -> R.string.setup_finish
                            else -> R.string.setup_continue
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (step != STEP_DONE) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(step: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(STEP_COUNT) { index ->
            val color by animateColorAsState(
                if (index <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "step$index",
            )
            Box(
                Modifier
                    .weight(if (index == step) 2f else 1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun StepHeader(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WelcomeStep() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(32.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        PipelineDiagram(
            cameraOnline = true,
            homelabOnline = true,
            syncing = true,
            offloading = false,
            stagedLabel = stringResource(R.string.pipeline_phone_role),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 28.dp),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.setup_welcome_title), style = MaterialTheme.typography.displaySmall)
        Text(
            stringResource(R.string.setup_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ListGroup {
        Feature(Icons.Outlined.Sensors, stringResource(R.string.setup_feature_watch), MaterialTheme.signals.sync)
        Feature(Icons.Outlined.Bolt, stringResource(R.string.setup_feature_fast), MaterialTheme.signals.sync)
        Feature(Icons.Outlined.CloudUpload, stringResource(R.string.setup_feature_offload), MaterialTheme.signals.offload)
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TestResult(ok: Boolean, message: String, settings: BridgeSettings) {
    val tint = if (ok) MaterialTheme.signals.online else MaterialTheme.colorScheme.error
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (ok) {
            CheckRow(stringResource(R.string.setup_check_server, settings.destHost, settings.destPort))
            CheckRow(stringResource(R.string.setup_check_health))
            CheckRow(stringResource(R.string.setup_check_ready))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = tint)
                Text(stringResource(R.string.setup_check_failed), style = MaterialTheme.typography.titleSmall, color = tint)
            }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CheckRow(text: String) {
    val tint = MaterialTheme.signals.online
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(50))
                .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(14.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DoneStep() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(MaterialShapes.SoftBurst.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(64.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.setup_done_title), style = MaterialTheme.typography.displaySmall)
        Text(
            stringResource(R.string.setup_done_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.setup_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(16.dp),
        )
    }
}

@Composable
private fun WizardField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    isError: Boolean = false,
    supporting: String? = null,
    onChange: (String) -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            { TextButton(onClick = { reveal = !reveal }) { Text(stringResource(if (reveal) R.string.common_hide else R.string.common_show)) } }
        } else {
            null
        },
        textStyle = if (keyboardType == KeyboardType.Text) MaterialTheme.typography.bodyLarge else Numeric.medium,
        shape = RoundedCornerShape(16.dp),
    )
}
