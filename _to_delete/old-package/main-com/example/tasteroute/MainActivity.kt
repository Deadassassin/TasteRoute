package com.example.tasteroute

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.tasteroute.data.AppState
import com.example.tasteroute.data.LocationRepository
import com.example.tasteroute.data.LocationStatus
import com.example.tasteroute.data.NimClient
import com.example.tasteroute.data.Recommender
import com.example.tasteroute.data.USER_AGENT
import com.example.tasteroute.ui.account.AccountScreen
import com.example.tasteroute.ui.chat.AssistantScreen
import com.example.tasteroute.ui.components.LocalRequestLocation
import com.example.tasteroute.ui.crowd.CheckInScreen
import com.example.tasteroute.ui.detail.PlaceDetailScreen
import com.example.tasteroute.ui.detail.ReviewScreen
import com.example.tasteroute.ui.group.TableSyncScreen
import com.example.tasteroute.ui.home.HomeScreen
import com.example.tasteroute.ui.map.MapRouteScreen
import com.example.tasteroute.ui.map.NavigationScreen
import com.example.tasteroute.ui.onboarding.TasteSetupScreen
import com.example.tasteroute.ui.onboarding.WelcomeScreen
import com.example.tasteroute.ui.settings.SettingsScreen
import com.example.tasteroute.ui.theme.Genie
import com.example.tasteroute.ui.theme.GenieContainer
import com.example.tasteroute.ui.theme.TasteRouteTheme
import com.example.tasteroute.ui.theme.genieEnter
import com.example.tasteroute.ui.theme.genieExit
import java.io.File
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureOsmdroid(this)
        enableEdgeToEdge()
        setContent {
            TasteRouteTheme {
                TasteRouteApp()
            }
        }
    }
}

/** Cache under the app's own dir so no storage permission is needed; tile servers require a real UA. */
private fun configureOsmdroid(context: Context) {
    val cache = File(context.cacheDir, "osmdroid")
    Configuration.getInstance().apply {
        load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        userAgentValue = USER_AGENT
        osmdroidBasePath = cache
        osmdroidTileCache = File(cache, "tiles")
    }
}

private const val DISCOVER = "discover"
private const val ASSISTANT = "assistant"
private const val PROFILE = "profile"
private const val DETAIL = "detail"
private const val ROUTE = "route"
private const val NAVIGATE = "navigate"
private const val TASTE = "taste"
private const val ACCOUNT = "account"
private const val TABLE_SYNC = "table_sync"
private const val CHECK_IN = "check_in"
private const val REVIEW = "review"
private const val ABOUT = "about"

private const val TAB_FADE_MS = 90

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(DISCOVER, "Discover", Icons.Filled.Home),
    Tab(ASSISTANT, "Assistant", Icons.AutoMirrored.Filled.Send),
    Tab(PROFILE, "Profile", Icons.Filled.Person),
)

private val tabRoutes = tabs.map { it.route }.toSet()

@Composable
private fun TasteRouteApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asked by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) scope.launch { LocationRepository.refresh(context) }
        else AppState.locationStatus = LocationStatus.DENIED
    }

    val requestLocation = remember {
        {
            if (LocationRepository.hasPermission(context)) scope.launch { LocationRepository.refresh(context) }
            else permissionLauncher.launch(LocationRepository.permissions)
            Unit
        }
    }

    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            requestLocation()
        }
    }

    // One race across the candidate models, only when nothing has been chosen yet. Off the
    // critical path and 12 tokens per candidate, so it costs nothing worth measuring.
    LaunchedEffect(Unit) { NimClient.ensureModelChosen() }

    // Warm the place cache the moment a fix lands, so Discover has data before it is ever shown.
    LaunchedEffect(AppState.origin, AppState.tier) {
        AppState.origin?.let { Recommender.prefetch(it, AppState.tier) }
    }

    // Picks the fix back up after the user grants permission or enables GPS in system settings.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                AppState.locationStatus != LocationStatus.READY &&
                LocationRepository.hasPermission(context)
            ) {
                scope.launch { LocationRepository.refresh(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalRequestLocation provides requestLocation) {
        if (!AppState.welcomed) {
            WelcomeScreen(onContinue = { AppState.setWelcomed() })
        } else if (!AppState.onboarded) {
            TasteSetupScreen(onDone = {}, isFirstRun = true)
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (current in tabRoutes) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            tabs.forEach { tab ->
                                NavigationBarItem(
                                    selected = current == tab.route,
                                    onClick = { nav.switchTab(tab.route) },
                                    icon = { Icon(tab.icon, tab.label) },
                                    label = { Text(tab.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                // Tabs cross-fade instantly; only pushed screens get the genie, which is what makes
                // it read as "this card opened" instead of a generic page animation.
                // Tabs cross-fade in 90ms. Only pushed screens run the genie, and only they hold
                // two screens in composition at once — a tab switch used to pay for both.
                NavHost(
                    navController = nav,
                    startDestination = DISCOVER,
                    modifier = Modifier.padding(padding),
                    enterTransition = { fadeIn(tween(TAB_FADE_MS)) },
                    exitTransition = { fadeOut(tween(TAB_FADE_MS)) },
                    popEnterTransition = { fadeIn(tween(TAB_FADE_MS)) },
                    popExitTransition = { fadeOut(tween(TAB_FADE_MS)) },
                ) {
                    composable(DISCOVER) {
                        HomeScreen(
                            onOpenPlace = { nav.navigate(DETAIL) },
                            onUpgrade = { nav.switchTab(PROFILE) },
                            onRetune = { nav.push(TASTE) },
                            onTableSync = { nav.push(TABLE_SYNC) },
                            onEditAllergens = { nav.switchTab(PROFILE) },
                        )
                    }
                    composable(ASSISTANT) {
                        AssistantScreen(onOpenPlace = { nav.navigate(DETAIL) })
                    }
                    composable(PROFILE) {
                        SettingsScreen(
                            onRetune = { nav.push(TASTE) },
                            onAccount = { nav.push(ACCOUNT) },
                            onAbout = { nav.push(ABOUT) },
                        )
                    }
                    // Pushed on top of whichever tab you came from, so back returns there.
                    genieScreen(DETAIL) {
                        PlaceDetailScreen(
                            onBack = { nav.popBackStack() },
                            onRoute = { nav.navigate(ROUTE) },
                            onCheckIn = { nav.navigate(CHECK_IN) },
                            onSignIn = { nav.push(ACCOUNT) },
                            onWriteReview = { nav.push(REVIEW) },
                        )
                    }
                    genieScreen(ROUTE) {
                        MapRouteScreen(
                            onBack = { nav.popBackStack() },
                            onNavigate = { nav.navigate(NAVIGATE) },
                        )
                    }
                    // Deliberately not a genieScreen: the genie holds both screens in composition
                    // for its whole duration, and these two are the heaviest in the app.
                    composable(NAVIGATE) {
                        NavigationScreen(onBack = { nav.popBackStack() })
                    }
                    genieScreen(CHECK_IN) {
                        CheckInScreen(onBack = { nav.popBackStack() })
                    }
                    genieScreen(REVIEW) {
                        ReviewScreen(onBack = { nav.popBackStack() })
                    }
                    genieScreen(TASTE) {
                        TasteSetupScreen(onDone = { nav.popBackStack() }, isFirstRun = false)
                    }
                    genieScreen(ABOUT) {
                        com.example.tasteroute.ui.settings.AboutScreen(onBack = { nav.popBackStack() })
                    }
                    genieScreen(ACCOUNT) {
                        AccountScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
                    }
                    genieScreen(TABLE_SYNC) {
                        TableSyncScreen(
                            onBack = { nav.popBackStack() },
                            onOpenPlace = { nav.navigate(DETAIL) },
                            onSignIn = { nav.push(ACCOUNT) },
                        )
                    }
                }
            }
        }
    }
}

/** A pushed destination wrapped in the genie warp. */
private fun NavGraphBuilder.genieScreen(
    route: String,
    content: @Composable () -> Unit,
) = composable(
    route = route,
    enterTransition = { genieEnter },
    exitTransition = { genieExit },
    popEnterTransition = { genieEnter },
    popExitTransition = { genieExit },
) { GenieContainer { content() } }

/** Tab switches replace the tab stack; everything else is a push so back is always meaningful. */
private fun NavHostController.switchTab(route: String) {
    Genie.anchorToCenter()
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** A push with no card behind it — the genie comes from the middle rather than a stale anchor. */
private fun NavHostController.push(route: String) {
    Genie.anchorToCenter()
    navigate(route)
}
