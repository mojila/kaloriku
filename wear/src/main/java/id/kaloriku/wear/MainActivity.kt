package id.kaloriku.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import id.kaloriku.shared.KaloriKu
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.sync.SyncRole
import id.kaloriku.wear.ui.EditEntryScreen
import id.kaloriku.wear.ui.HomeScreen
import id.kaloriku.wear.ui.RecentDetailScreen
import id.kaloriku.wear.ui.VoiceLogScreen
import id.kaloriku.wear.ui.theme.KaloriWearTheme
import kotlinx.coroutines.launch

private const val ARG_ENTRY_ID = "entryId"
private const val ROUTE_HOME = "home"
private const val ROUTE_VOICE = "voice"
private const val ROUTE_DETAIL = "detail/{$ARG_ENTRY_ID}"
private const val ROUTE_EDIT = "edit/{$ARG_ENTRY_ID}"

private fun entryRoute(base: String, id: Long) = "$base/$id"

class MainActivity : ComponentActivity() {

    /** Set by `WearNav` so a fresh permission result can update the voice screen gate. */
    private var micResultHandler: ((Boolean) -> Unit)? = null

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micResultHandler?.invoke(granted)
    }

    override fun onStart() {
        super.onStart()
        // Retry anything the peer has not acked yet (typically an edit made while the
        // phone was unreachable) every time the watch app comes back to the front.
        lifecycleScope.launch {
            KaloriKu.init(applicationContext, SyncRole.WATCH).sync.sync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KaloriWearTheme {
                val vm: WearViewModel = viewModel()
                WearNav(
                    vm = vm,
                    onRegisterMicResult = { micResultHandler = it },
                    onRequestMic = { requestMic.launch(Manifest.permission.RECORD_AUDIO) },
                    testTranscript = intent?.getStringExtra("kaloriku_test_transcript"),
                    testSave = intent?.getStringExtra("kaloriku_test_save"),
                )
            }
        }
    }
}

/**
 * Navigation graph for the watch app.
 *
 * Previously the screens were swapped with a plain `when` on local state, so the
 * system swipe-back gesture had no back-stack entry to pop and finished the
 * activity instead (landing on the watch face). Hosting every screen in a
 * [SwipeDismissableNavHost] gives swipe-back a destination to return to, so it
 * goes to the previous app page and only exits from home.
 *
 * The detail and edit destinations prefer the live home list on every recomposition,
 * so an edit refreshes them immediately. When the entry is not in that 15-row window
 * (e.g. it aged out, or the screen was opened from a deep link), they fall back to a
 * by-id repository lookup. Only when that lookup also returns nothing is the entry
 * considered gone and the screen popped back home.
 */
@Composable
private fun WearNav(
    vm: WearViewModel,
    onRegisterMicResult: ((Boolean) -> Unit) -> Unit,
    onRequestMic: () -> Unit,
    testTranscript: String?,
    testSave: String?,
) {
    val navController = rememberSwipeDismissableNavController()
    val home by vm.home.collectAsStateWithLifecycle()

    // Debug-only test seam: run the watch analysis pipeline from adb.
    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            testSave?.takeIf { it.isNotBlank() }?.let { vm.analyzeAndSave(it) }
            testTranscript?.takeIf { it.isNotBlank() }?.let {
                vm.analyze(it)
                navController.navigate(ROUTE_VOICE)
            }
        }
    }

    // The mic permission is requested when the user opens the voice screen, but the
    // result may be a denial. The voice route reads this to decide whether it may
    // auto-launch speech; on denial the typed fallback is shown instead.
    val context = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    // Hand the activity a way to report a fresh permission result back into this state.
    LaunchedEffect(Unit) { onRegisterMicResult { granted -> micGranted = granted } }

    AppScaffold {
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
        ) {
            composable(ROUTE_HOME) {
                HomeScreen(
                    vm = vm,
                    onLogVoice = {
                        // Ask only when the mic is not already granted. The voice screen
                        // reads the live state and either auto-launches speech or shows
                        // the typed fallback, so a denial is never a dead end.
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) onRequestMic()
                        navController.navigate(ROUTE_VOICE)
                    },
                    onSelect = { entry -> navController.navigate(entryRoute("detail", entry.id)) },
                )
            }
            composable(ROUTE_VOICE) {
                VoiceLogScreen(
                    vm = vm,
                    micGranted = micGranted,
                    onRequestMic = onRequestMic,
                    onClose = { navController.popBackStack() },
                )
            }
            composable(
                route = ROUTE_DETAIL,
                arguments = listOf(navArgument(ARG_ENTRY_ID) { type = NavType.LongType }),
            ) { backStackEntry ->
                val entryId = backStackEntry.arguments?.getLong(ARG_ENTRY_ID) ?: -1L
                when (val lookup = resolveEntry(vm, home.recent, entryId)) {
                    EntryLookup.Loading -> Unit // Read in flight: render nothing, do not pop.
                    EntryLookup.Missing -> {
                        // Entry genuinely no longer exists (deleted elsewhere): go home.
                        LaunchedEffect(Unit) {
                            navController.popBackStack(ROUTE_HOME, inclusive = false)
                        }
                    }
                    is EntryLookup.Found -> RecentDetailScreen(
                        entry = lookup.entry,
                        vm = vm,
                        onClose = { navController.popBackStack() },
                        onEdit = { navController.navigate(entryRoute("edit", it.id)) },
                    )
                }
            }
            composable(
                route = ROUTE_EDIT,
                arguments = listOf(navArgument(ARG_ENTRY_ID) { type = NavType.LongType }),
            ) { backStackEntry ->
                val entryId = backStackEntry.arguments?.getLong(ARG_ENTRY_ID) ?: -1L
                when (val lookup = resolveEntry(vm, home.recent, entryId)) {
                    EntryLookup.Loading -> Unit // Read in flight: render nothing, do not pop.
                    EntryLookup.Missing -> {
                        LaunchedEffect(Unit) {
                            navController.popBackStack(ROUTE_HOME, inclusive = false)
                        }
                    }
                    is EntryLookup.Found -> EditEntryScreen(
                        vm = vm,
                        entry = lookup.entry,
                        // Back to the detail page, matching the old overlay behaviour.
                        onDone = { navController.popBackStack() },
                        onCancel = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/**
 * Resolves the entry a detail/edit destination should show.
 *
 * The live home list is checked first because it is already in memory and updates on
 * every recomposition, so an edit elsewhere on the screen is reflected immediately.
 * When the id is not in that window (it only holds the newest rows), the repository
 * is queried directly. [EntryLookup.Missing] is returned only once that read has
 * completed and found nothing, which is the only case the caller should treat as
 * "pop back home"; [EntryLookup.Loading] must render nothing without popping.
 */
@Composable
private fun resolveEntry(
    vm: WearViewModel,
    recent: List<FoodEntry>,
    entryId: Long,
): EntryLookup {
    // Fast path: always up to date, and refreshes the screen after an edit.
    recent.firstOrNull { it.id == entryId }?.let { return EntryLookup.Found(it) }

    // Only re-read once the fast path misses. Keying on the recent list as well means a
    // delete on the phone (which drops the row and changes the list) re-runs the read,
    // so the screen still pops home when the entry truly goes away.
    val byId by produceState<EntryLookup>(EntryLookup.Loading, entryId, recent) {
        value = if (entryId < 0L) {
            EntryLookup.Missing
        } else {
            vm.findEntry(entryId)?.let { EntryLookup.Found(it) } ?: EntryLookup.Missing
        }
    }
    return byId
}

/** Outcome of the by-id fallback lookup on the detail/edit destinations. */
private sealed interface EntryLookup {
    data object Loading : EntryLookup
    data object Missing : EntryLookup
    data class Found(val entry: FoodEntry) : EntryLookup
}
