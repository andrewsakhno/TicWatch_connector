package org.sand.ticwatch.phone.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Privacy-policy / rationale screen Health Connect requires before it will show its
 * permission dialog at all. Without an activity handling
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (Android 13-) and the
 * `VIEW_PERMISSION_USAGE` + `HEALTH_PERMISSIONS` alias (Android 14+), the platform
 * auto-denies every health permission request without any UI.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text(TITLE, style = MaterialTheme.typography.headlineSmall)
                        Text(BODY, modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }

    private companion object {
        const val TITLE = "How TicWatch Connector uses health data"
        const val BODY =
            "Heart-rate samples measured by your TicWatch are written to Health Connect, " +
                "and a resting heart rate is derived from them on this phone. " +
                "The app only writes data; it does not read your Health Connect records " +
                "or share them with third parties."
    }
}
