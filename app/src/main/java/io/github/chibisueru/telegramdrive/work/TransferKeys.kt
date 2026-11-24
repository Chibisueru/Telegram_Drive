package io.github.chibisueru.telegramdrive.work

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import io.github.chibisueru.telegramdrive.R
import io.github.chibisueru.telegramdrive.TgDriveApp

object TransferKeys {
    const val TAG = "transfer"
    const val TAG_UPLOAD = "transfer_upload"
    const val TAG_DOWNLOAD = "transfer_download"
    const val NAME_TAG_PREFIX = "name:"

    const val URI = "uri"
    const val FOLDER = "folder"
    const val NAME = "name"
    const val MIME = "mime"
    const val SIZE = "size"
    const val FILE_ID = "file_id"

    const val PROGRESS = "progress"
    const val ERROR = "error"
}

internal fun transferForegroundInfo(context: Context, id: Int, title: String, name: String, progress: Float?): ForegroundInfo {
    val notification: Notification = NotificationCompat.Builder(context, TgDriveApp.TRANSFER_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_cloud)
        .setContentTitle(title)
        .setContentText(name)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(1000, ((progress ?: 0f) * 1000).toInt(), progress == null)
        .build()
    return ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}
