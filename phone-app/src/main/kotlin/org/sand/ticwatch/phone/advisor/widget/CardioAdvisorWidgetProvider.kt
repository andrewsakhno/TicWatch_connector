package org.sand.ticwatch.phone.advisor.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.sand.ticwatch.phone.AppContainer
import org.sand.ticwatch.phone.MainActivity
import org.sand.ticwatch.phone.datalayer.WatchStatePublisher
import org.sand.ticwatch.phone.history.SensorHistoryModule
import java.time.ZoneId

/** Home-screen widget with the Advisor tab's readiness, advice and latest vitals; rendered off the main thread. */
class CardioAdvisorWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = AppContainer(appContext)
                val renderer = CardioAdvisorWidgetRenderer(appContext, MainActivity.advisorIntent(appContext), ZoneId.systemDefault())
                val views = runCatching { renderer.render(container.cardioReadiness.load(), SensorHistoryModule.clock.instant()) }
                    .onFailure { Log.w(TAG, "Readiness unavailable", it) }
                    .getOrElse { renderer.unavailable(UNAVAILABLE) }
                widgetIds.forEach { manager.updateAppWidget(it, views) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "CardioAdvisorWidget"
        const val UNAVAILABLE = "No readiness data yet"
    }
}

/** Re-renders every placed advisor widget; hooked after each stored sensor push and blood pressure change. */
class AdvisorWidgetRefresher(context: Context) : WatchStatePublisher {
    private val appContext = context.applicationContext

    override fun publish() {
        val component = ComponentName(appContext, CardioAdvisorWidgetProvider::class.java)
        val ids = AppWidgetManager.getInstance(appContext).getAppWidgetIds(component)
        if (ids.isEmpty()) return
        appContext.sendBroadcast(
            Intent(appContext, CardioAdvisorWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
        )
    }
}
