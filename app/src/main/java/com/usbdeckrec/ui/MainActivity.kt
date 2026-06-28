package com.usbdeckrec.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.usbdeckrec.service.RecoveryHelper
import com.usbdeckrec.ui.navigation.NavGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * Launcher for requesting multiple runtime permissions at once.
     * Android queues the system dialogs automatically so the user is
     * prompted for each permission in sequence without having to
     * close and reopen the app.
     */
    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // All requested permissions have been granted or denied —
        // proceed either way. Individual checks happen elsewhere
        // before each feature is used.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Scan for orphaned pending MediaStore entries that may have been left
        // behind by a crash during a previous recording session.
        scanForOrphanedRecordings()

        // Request all runtime permissions needed on first launch.
        // Passing them in a single call to RequestMultiplePermissions
        // makes Android show the system dialogs one after another,
        // so the user doesn't need to close and reopen the app to
        // be prompted for each one.
        requestAllNeededPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph()
                }
            }
        }
    }

    /**
     * Launch a background coroutine that scans for and recovers any orphaned
     * pending MediaStore entries (or deletes tiny header-only entries).
     *
     * This runs on [Dispatchers.IO] so it doesn't block the UI thread. Results
     * are logged and, if any files were recovered, a [Toast] is shown to the
     * user.
     */
    private fun scanForOrphanedRecordings() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recoveredFiles = RecoveryHelper.scanAndRecoverOrphanedRecordings(this@MainActivity)

            val recoveredCount = recoveredFiles.count { it.wasRecovered }
            val deletedCount = recoveredFiles.size - recoveredCount

            if (recoveredFiles.isNotEmpty()) {
                android.util.Log.i("MainActivity",
                    "Recovery scan: $recoveredCount recovered, $deletedCount deleted as too small")
            }

            // Show a toast on the main thread if any files were recovered.
            if (recoveredCount > 0) {
                launch(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Recovered $recoveredCount incomplete recording(s) from previous session",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            // On legacy API levels, also clean up any stale .tmp / .partial files
            // that may have been left behind.
            RecoveryHelper.cleanupOrphanedTempFiles(this@MainActivity)
        }
    }

    /**
     * Request all runtime permissions needed on first launch in a single batch.
     *
     * Using [ActivityResultContracts.RequestMultiplePermissions] causes the
     * system to show the permission dialogs one after another, so the user
     * doesn't have to close and reopen the app to be prompted for each one.
     *
     * The permissions requested depend on the API level:
     * - **All API levels**: [Manifest.permission.RECORD_AUDIO] for Oboe/AAudio capture.
     * - **Android 13+ (API 33+)**: [Manifest.permission.POST_NOTIFICATIONS] so the
     *   foreground service notification is visible, and
     *   [Manifest.permission.READ_MEDIA_AUDIO] so the Recordings screen can scan
     *   previously saved files.
     * - **Android 10–12 (API 29–32)**: Scoped storage is in effect —
     *   no storage runtime permission needed.
     * - **Android 9 and below (API < 29)**: [Manifest.permission.READ_EXTERNAL_STORAGE]
     *   for legacy file access.
     *
     * Denial of any one permission is not fatal — the app degrades gracefully:
     * - Without RECORD_AUDIO: no capture possible; the UI shows an error.
     * - Without POST_NOTIFICATIONS: the service still runs, but no visible indicator.
     * - Without storage: the Recordings screen shows a manual "Grant access" button.
     */
    private fun requestAllNeededPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // RECORD_AUDIO is always needed for Oboe/AAudio input.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // POST_NOTIFICATIONS is needed on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Storage read — depends on API level.
        val storagePermission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                Manifest.permission.READ_MEDIA_AUDIO
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                null  // Scoped storage — no runtime permission needed
            else ->
                Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (storagePermission != null &&
            ContextCompat.checkSelfPermission(this, storagePermission) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(storagePermission)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(
                permissionsToRequest.toTypedArray()
            )
        }
    }
}
