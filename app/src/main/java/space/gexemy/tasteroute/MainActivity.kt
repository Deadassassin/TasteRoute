package space.gexemy.tasteroute

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import space.gexemy.tasteroute.data.AppState
import space.gexemy.tasteroute.data.LocationRepository
import space.gexemy.tasteroute.data.MallFinder
import space.gexemy.tasteroute.data.LocationStatus
import space.gexemy.tasteroute.data.NimClient
import space.gexemy.tasteroute.data.Perf
import space.gexemy.tasteroute.data.Recommender
import space.gexemy.tasteroute.data.Social
import space.gexemy.tasteroute.ui.account.AccountScreen
import space.gexemy.tasteroute.ui.chat.AssistantScreen
import space.gexemy.tasteroute.ui.components.AiFab
import space.gexemy.tasteroute.ui.components.AiFabOverhang
import space.gexemy.tasteroute.ui.components.LocalRequestLocation
import space.gexemy.tasteroute.ui.crowd.CheckInScreen
import space.gexemy.tasteroute.ui.detail.MenuScreen
import space.gexemy.tasteroute.ui.detail.PlaceDetailScreen
import space.gexemy.tasteroute.ui.detail.ReviewScreen
import space.gexemy.tasteroute.ui.group.TableSyncScreen
import space.gexemy.tasteroute.ui.home.HomeScreen
import space.gexemy.tasteroute.ui.mall.MallScreen
import space.gexemy.tasteroute.ui.map.MapRouteScreen
import space.gexemy.tasteroute.ui.map.NavigationScreen
import space.gexemy.tasteroute.ui.onboarding.TasteSetupScreen
import space.gexemy.tasteroute.ui.profile.FriendProfileScreen
import space.gexemy.tasteroute.ui.profile.FriendsScreen
import space.gexemy.tasteroute.ui.profile.ProfileScreen
import space.gexemy.tasteroute.ui.onboarding.WelcomeScreen
import space.gexemy.tasteroute.ui.settings.SettingsScreen
import space.gexemy.tasteroute.ui.theme.TasteRouteTheme
import space.gexemy.tasteroute.ui.theme.popEnter
import space.gexemy.tasteroute.ui.theme.popExit
import space.gexemy.tasteroute.ui.theme.pushEnter
import space.gexemy.tasteroute.ui.theme.pushExit
import space.gexemy.tasteroute.ui.theme.tabEnter
import space.gexemy.tasteroute.ui.theme.tabExit
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Started here and not in Application: the frame budget is the panel's own refresh rate,
        // and there is no display to ask before an Activity exists.
        @Suppress("DEPRECATION")
        Perf.observeFrames(windowManager.defaultDisplay?.refreshRate ?: 60f)
        setContent {
            TasteRouteTheme {
                TasteRouteApp()
            }
        }
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
private const val MENU = "menu"
private const val MALL = "mall"
private const val ABOUT = "about"
private const val SETTINGS = "settings"
private const val FRIENDS = "friends"
private const val FRIEND = "friend"

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * The two flanking destinations. The assistant is deliberately NOT one of them: it is the raised
 * button between them, because it is the only tab that is also an action. Sitting in the row it
 * was one of three equal things; in the centre it is the thing the app is for, and — once you are
 * in it — the way to start over. See [AiFab].
 */
private val tabs = listOf(
    Tab(DISCOVER, "Discover", Icons.Filled.Home),
    Tab(PROFILE, "Profile", Icons.Filled.Person),
)

/** ASSISTANT is not in [tabs] but is still a tab: the bar shows on it and it saves its state. */
private val tabRoutes = tabs.map { it.route }.toSet() + ASSISTANT

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
        if (grants.values.any { it }) scope.launch {
            LocationRepository.refresh(context)
            LocationRepository.startUpdates(context)
        } else {
            AppState.locationStatus = LocationStatus.DENIED
        }
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

    // The model auto-pick, and it runs at EVERY open rather than only on first run: the server
    // says which ids still answer, then the survivors are raced here. Off the critical path - one
    // small GET to our own host, then 12 tokens per candidate - and the reason a model retired
    // after this APK shipped no longer outlives the install.
    LaunchedEffect(Unit) { NimClient.ensureModelChosen() }

    // Warm the place cache the moment a fix lands, so Discover has data before it is ever shown.
    // Keyed on searchOrigin, not origin: origin now changes every fifteen seconds and this would
    // otherwise re-run the whole provider call every time the person shifted in their chair.
    LaunchedEffect(AppState.searchOrigin, AppState.tier) {
        AppState.searchOrigin?.let { Recommender.prefetch(it, AppState.tier) }
    }

    // "Am I in a mall?" asked once per latched origin, which is at most every 450m or five
    // minutes. Keyed on searchOrigin for exactly the reason the prefetch above is: the live fix
    // moves every fifteen seconds, and a containment query per fix is a request storm aimed at
    // the free Overpass mirrors that are also the app's fallback when our own API is down.
    LaunchedEffect(AppState.searchOrigin) {
        AppState.mall = AppState.searchOrigin?.let { MallFinder.detect(it) }
    }

    // Follows the person while the app is in front of them, and — just as importantly — stops the
    // moment it is not. A location callback that outlives the foreground is a battery complaint
    // with a delay on it. ON_RESUME also picks the fix back up after the user grants permission or
    // enables GPS in system settings, which is what this block originally existed for.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (LocationRepository.hasPermission(context)) {
                        if (AppState.locationStatus != LocationStatus.READY) {
                            scope.launch { LocationRepository.refresh(context) }
                        }
                        LocationRepository.startUpdates(context)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> LocationRepository.stopUpdates(context)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            LocationRepository.stopUpdates(context)
        }
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
                        val onAssistant = current == ASSISTANT
                        // The button overhangs the bar, and a composable drawn outside its
                        // parent's measured bounds does not receive touches. So the Box is grown
                        // by the overhang and the BAR is pushed down into it, rather than the
                        // button being offset up out of it: same picture, and the whole 56dp
                        // target is inside something that was measured.
                        Box(Modifier.fillMaxWidth()) {
                            NavigationBar(
                                modifier = Modifier.padding(top = AiFabOverhang),
                                containerColor = MaterialTheme.colorScheme.surface,
                            ) {
                                tabs.forEachIndexed { index, tab ->
                                    NavigationBarItem(
                                        selected = current == tab.route,
                                        onClick = { nav.switchTab(tab.route) },
                                        icon = { Icon(tab.icon, tab.label) },
                                        // Material leaves the label a full indicator's worth of
                                        // air below a 24dp icon, which reads as two unrelated
                                        // controls stacked. Nudged up rather than restyled: the
                                        // item keeps its own metrics and its touch target.
                                        label = { Text(tab.label, Modifier.offset(y = (-3).dp)) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        ),
                                    )
                                    // The gap the button sits in. A weighted spacer rather than a
                                    // fixed width so the two items stay centred in their own
                                    // halves at any screen width.
                                    if (index == 0) Spacer(Modifier.weight(1f))
                                }
                            }
                            AiFab(
                                onAssistant = onAssistant,
                                busy = AppState.assistantBusy,
                                onClick = {
                                    if (onAssistant) AppState.clearChat() else nav.switchTab(ASSISTANT)
                                },
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        }
                    }
                },
            ) { padding ->
                // Tabs fade through one another, pushes travel along a shared X axis. Two
                // different relationships between two screens, so two different transitions —
                // and only a push holds both screens in composition at once. See Motion.kt.
                NavHost(
                    navController = nav,
                    startDestination = DISCOVER,
                    modifier = Modifier.padding(padding),
                    enterTransition = { tabEnter() },
                    exitTransition = { tabExit() },
                    popEnterTransition = { tabEnter() },
                    popExitTransition = { tabExit() },
                ) {
                    composable(DISCOVER) {
                        HomeScreen(
                            onOpenPlace = { nav.navigate(DETAIL) },
                            onUpgrade = { nav.push(SETTINGS) },
                            onRetune = { nav.push(TASTE) },
                            onTableSync = { nav.push(TABLE_SYNC) },
                            onEditAllergens = { nav.push(SETTINGS) },
                            onMall = { nav.push(MALL) },
                        )
                    }
                    composable(ASSISTANT) {
                        AssistantScreen(onOpenPlace = { nav.navigate(DETAIL) })
                    }
                    composable(PROFILE) {
                        ProfileScreen(
                            onSettings = { nav.push(SETTINGS) },
                            onAccount = { nav.push(ACCOUNT) },
                            onRetune = { nav.push(TASTE) },
                            onFriends = { nav.push(FRIENDS) },
                            onOpenFriend = { id ->
                                Social.openFriendId = id
                                nav.push(FRIEND)
                            },
                            onOpenPlace = { nav.navigate(DETAIL) },
                        )
                    }
                    // Pushed on top of whichever tab you came from, so back returns there.
                    pushScreen(DETAIL) {
                        PlaceDetailScreen(
                            onBack = { nav.popBackStack() },
                            onRoute = { nav.navigate(ROUTE) },
                            onMenu = { nav.push(MENU) },
                            onCheckIn = { nav.navigate(CHECK_IN) },
                            onSignIn = { nav.push(ACCOUNT) },
                            onWriteReview = { nav.push(REVIEW) },
                        )
                    }
                    pushScreen(MENU) {
                        MenuScreen(onBack = { nav.popBackStack() })
                    }
                    pushScreen(MALL) {
                        MallScreen(
                            onBack = { nav.popBackStack() },
                            onOpenPlace = { nav.navigate(DETAIL) },
                        )
                    }
                    pushScreen(ROUTE) {
                        MapRouteScreen(
                            onBack = { nav.popBackStack() },
                            onNavigate = { nav.navigate(NAVIGATE) },
                            onOpenPlace = { nav.navigate(DETAIL) },
                        )
                    }
                    // Deliberately not a pushScreen: a transition holds both screens in
                    // composition for its whole duration, and these two are the heaviest here.
                    composable(NAVIGATE) {
                        NavigationScreen(onBack = { nav.popBackStack() })
                    }
                    pushScreen(CHECK_IN) {
                        CheckInScreen(onBack = { nav.popBackStack() })
                    }
                    pushScreen(REVIEW) {
                        ReviewScreen(onBack = { nav.popBackStack() })
                    }
                    pushScreen(TASTE) {
                        TasteSetupScreen(onDone = { nav.popBackStack() }, isFirstRun = false)
                    }
                    pushScreen(SETTINGS) {
                        SettingsScreen(
                            onRetune = { nav.push(TASTE) },
                            onAccount = { nav.push(ACCOUNT) },
                            onAbout = { nav.push(ABOUT) },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    pushScreen(FRIENDS) {
                        FriendsScreen(
                            onBack = { nav.popBackStack() },
                            onOpenFriend = { id ->
                                Social.openFriendId = id
                                nav.push(FRIEND)
                            },
                        )
                    }
                    pushScreen(FRIEND) {
                        FriendProfileScreen(Social.openFriendId, onBack = { nav.popBackStack() })
                    }
                    pushScreen(ABOUT) {
                        space.gexemy.tasteroute.ui.settings.AboutScreen(onBack = { nav.popBackStack() })
                    }
                    pushScreen(ACCOUNT) {
                        AccountScreen(onBack = { nav.popBackStack() }, onDone = { nav.popBackStack() })
                    }
                    pushScreen(TABLE_SYNC) {
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

/** A pushed destination: in along the shared axis, back out the way it came. */
private fun NavGraphBuilder.pushScreen(
    route: String,
    content: @Composable () -> Unit,
) = composable(
    route = route,
    enterTransition = { pushEnter() },
    exitTransition = { pushExit() },
    popEnterTransition = { popEnter() },
    popExitTransition = { popExit() },
) { content() }

/** Tab switches replace the tab stack; everything else is a push so back is always meaningful. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Everything that is not a tab is a push, so Back always means something. */
private fun NavHostController.push(route: String) {
    navigate(route)
}
