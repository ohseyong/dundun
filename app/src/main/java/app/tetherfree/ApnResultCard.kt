package app.tetherfree

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ApnResultCard(sims: List<Map<String, String>>, appliedAt: Long, onHotspot: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card {
        Column(Modifier.padding(20.dp)) {
            val failures = sims.count { it["status"] == "error" }
            val title = when (failures) {
                0 -> R.string.done_title
                sims.size -> R.string.result_failed
                else -> R.string.result_partial
            }
            Text(stringResource(title), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            if (failures < sims.size) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.done_body), fontSize = 15.sp, color = scheme.onSurface)
                Spacer(Modifier.height(16.dp))
                Primary(stringResource(R.string.done_hotspot), onHotspot)
            }
            // 핫스팟에 노트북을 붙인 사람에게 엑셀코스피를 권한다. 한국 주식 사이트라 한국어 화면에서만.
            if (LocalConfiguration.current.locales[0].language == "ko") {
                val context = LocalContext.current
                Spacer(Modifier.height(14.dp))
                Column(
                    modifier = Modifier
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EXCELKOSPI_URL)))
                            }
                        }
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        stringResource(R.string.promo_question),
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.promo_excelkospi) + " →",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.primary,
                    )
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        // 유심마다 한 묶음. 듀얼심이면 두 묶음이 줄로 나뉜다.
        sims.forEachIndexed { index, result ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = scheme.outlineVariant)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (index == 0) Text(stringResource(R.string.detail_header), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurfaceVariant)
                val sim = listOfNotNull(
                    result["sim_carrier"]?.takeIf { it.isNotBlank() },
                    result["sim_slot"]?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.detail_sim_slot, it) },
                ).joinToString(" · ")
                Detail(stringResource(R.string.detail_sim), sim)
                if (result["status"] == "error") {
                    Text(stringResource(R.string.detail_sim_failed, result["error"].orEmpty()), fontSize = 14.sp, lineHeight = 20.sp, color = scheme.error)
                    return@Column
                }
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
                if (appliedAt > 0 && index == sims.lastIndex) {
                    Detail(stringResource(R.string.detail_time), java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(appliedAt)))
                }
            }
        }
    }
}

private const val EXCELKOSPI_URL = "https://excelkospi.ai/?utm_source=dundun&utm_medium=app&utm_campaign=done"

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
