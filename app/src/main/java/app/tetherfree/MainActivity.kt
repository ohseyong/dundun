package app.tetherfree

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.dimensionResource
import androidx.annotation.StringRes

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Home(resumeTick.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

// ---- 색 ----

private val Accent = Color(0xFF2457D6)
private val AccentDark = Color(0xFF8DB1FF)

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = AccentDark, onPrimary = Color(0xFF0B1B44),
        background = Color(0xFF121316), surface = Color(0xFF1B1C20),
        onBackground = Color(0xFFECECEE), onSurface = Color(0xFFECECEE),
        onSurfaceVariant = Color(0xFFA3A5AD), outlineVariant = Color(0xFF2E3036),
        error = Color(0xFFFF8A80),
    ) else lightColorScheme(
        primary = Accent, onPrimary = Color.White,
        background = Color(0xFFF6F6F4), surface = Color.White,
        onBackground = Color(0xFF16181D), onSurface = Color(0xFF16181D),
        onSurfaceVariant = Color(0xFF62656E), outlineVariant = Color(0xFFE6E6E3),
        error = Color(0xFFC62828),
    )
    val base = MaterialTheme.typography
    val phrase = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
    MaterialTheme(colorScheme = scheme, typography = base.copy(
        bodyLarge = base.bodyLarge.copy(lineBreak = phrase),
        bodyMedium = base.bodyMedium.copy(lineBreak = phrase),
    )) {
        ProvideTextStyle(LocalTextStyle.current.copy(lineBreak = phrase), content)
    }
}

// ---- 화면 ----

private enum class StepState { Done, Current, Waiting }

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
    val carrier = Sys.carrierName(context)
    var netTick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { netTick++ }
            override fun onLost(network: Network) { netTick++ }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    // 연결 변화 알림을 놓치는 경우를 대비해 화면이 떠 있는 동안 3초마다 다시 본다.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3_000)
            netTick++
        }
    }
    val live = remember(netTick, tick) { Sys.liveState(context) }
    val recorded = store.applied && !(work is Work.Done && (work as Work.Done).restored)
    // 실제 상태를 알 수 있으면 그걸 따르고, 모르면 기록을 따른다.
    val applied = when (live) {
        Sys.Live.Applied -> true
        Sys.Live.NotApplied -> false
        Sys.Live.Unknown -> recorded
    }
    val paired = store.paired

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
        LiveStatus(live, recorded)
        Spacer(Modifier.height(16.dp))

        if (applied) {
            DoneCard(result = if (recorded) store.result else emptyMap(), appliedAt = store.appliedAt, carrier = carrier, onHotspot = { Sys.openHotspot(context) })
        } else {
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
                Step(4, stringResource(R.string.step4_title), if (carrier.isNotBlank()) stringResource(R.string.step4_body_carrier, carrier) else stringResource(R.string.step4_body), s4) {
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

        if (applied) {
            Spacer(Modifier.height(8.dp))
            val restoring = work is Work.Busy
            Text(
                stringResource(if (restoring) (work as Work.Busy).text else R.string.restore_button),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(enabled = !restoring) { runWithDebugging(AdbService.ACTION_RESTORE) }
                    .padding(vertical = 10.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.disclaimer),
            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 맨 위 상태 줄. 지금 실제로 적용돼 있는지 보여 준다. */
@Composable
private fun LiveStatus(live: Sys.Live, recorded: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val (title, body) = when (live) {
        Sys.Live.Applied -> R.string.live_applied_title to R.string.live_applied_body
        Sys.Live.NotApplied -> R.string.live_off_title to (if (recorded) R.string.live_off_body_record else R.string.live_off_body)
        Sys.Live.Unknown -> R.string.live_unknown_title to (if (recorded) R.string.live_unknown_body_record else R.string.live_unknown_body)
    }
    val color = when (live) {
        Sys.Live.Applied -> scheme.primary
        Sys.Live.NotApplied -> if (recorded) scheme.error else scheme.onSurfaceVariant
        Sys.Live.Unknown -> scheme.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (live == Sys.Live.Unknown) scheme.onSurface else color)
            Spacer(Modifier.height(2.dp))
            Text(stringResource(body), fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DoneCard(result: Map<String, String>, appliedAt: Long, carrier: String, onHotspot: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).background(scheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.done_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.done_body), fontSize = 15.sp, color = scheme.onSurface)
            Spacer(Modifier.height(16.dp))
            Primary(stringResource(R.string.done_hotspot), onHotspot)
        }
        HorizontalDivider(color = scheme.outlineVariant)
        if (result.isEmpty()) {
            Text(
                stringResource(R.string.detail_no_record), fontSize = 14.sp, lineHeight = 20.sp,
                color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            return@Card
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.detail_header), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
            val sim = listOfNotNull(
                result["sim_carrier"]?.takeIf { it.isNotBlank() } ?: carrier.takeIf { it.isNotBlank() },
                result["sim_slot"]?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.detail_sim_slot, it) },
            ).joinToString(" · ")
            Detail(stringResource(R.string.detail_sim), sim)
            Detail(stringResource(R.string.detail_name), result["name"].orEmpty())
            Detail(stringResource(R.string.detail_apn), result["apn"].orEmpty())
            val mcc = result["mcc"].orEmpty()
            val mnc = result["mnc"].orEmpty()
            if (mcc.isNotBlank()) Detail(stringResource(R.string.detail_mcc_mnc), "$mcc / $mnc")
            DetailRow(stringResource(R.string.detail_type)) { TypeText(result["type"].orEmpty()) }
            val orig = result["orig"].orEmpty()
            if (result["mode"] != "already" && orig.isNotBlank()) DetailRow(stringResource(R.string.detail_old_type)) { TypeText(orig, highlight = false) }
            val protocol = result["protocol"].orEmpty()
            if (protocol.isNotBlank()) Detail(stringResource(R.string.detail_protocol), protocol)
            Detail(stringResource(R.string.detail_mode), stringResource(modeLabel(result["mode"])))
            Detail(stringResource(R.string.detail_preferred), stringResource(if (result["preferred"] == "false") R.string.detail_preferred_no else R.string.detail_preferred_yes))
            if (appliedAt > 0) {
                Detail(stringResource(R.string.detail_time), java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(appliedAt)))
            }
        }
    }
}

@StringRes
private fun modeLabel(mode: String?): Int = when (mode) {
    "copy" -> R.string.mode_copy
    "already" -> R.string.mode_already
    else -> R.string.mode_merge
}

@Composable
private fun Detail(label: String, value: String) {
    DetailRow(label) {
        Text(value.ifBlank { "-" }, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DetailRow(label: String, value: @Composable () -> Unit) {
    // 이름 칸 너비를 고정해 값이 한 줄로 맞춰지게 한다.
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(dimensionResource(R.dimen.detail_label_width)).padding(end = 8.dp))
        Box(Modifier.weight(1f)) { value() }
    }
}

/** APN 타입 목록에서 dun 만 강조한다. */
@Composable
private fun TypeText(types: String, highlight: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val parts = types.split(",").map(String::trim).filter(String::isNotEmpty)
    val text = buildAnnotatedString {
        parts.forEachIndexed { i, t ->
            if (i > 0) append(", ")
            if (highlight && t.equals("dun", true)) withStyle(SpanStyle(color = scheme.primary, fontWeight = FontWeight.SemiBold)) { append(t) }
            else append(t)
        }
    }
    Text(if (parts.isEmpty()) AnnotatedString("-") else text, fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurface)
}

// ---- 부품 ----

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) { content() }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun Step(number: Int, title: String, body: String, state: StepState, action: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp)) {
        // 번호 칸은 28dp 고정, 글은 모두 같은 왼쪽 선에서 시작한다.
        Box(
            Modifier.size(28.dp).then(
                when (state) {
                    StepState.Done -> Modifier.background(scheme.primary, CircleShape)
                    StepState.Current -> Modifier.border(1.5.dp, scheme.primary, CircleShape)
                    StepState.Waiting -> Modifier.border(1.dp, scheme.outlineVariant, CircleShape)
                }
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (state == StepState.Done) {
                Icon(Icons.Rounded.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
            } else {
                Text(
                    number.toString(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (state == StepState.Current) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 3.dp)) {
            Text(
                title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = if (state == StepState.Waiting) scheme.onSurfaceVariant else scheme.onSurface,
            )
            if (state == StepState.Current) {
                Spacer(Modifier.height(4.dp))
                Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                action()
            }
        }
    }
}

@Composable
private fun Primary(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Secondary(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) { Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun Status(text: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
}
