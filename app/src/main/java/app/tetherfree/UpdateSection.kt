package app.tetherfree

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 버전, 업데이트 버튼, 자동 업데이트 스위치. */
@Composable
internal fun UpdateSection(store: Store, tick: Int, busy: Boolean) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val state by Updater.flow.collectAsState()
    var auto by remember { mutableStateOf(store.autoUpdate) }

    // 앱을 열면 한 번 확인한다. 자동 업데이트가 켜져 있고 설정 중이 아니면 바로 받는다.
    LaunchedEffect(Unit) {
        val release = Updater.check(context)
        if (release != null && store.autoUpdate && !busy) Updater.downloadAndInstall(context, release)
    }
    // 설치 허용 화면에서 돌아오면 이어서 받는다.
    LaunchedEffect(tick) {
        val s = Updater.flow.value
        if (s is Updater.State.NeedPermission && context.packageManager.canRequestPackageInstalls()) {
            Updater.downloadAndInstall(context, s.release)
        }
    }

    val status = when (val s = state) {
        Updater.State.Idle, Updater.State.Checking -> stringResource(R.string.update_checking)
        Updater.State.Latest -> stringResource(R.string.update_latest)
        is Updater.State.Available -> stringResource(R.string.update_available, s.release.version)
        is Updater.State.Downloading -> stringResource(R.string.update_downloading, "${s.percent}%")
        is Updater.State.NeedPermission -> stringResource(R.string.update_allow_source)
        Updater.State.Installing -> stringResource(R.string.update_installing)
        Updater.State.Failed -> stringResource(R.string.update_failed)
    }
    Card {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_version, Updater.currentVersion(context)), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(status, fontSize = 14.sp, lineHeight = 20.sp, color = if (state is Updater.State.Available) scheme.primary else scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            when (val s = state) {
                is Updater.State.Available -> Primary(stringResource(R.string.update_button)) {
                    scope.launch { Updater.downloadAndInstall(context, s.release) }
                }
                is Updater.State.NeedPermission -> Secondary(stringResource(R.string.update_allow_button)) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    }
                }
                Updater.State.Failed -> Secondary(stringResource(R.string.update_check)) {
                    scope.launch { Updater.check(context) }
                }
                is Updater.State.Downloading, Updater.State.Installing, Updater.State.Checking ->
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> Unit
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_auto), fontSize = 15.sp, color = scheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(R.string.update_auto_body), fontSize = 13.sp, lineHeight = 18.sp, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = auto, onCheckedChange = {
                auto = it
                store.autoUpdate = it
                Updater.schedule(context, it)
            })
        }
    }
}
