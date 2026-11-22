package app.tetherfree

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Home(resumeTick.intValue) } }
        Updater.schedule(this, Store(this).autoUpdate)
    }

    override fun onResume() {
        super.onResume()
        Updater.foreground = true
        resumeTick.intValue++
    }

    override fun onPause() {
        Updater.foreground = false
        super.onPause()
    }
}

@Composable
private fun Home(tick: Int) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    val work by WorkState.flow.collectAsState()

    // onResume 마다 다시 읽는다.
    @Suppress("UNUSED_EXPRESSION") tick
    val devOn = Sys.devOptionsOn(context)
    val wdOn = Sys.wirelessDebuggingOn(context)
    val wifi = Sys.wifiConnected(context)
    val sims = store.simResults
    val canRestore = store.canRestore
    val paired = store.paired
    val busy = work is Work.Busy || work is Work.WaitingCode

    var notice by remember { mutableStateOf<Int?>(null) }
    // 설정 화면에 다녀오면 안내는 지운다. 상태는 위에서 다시 읽는다.
    LaunchedEffect(tick) { notice = null }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            AdbService.start(context, AdbService.ACTION_PAIR)
            Sys.openWirelessDebugging(context)
        } else {
            notice = R.string.notice_notifications
        }
    }
    fun startPairing() {
        notice = null
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            AdbService.start(context, AdbService.ACTION_PAIR)
            Sys.openWirelessDebugging(context)
        } else {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun runWithDebugging(action: String) {
        notice = null
        if (wdOn == false) {
            notice = R.string.notice_turn_on_debugging
            Sys.openWirelessDebugging(context)
        } else {
            AdbService.start(context, action)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(stringResource(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(16.dp))
        if (sims.isNotEmpty()) {
            ApnResultCard(sims, store.appliedAt) { Sys.openHotspot(context) }
            Spacer(Modifier.height(12.dp))
        }
        if (sims.isEmpty() || !paired) {
            // 다시 적용할 때도 무선 디버깅은 켜져 있어야 하니, 앞 단계가 끝나야 다음 단계가 열린다.
            val s1 = if (devOn) StepState.Done else StepState.Current
            val s2 = when {
                !devOn -> StepState.Waiting
                wdOn != false -> StepState.Done
                else -> StepState.Current
            }
            val s3 = when {
                paired -> StepState.Done
                s2 == StepState.Done -> StepState.Current
                else -> StepState.Waiting
            }
            val s4 = if (paired && s2 == StepState.Done) StepState.Current else StepState.Waiting

            Card {
                Step(1, stringResource(R.string.step1_title), stringResource(R.string.step1_body), s1) {
                    Primary(stringResource(R.string.step1_button)) { Sys.openSoftwareInfo(context) }
                }
                Divider()
                Step(2, stringResource(R.string.step2_title),
                    stringResource(if (wifi) R.string.step2_body else R.string.step2_body_wifi),
                    s2) {
                    if (wifi) Primary(stringResource(R.string.step2_button)) { Sys.openWirelessDebugging(context) }
                    else Primary(stringResource(R.string.step2_button_wifi)) { Sys.openWifi(context) }
                }
                Divider()
                Step(3, stringResource(R.string.step3_title), stringResource(R.string.step3_body), s3) {
                    when (val w = work) {
                        is Work.WaitingCode -> {
                            Status(stringResource(if (w.ready) R.string.step3_waiting_ready else R.string.step3_waiting_dialog), busy = true)
                            w.error?.let { ErrorText(stringResource(it)) }
                            Spacer(Modifier.height(10.dp))
                            Secondary(stringResource(R.string.step3_reopen)) { Sys.openWirelessDebugging(context) }
                        }
                        is Work.Busy -> Status(stringResource(w.text), busy = true)
                        else -> Primary(stringResource(R.string.step3_button)) { startPairing() }
                    }
                }
                Divider()
                Step(4, stringResource(R.string.step4_title), stringResource(R.string.step4_body), s4) {
                    when (val w = work) {
                        is Work.Busy -> Status(stringResource(w.text), busy = true)
                        else -> Primary(stringResource(R.string.step4_button)) { runWithDebugging(AdbService.ACTION_APPLY) }
                    }
                }
            }
        }

        val failed = work as? Work.Failed
        if (failed != null || notice != null) {
            Spacer(Modifier.height(12.dp))
            ErrorText(notice?.let { stringResource(it) } ?: failed!!.message)
        }
        if (notice == R.string.notice_notifications) {
            Spacer(Modifier.height(10.dp))
            Secondary(stringResource(R.string.open_notification_settings)) { Sys.openNotificationSettings(context) }
        }

        val confirmation = when {
            work == Work.Paired -> R.string.result_paired
            (work as? Work.Done)?.restored == true -> R.string.notif_restored_text
            else -> null
        }
        if (confirmation != null) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(confirmation))
        }
        if (wdOn != false && (work is Work.Done || work is Work.Failed || work == Work.Cancelled || work == Work.Paired)) {
            Spacer(Modifier.height(12.dp))
            ErrorText(stringResource(R.string.debug_shutdown_check))
            Secondary(stringResource(R.string.step2_button)) { Sys.openWirelessDebugging(context) }
        }
        if (canRestore) {
            Spacer(Modifier.height(8.dp))
            if (!busy) Secondary(stringResource(R.string.restore_button)) {
                if (!paired && wdOn == true) startPairing()
                else runWithDebugging(AdbService.ACTION_RESTORE)
            }
        }
        if (paired && !busy && sims.isNotEmpty() && store.legacyResult.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Secondary(stringResource(R.string.step4_button)) { runWithDebugging(AdbService.ACTION_APPLY) }
        }

        Spacer(Modifier.height(24.dp))
        UpdateSection(store, tick, busy)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.data_note),
            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.disclaimer),
            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
