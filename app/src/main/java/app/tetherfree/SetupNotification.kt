package app.tetherfree

import android.app.Notification
import android.content.Context

internal fun resultNotification(context: Context, builder: Notification.Builder, result: Work): Notification = with(context) {
    val (title, message) = when (result) {
        is Work.Done -> when {
            result.restored -> R.string.notif_restored_title to getString(R.string.notif_restored_text)
            result.partial -> R.string.result_partial to getString(R.string.result_partial_body)
            else -> R.string.done_title to getString(R.string.done_body)
        }
        is Work.Failed -> R.string.result_failed to result.message
        Work.Paired -> R.string.result_paired to ""
        Work.Cancelled -> R.string.notif_cancel to ""
        else -> R.string.app_name to ""
    }
    val lines = listOfNotNull(message.takeIf { it.isNotBlank() },
        getString(R.string.debug_shutdown_check).takeUnless { Sys.wirelessDebuggingOn(this) == false })
    builder
        .setContentTitle(getString(title))
        .setContentText(lines.joinToString(" "))
        .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
        .setAutoCancel(true)
        .build()
}
