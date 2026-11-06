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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sand.ticwatch.phone.R
import org.sand.ticwatch.phone.ui.TicWatchTheme

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
            TicWatchTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text(stringResource(R.string.hc_rationale_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.hc_rationale_body), modifier = Modifier.padding(top = 16.dp))
                    }
                }
            }
        }
    }

}
