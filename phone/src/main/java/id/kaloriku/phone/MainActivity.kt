package id.kaloriku.phone

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import id.kaloriku.phone.ui.DashboardScreen
import id.kaloriku.phone.ui.InsightsScreen
import id.kaloriku.phone.ui.SettingsScreen
import id.kaloriku.phone.ui.StatsScreen
import id.kaloriku.phone.ui.theme.KaloriKuTheme
import id.kaloriku.shared.KaloriKu
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.sync.SyncRole
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * Where the user wants the backup written. The contract only creates the document;
     * the view model writes the bytes through the returned uri. A null uri means the
     * picker was cancelled, which is not an error and is simply ignored.
     */
    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri?.let { pendingExport?.exportBackup(it) }
        pendingExport = null
    }

    /**
     * The backup file to restore from.
     *
     * A wildcard MIME type is listed alongside `application/json` because several document
     * providers report a stored `.json` file as `application/octet-stream` or with no type
     * at all, which would hide a perfectly valid backup behind a greyed-out file row.
     */
    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { pendingImport?.inspectBackup(it) }
        pendingImport = null
    }

    /**
     * The view model a launcher should deliver its result to.
     *
     * The launchers outlive the composition, so the target is captured here when the
     * launch happens instead of being read from the clicked lambda later.
     */
    private var pendingExport: MainViewModel? = null
    private var pendingImport: MainViewModel? = null

    /** True when the user has already granted `RECORD_AUDIO`. */
    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        // Automatic sync each time the app comes to the foreground.
        lifecycleScope.launch {
            KaloriKu.init(applicationContext, SyncRole.PHONE).sync.sync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KaloriKuTheme {
                val vm: MainViewModel = viewModel()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // Reports whether RECORD_AUDIO is granted right now and kicks off the
                // system dialog when it is not. The voice sheet must never claim to be
                // listening when the permission was denied.
                val micLauncher = remember {
                    {
                        val granted = hasMicPermission()
                        if (!granted) requestMic.launch(Manifest.permission.RECORD_AUDIO)
                        granted
                    }
                }

                // Back on a non-home tab returns to "Hari Ini" before it can exit the
                // app. The sheet-level handler inside the dashboard takes precedence
                // when it is active, because it is composed last.
                BackHandler(enabled = tab != 0) { tab = 0 }

                // Debug-only test seam: drive the analysis pipeline from adb so the
                // real on-device path (network + Jev) can be verified without voice.
                if (BuildConfig.DEBUG) {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        intent?.getStringExtra("kaloriku_test_transcript")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { vm.analyze(it) }
                        intent?.getStringExtra("kaloriku_test_save")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { vm.analyzeAndSave(it) }
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            TabItem("Hari Ini", Icons.Filled.Home, tab == 0) { tab = 0 }
                            TabItem("Statistik", Icons.Filled.BarChart, tab == 1) { tab = 1 }
                            TabItem("Wawasan", Icons.Filled.Lightbulb, tab == 2) { tab = 2 }
                            TabItem("Atur", Icons.Filled.Settings, tab == 3) { tab = 3 }
                        }
                    },
                ) { padding ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                        when (tab) {
                            0 -> DashboardScreen(vm, onRequestMic = micLauncher)
                            1 -> StatsScreen(vm)
                            2 -> InsightsScreen(vm)
                            else -> SettingsScreen(
                                vm = vm,
                                onExportBackup = {
                                    pendingExport = vm
                                    createBackup.launch("kaloriku-backup-${JakartaTime.todayKey()}.json")
                                },
                                onImportBackup = {
                                    pendingImport = vm
                                    openBackup.launch(arrayOf("application/json", "*/*"))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
    )
}
