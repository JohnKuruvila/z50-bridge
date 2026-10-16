package io.github.johnkuruvila.z50bridge.ui.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.johnkuruvila.z50bridge.R
import java.io.File

/** Writes [report] to the shared cache and opens the share sheet. False if nothing can take it. */
fun shareDiagnostics(context: Context, report: String): Boolean = try {
    val file = File(context.cacheDir, "z50-diagnostics.txt").apply { writeText(report) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.diagnostics_subject))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.diagnostics_share_title)))
    true
} catch (_: Exception) {
    false
}
