package app.tetherfree

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class StepState { Done, Current, Waiting }

@Composable
internal fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) { content() }
}

@Composable
internal fun Divider() = HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)

@Composable
internal fun Step(number: Int, title: String, body: String, state: StepState, action: @Composable () -> Unit) {
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
internal fun Primary(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

@Composable
internal fun Secondary(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) { Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
internal fun Status(text: String, busy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun ErrorText(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
}
