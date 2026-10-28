package org.sand.ticwatch.phone.report

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/** Hands a rendered report to the user: print / save as PDF, or share as Markdown text. */
interface CardioReportExporter {
    fun print(html: String, jobName: String)
    fun shareMarkdown(markdown: String, subject: String)
}

/**
 * Android export through the system dialogs. [context] must be an Activity (PrintManager needs one); call on the
 * main thread - the WebView is created, laid out and printed there.
 */
class AndroidCardioReportExporter(private val context: Context) : CardioReportExporter {

    // The WebView must outlive print(): the print framework keeps reading it after the dialog opens.
    private var printView: WebView? = null

    override fun print(html: String, jobName: String) {
        val view = WebView(context)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(webView: WebView, url: String?) {
                val printManager = context.getSystemService(PrintManager::class.java)
                printManager.print(
                    jobName,
                    webView.createPrintDocumentAdapter(jobName),
                    PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build(),
                )
            }
        }
        view.loadDataWithBaseURL(null, html, MIME_HTML, ENCODING, null)
        printView = view
    }

    override fun shareMarkdown(markdown: String, subject: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME_MARKDOWN)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, markdown)
        context.startActivity(Intent.createChooser(send, subject))
    }

    private companion object {
        const val MIME_HTML = "text/html"
        const val MIME_MARKDOWN = "text/markdown"
        const val ENCODING = "UTF-8"
    }
}
