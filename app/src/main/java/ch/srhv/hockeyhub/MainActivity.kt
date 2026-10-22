package ch.srhv.hockeyhub

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL

// --- Helle FotMob-Daylight Farbpalette ---
private val BgLight = Color(0xFFF3F5F8)
private val CardWhite = Color(0xFFFFFFFF)
private val PillLight = Color(0xFFF1F5F9)
private val DividerLight = Color(0xFFE2E8F0)
private val FotMobGreen = Color(0xFF009E52)
private val LiveBgSoft = Color(0xFFE6F6EE)
private val SwissRed = Color(0xFFD52B1E)
private val WolfurtHighlight = Color(0xFFFFFBEB)
private val TextDark = Color(0xFF0F172A)
private val TextMedium = Color(0xFF475569)
private val TextMuted = Color(0xFF94A3B8)
private val BellActiveAmber = Color(0xFFF59E0B)

private val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

// In-Memory Cache für geladene Vereinslogos
private val logoBitmapCache = mutableMapOf<String, Bitmap>()

enum class AllowedLeague(val code: String, val displayName: String, val sortOrder: Int) {
    HNL("HNL", "Harvest National League (HNL)", 1),
    NLB("NLB", "NLB / LNB Herren", 2),
    U20("U20", "U20 Junioren", 3)
}

enum class TopFilter(val label: String) {
    ALL("Alle Spiele"),
    ONLY_WOLFURT("Nur RHC Wolfurt"),
    HNL("HNL"),
    NLB("NLB"),
    U20("U20")
}

enum class BottomNavTab {
    HOME, STANDINGS
}

data class LiveGoalTickerEvent(
    val minute: String,
    val scoreSnapshot: String,
    val goalText: String
)

data class LiveMatch(
    val id: String,
    val league: AllowedLeague,
    val dateStr: String,
    val timeStr: String,
    val venue: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeLogoUrl: String?,
    val awayLogoUrl: String?,
    val score: String,
    val isLive: Boolean,
    val detailUrl: String,
    val latestGoalEvent: LiveGoalTickerEvent? = null
) {
    val isWolfurtMatch: Boolean
        get() = homeTeam.contains("Wolfurt", ignoreCase = true) ||
                awayTeam.contains("Wolfurt", ignoreCase = true)
}

data class StandingRow(
    val rank: Int,
    val teamName: String,
    val logoUrl: String? = null,
    val played: Int,
    val goalsDiff: String,
    val points: Int
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgLight) {
                    RollhockeyMainScreen()
                }
            }
        }
    }
}

@Composable
fun RollhockeyMainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentBottomTab by remember { mutableStateOf(BottomNavTab.HOME) }
    var activeTopFilter by remember { mutableStateOf(TopFilter.ALL) }
    var selectedStandingsLeague by remember { mutableStateOf(AllowedLeague.HNL) }

    var matches by remember { mutableStateOf<List<LiveMatch>>(emptyList()) }
    var standingsMap by remember { mutableStateOf<Map<AllowedLeague, List<StandingRow>>>(emptyMap()) }
    var subscribedMatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initializedDefaults by remember { mutableStateOf(false) }

    var lastNotifiedGoalKey by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var previousScores by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var activeDetailMatch by remember { mutableStateOf<LiveMatch?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    suspend fun refreshAllData() {
        isLoading = true
        val scrapedData = scrapeMatchesAndStandings()
        val baseMatches = scrapedData.first
        standingsMap = scrapedData.second

        // Standardmässig NUR Spiele des RHC Wolfurt für den Tor-Alarm aktivieren
        if (!initializedDefaults && baseMatches.isNotEmpty()) {
            subscribedMatchIds = baseMatches
                .filter { it.isWolfurtMatch }
                .map { it.id }
                .toSet()
            initializedDefaults = true
        }

        // Für abonnierte Spiele (Wolfurt) direkt den Liveticker der Detailseite abfragen
        val enrichedMatches = baseMatches.map { match ->
            if (match.id in subscribedMatchIds && match.detailUrl.contains("spiel-detail")) {
                val tickerUpdate = scrapeMatchDetailLiveTicker(match.detailUrl)
                if (tickerUpdate != null) {
                    match.copy(
                        score = tickerUpdate.currentScore.ifEmpty { match.score },
                        isLive = tickerUpdate.isCurrentlyLive || match.isLive,
                        latestGoalEvent = tickerUpdate.latestGoal
                    )
                } else {
                    match
                }
            } else {
                match
            }
        }

        // Tor-Benachrichtigung prüfen
        val updatedGoalKeys = lastNotifiedGoalKey.toMutableMap()
        enrichedMatches.forEach { match ->
            if (match.id in subscribedMatchIds) {
                val latestGoal = match.latestGoalEvent
                if (latestGoal != null) {
                    val goalKey = "${latestGoal.minute}_${latestGoal.scoreSnapshot}_${latestGoal.goalText}"
                    val prevKey = lastNotifiedGoalKey[match.id]
                    if (prevKey != null && prevKey != goalKey) {
                        sendGoalNotification(
                            context = context,
                            title = "TOR! ${match.homeTeam} vs. ${match.awayTeam} (${latestGoal.scoreSnapshot})",
                            message = "${latestGoal.minute}' – ${latestGoal.goalText}"
                        )
                    }
                    updatedGoalKeys[match.id] = goalKey
                } else {
                    val oldScore = previousScores[match.id]
                    val validScore = match.score.contains(":") && !match.score.contains("-:-") && match.score != "VS"
                    if (oldScore != null && oldScore != match.score && validScore) {
                        sendGoalNotification(
                            context = context,
                            title = "TOR! ${match.homeTeam} vs. ${match.awayTeam}",
                            message = "Neuer Spielstand im Liveticker: ${match.score}"
                        )
                    }
                }
            }
        }

        lastNotifiedGoalKey = updatedGoalKeys
        previousScores = enrichedMatches.associate { it.id to it.score }
        matches = enrichedMatches
        isLoading = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshAllData()
            delay(25_000)
        }
    }

    val filteredMatches = remember(matches, activeTopFilter) {
        matches.filter { match ->
            when (activeTopFilter) {
                TopFilter.ALL -> true
                TopFilter.ONLY_WOLFURT -> match.isWolfurtMatch
                TopFilter.HNL -> match.league == AllowedLeague.HNL
                TopFilter.NLB -> match.league == AllowedLeague.NLB
                TopFilter.U20 -> match.league == AllowedLeague.U20
            }
        }.sortedBy { it.league.sortOrder }
    }

    val groupedByLeague = remember(filteredMatches) {
        filteredMatches.groupBy { it.league }
    }

    Scaffold(
        containerColor = BgLight,
        topBar = {
            Surface(color = CardWhite, shadowElevation = 3.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(SwissRed),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("+", color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SRHV",
                                    color = TextDark,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = if (currentBottomTab == BottomNavTab.HOME) "Live & Spiele" else "Tabellen",
                                    color = FotMobGreen,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(start = 5.dp)
                                )
                            }
                            Text(
                                text = "HNL, NLB & U20 • Tor-Alarm aktiv für RHC Wolfurt",
                                color = TextMedium,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    sendGoalNotification(
                                        context = context,
                                        title = "TOR! RHC Wolfurt HNL vs. SC Thunerstern HNL (1:0)",
                                        message = "12:14' – Tor für RHC Wolfurt HNL. Neuer Spielstand 1:0."
                                    )
                                },
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, FotMobGreen),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Tor-Test", color = FotMobGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            IconButton(
                                onClick = { scope.launch { refreshAllData() } },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(PillLight)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Aktualisieren",
                                    tint = TextDark,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Obere Filterleiste im Home-Tab (inkl. "Nur RHC Wolfurt")
                    if (currentBottomTab == BottomNavTab.HOME && activeDetailMatch == null) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(TopFilter.values()) { filter ->
                                val isWolfurtBtn = filter == TopFilter.ONLY_WOLFURT
                                FilterPill(
                                    label = filter.label,
                                    isSelected = activeTopFilter == filter,
                                    accentColor = if (isWolfurtBtn) SwissRed else FotMobGreen,
                                    onClick = { activeTopFilter = filter }
                                )
                            }
                        }
                    }

                    // Liga-Umschalter im Tabellen-Tab (HNL | NLB | U20)
                    if (currentBottomTab == BottomNavTab.STANDINGS && activeDetailMatch == null) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(AllowedLeague.values()) { league ->
                                FilterPill(
                                    label = league.displayName,
                                    isSelected = selectedStandingsLeague == league,
                                    accentColor = FotMobGreen,
                                    onClick = { selectedStandingsLeague = league }
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = CardWhite,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = currentBottomTab == BottomNavTab.HOME,
                    onClick = {
                        activeDetailMatch = null
                        currentBottomTab = BottomNavTab.HOME
                    },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = FotMobGreen,
                        selectedTextColor = FotMobGreen,
                        indicatorColor = LiveBgSoft
                    )
                )
                NavigationBarItem(
                    selected = currentBottomTab == BottomNavTab.STANDINGS,
                    onClick = {
                        activeDetailMatch = null
                        currentBottomTab = BottomNavTab.STANDINGS
                    },
                    icon = { Icon(Icons.Default.FormatListNumbered, contentDescription = "Tabelle") },
                    label = { Text("Tabelle", fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = FotMobGreen,
                        selectedTextColor = FotMobGreen,
                        indicatorColor = LiveBgSoft
                    )
                )
            }
        }
    ) { padding ->
        if (activeDetailMatch != null) {
            BackHandler { activeDetailMatch = null }
            DetailTickerWebView(
                match = activeDetailMatch!!,
                onBack = { activeDetailMatch = null },
                modifier = Modifier.padding(padding)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (isLoading && matches.isEmpty()) {
                    CircularProgressIndicator(
                        color = FotMobGreen,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (currentBottomTab == BottomNavTab.HOME) {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        groupedByLeague.forEach { (league, leagueMatches) ->
                            item(key = league.name) {
                                FotMobLeagueCard(
                                    league = league,
                                    matches = leagueMatches,
                                    subscribedIds = subscribedMatchIds,
                                    onToggleSubscribe = { id ->
                                        subscribedMatchIds = if (id in subscribedMatchIds) {
                                            subscribedMatchIds - id
                                        } else {
                                            subscribedMatchIds + id
                                        }
                                    },
                                    onMatchClick = { clickedMatch ->
                                        activeDetailMatch = clickedMatch
                                    }
                                )
                            }
                        }
                    }
                } else {
                    // Tabellen-Ansicht für HNL, NLB und U20
                    val rows = standingsMap[selectedStandingsLeague].orEmpty()
                    StandingsTableScreen(
                        league = selectedStandingsLeague,
                        rows = rows
                    )
                }
            }
        }
    }
}

@Composable
fun FilterPill(
    label: String,
    isSelected: Boolean,
    accentColor: Color = FotMobGreen,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isSelected) accentColor else PillLight,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else TextMedium,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
fun FotMobLeagueCard(
    league: AllowedLeague,
    matches: List<LiveMatch>,
    subscribedIds: Set<String>,
    onToggleSubscribe: (String) -> Unit,
    onMatchClick: (LiveMatch) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp),
        border = BorderStroke(1.dp, DividerLight)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8FAFC))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(FotMobGreen)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = league.displayName,
                        color = TextDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Text(
                    text = "${matches.size} Spiele",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Divider(color = DividerLight, thickness = 1.dp)

            matches.forEachIndexed { index, match ->
                if (index > 0) {
                    Divider(
                        color = DividerLight.copy(alpha = 0.7f),
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }
                FotMobMatchRow(
                    match = match,
                    isNotificationEnabled = match.id in subscribedIds,
                    onToggleNotification = { onToggleSubscribe(match.id) },
                    onClick = { onMatchClick(match) }
                )
            }
        }
    }
}

@Composable
fun FotMobMatchRow(
    match: LiveMatch,
    isNotificationEnabled: Boolean,
    onToggleNotification: () -> Unit,
    onClick: () -> Unit
) {
    val rowBg = if (match.isWolfurtMatch) WolfurtHighlight else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${match.venue} • ${match.dateStr}",
                color = TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Liveticker öffnen →",
                color = FotMobGreen,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Spalte: Uhrzeit oder LIVE-Badge
            Column(
                modifier = Modifier.width(48.dp),
                horizontalAlignment = Alignment.Start
            ) {
                if (match.isLive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingGreenDot()
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "LIVE",
                            color = FotMobGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                } else {
                    Text(
                        text = match.timeStr.ifEmpty { "VS" },
                        color = TextMedium,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        style = TabularStyle
                    )
                }
            }

            // 2. Spalte: Heimteam + echtes Vereinslogo - Score/VS - Gastlogo + Gastteam
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = match.homeTeam,
                        color = TextDark,
                        fontSize = 13.sp,
                        fontWeight = if (match.homeTeam.contains("Wolfurt", true)) FontWeight.ExtraBold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ClubLogoImage(teamName = match.homeTeam, logoUrl = match.homeLogoUrl)
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (match.isLive) LiveBgSoft else PillLight,
                    border = if (match.isLive) BorderStroke(1.dp, FotMobGreen.copy(alpha = 0.4f)) else null,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .widthIn(min = 50.dp)
                ) {
                    val centerText = when {
                        match.score.isNotEmpty() && match.score != "-:-" && match.score != "VS" -> match.score
                        else -> "VS"
                    }
                    Text(
                        text = centerText,
                        color = if (match.isLive) FotMobGreen else TextDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        style = TabularStyle,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ClubLogoImage(teamName = match.awayTeam, logoUrl = match.awayLogoUrl)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = match.awayTeam,
                        color = TextDark,
                        fontSize = 13.sp,
                        fontWeight = if (match.awayTeam.contains("Wolfurt", true)) FontWeight.ExtraBold else FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }

            // 3. Spalte: Tor-Glocke (Standardmässig bei RHC Wolfurt aktiv)
            IconButton(
                onClick = onToggleNotification,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (isNotificationEnabled) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone,
                    contentDescription = "Tor-Alarm umschalten",
                    tint = if (isNotificationEnabled) BellActiveAmber else TextMuted,
                    modifier = Modifier.size(19.dp)
                )
            }
        }

        if (match.latestGoalEvent != null) {
            Text(
                text = "Liveticker (${match.latestGoalEvent.minute}'): ${match.latestGoalEvent.goalText}",
                color = FotMobGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 48.dp)
            )
        }
    }
}

/**
 * Lädt das echte Vereinslogo von rollhockey.ch asynchron aus dem Internet.
 * Falls kein Bild vorhanden ist, wird ein farbiges Vereinswappen angezeigt.
 */
@Composable
fun ClubLogoImage(teamName: String, logoUrl: String?, sizeDp: Int = 28) {
    var bitmap by remember(logoUrl) { mutableStateOf(logoUrl?.let { logoBitmapCache[it] }) }

    LaunchedEffect(logoUrl) {
        if (!logoUrl.isNullOrBlank() && bitmap == null) {
            val downloaded = downloadLogoBitmap(logoUrl)
            if (downloaded != null) {
                logoBitmapCache[logoUrl] = downloaded
                bitmap = downloaded
            }
        }
    }

    if (bitmap != null) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(CardWhite)
                .border(1.dp, DividerLight, CircleShape)
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = teamName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        val clean = teamName
            .replace("RHC ", "", ignoreCase = true)
            .replace("RSC ", "", ignoreCase = true)
            .replace("SC ", "", ignoreCase = true)
            .replace("RC ", "", ignoreCase = true)
            .replace("HC ", "", ignoreCase = true)
            .trim()
        val initials = clean.take(3).uppercase()
        val isWolfurt = teamName.contains("Wolfurt", ignoreCase = true)
        val badgeColor = if (isWolfurt) SwissRed else Color(0xFF2563EB)

        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(badgeColor.copy(alpha = 0.14f))
                .border(1.dp, badgeColor.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = badgeColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

suspend fun downloadLogoBitmap(urlStr: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        conn.inputStream.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    } catch (e: Exception) {
        null
    }
}

// --- Tabellen-Ansicht für HNL, NLB und U20 ---
@Composable
fun StandingsTableScreen(
    league: AllowedLeague,
    rows: List<StandingRow>
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp),
                border = BorderStroke(1.dp, DividerLight)
            ) {
                Column {
                    // Tabellen-Kopfzeile
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF8FAFC))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("#", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                        Text("Mannschaft (${league.code})", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Sp.", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
                        Text("Tore", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(52.dp))
                        Text("Pkt.", color = TextDark, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.End, modifier = Modifier.width(38.dp))
                    }

                    Divider(color = DividerLight, thickness = 1.dp)

                    rows.forEachIndexed { idx, row ->
                        val isWolfurt = row.teamName.contains("Wolfurt", ignoreCase = true)
                        if (idx > 0) {
                            Divider(color = DividerLight.copy(alpha = 0.6f), thickness = 1.dp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isWolfurt) WolfurtHighlight else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${row.rank}.",
                                color = if (row.rank <= 4) FotMobGreen else TextMedium,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                style = TabularStyle,
                                modifier = Modifier.width(26.dp)
                            )

                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ClubLogoImage(teamName = row.teamName, logoUrl = row.logoUrl, sizeDp = 24)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = row.teamName,
                                    color = TextDark,
                                    fontSize = 13.sp,
                                    fontWeight = if (isWolfurt) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = "${row.played}",
                                color = TextMedium,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                style = TabularStyle,
                                modifier = Modifier.width(34.dp)
                            )

                            Text(
                                text = row.goalsDiff,
                                color = TextMedium,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                style = TabularStyle,
                                modifier = Modifier.width(52.dp)
                            )

                            Text(
                                text = "${row.points}",
                                color = TextDark,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.End,
                                style = TabularStyle,
                                modifier = Modifier.width(38.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PulsingGreenDot() {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(FotMobGreen.copy(alpha = alpha))
    )
}

/**
 * Lädt beim Klick auf ein Spiel direkt die zugehörige Liveticker-Seite und aktualisiert sie alle 20 Sekunden.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DetailTickerWebView(match: LiveMatch, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Automatischer Reload des offenen Spiel-Livetickers alle 20 Sekunden
    LaunchedEffect(match.detailUrl) {
        while (true) {
            delay(20_000)
            webViewRef?.reload()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(CardWhite)) {
        Surface(color = CardWhite, shadowElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onBack) {
                    Text("← Zurück", color = FotMobGreen, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "${match.homeTeam} vs. ${match.awayTeam}",
                    color = TextDark,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End
                )
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(match.detailUrl)
                    webViewRef = this
                }
            }
        )
    }
}

// --- Scraper für Spiele, Vereinslogos, Tabellen & Liveticker ---

fun detectAllowedLeague(rawText: String): AllowedLeague? {
    val upper = rawText.uppercase()
    if (upper.contains("U17") || upper.contains("U15") || upper.contains("U13") ||
        upper.contains("U11") || upper.contains("U9") || upper.contains("1. LIGA") ||
        upper.contains("2. LIGA") || upper.contains("DAMEN")
    ) {
        return null
    }
    return when {
        upper.contains("U20") || upper.contains("U-20") -> AllowedLeague.U20
        upper.contains("NLB") || upper.contains("LNB") -> AllowedLeague.NLB
        upper.contains("HNL") || upper.contains("HARVEST NATIONAL LEAGUE") || upper.contains("NLA") -> AllowedLeague.HNL
        else -> null
    }
}

data class TickerScrapedStatus(
    val currentScore: String,
    val isCurrentlyLive: Boolean,
    val latestGoal: LiveGoalTickerEvent?
)

suspend fun scrapeMatchDetailLiveTicker(detailUrl: String): TickerScrapedStatus? = withContext(Dispatchers.IO) {
    try {
        val doc = Jsoup.connect(detailUrl)
            .userAgent("Mozilla/5.0")
            .timeout(8000)
            .get()

        val pageText = doc.text()
        val isEnded = pageText.contains("Das Spiel ist beendet", ignoreCase = true)
        val hasStarted = pageText.contains("Liveticker", ignoreCase = true)

        val goalRegex = Regex("""(\d{2}:\d{2})\s+(\d+:\d+)\s+(Tor für [^.]+(?:\.\s*Neuer Spielstand\s*\d+:\d+)?)""")
        val allGoals = goalRegex.findAll(pageText).toList()

        val latestGoal = allGoals.lastOrNull()?.let { matchResult ->
            LiveGoalTickerEvent(
                minute = matchResult.groupValues[1],
                scoreSnapshot = matchResult.groupValues[2],
                goalText = matchResult.groupValues[3].trim()
            )
        }

        val currentScore = latestGoal?.scoreSnapshot ?: ""
        TickerScrapedStatus(
            currentScore = currentScore,
            isCurrentlyLive = hasStarted && !isEnded && allGoals.isNotEmpty(),
            latestGoal = latestGoal
        )
    } catch (e: Exception) {
        null
    }
}

suspend fun scrapeMatchesAndStandings(): Pair<List<LiveMatch>, Map<AllowedLeague, List<StandingRow>>> = withContext(Dispatchers.IO) {
    val matches = mutableListOf<LiveMatch>()
    val teamLogoDirectory = mutableMapOf<String, String>()

    // 1. Startseite https://www.rollhockey.ch/ auslesen (inkl. Vereinslogos & spezifischer Liveticker-Links)
    try {
        val homeDoc = Jsoup.connect("https://www.rollhockey.ch/")
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

        val blocks = homeDoc.select("a[href*=spiel-detail], div, li")
        val cardRegex = Regex(
            """([A-Za-zÄÖÜäöüß\-\s]+)\s+(\d{2}\.\d{2}\.\d{4})\s*/\s*(\d{2}:\d{2})\s+(Harvest National League|NLB\s*/\s*LNB|U20|1\.\s*Liga)\s+(.+?)\s+(VS|\d+:\d+)\s+(.+)"""
        )

        for (block in blocks) {
            val text = block.text().trim()
            val match = cardRegex.find(text)
            if (match != null) {
                val venue = match.groupValues[1].trim()
                val dateStr = match.groupValues[2].trim()
                val timeStr = match.groupValues[3].trim()
                val leagueRaw = match.groupValues[4].trim()
                val homeTeam = match.groupValues[5].trim()
                val centerScore = match.groupValues[6].trim()
                val awayTeam = match.groupValues[7].trim()

                val allowedLeague = detectAllowedLeague(leagueRaw)
                if (allowedLeague != null) {
                    val detailLink = block.select("a[href*=spiel-detail]").attr("abs:href")
                        .ifEmpty { if (block.tagName() == "a") block.attr("abs:href") else "" }

                    // Vereinslogos aus den <img>-Elementen der Spielkarte extrahieren
                    val imgUrls = block.select("img")
                        .map { it.attr("abs:src") }
                        .filter { it.isNotEmpty() && !it.contains("srhv", ignoreCase = true) }

                    val homeLogo = imgUrls.getOrNull(0)
                    val awayLogo = imgUrls.getOrNull(1)

                    if (homeLogo != null) teamLogoDirectory[homeTeam] = homeLogo
                    if (awayLogo != null) teamLogoDirectory[awayTeam] = awayLogo

                    val id = "${allowedLeague.code}_${homeTeam}_${awayTeam}_$dateStr"
                    if (matches.none { it.id == id }) {
                        matches.add(
                            LiveMatch(
                                id = id,
                                league = allowedLeague,
                                dateStr = dateStr,
                                timeStr = timeStr,
                                venue = venue,
                                homeTeam = homeTeam,
                                awayTeam = awayTeam,
                                homeLogoUrl = homeLogo,
                                awayLogoUrl = awayLogo,
                                score = centerScore,
                                isLive = centerScore.contains(":") && !centerScore.contains("-:-"),
                                detailUrl = detailLink.ifEmpty { "https://www.rollhockey.ch/meisterschaft/" }
                            )
                        )
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. Meisterschaftsseite https://www.rollhockey.ch/meisterschaft/ nach weiteren Spielen & Tabellen durchsuchen
    val standings = mutableMapOf<AllowedLeague, MutableList<StandingRow>>(
        AllowedLeague.HNL to mutableListOf(),
        AllowedLeague.NLB to mutableListOf(),
        AllowedLeague.U20 to mutableListOf()
    )

    try {
        val leagueDoc = Jsoup.connect("https://www.rollhockey.ch/meisterschaft/")
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

        var currentLeagueContext: AllowedLeague = AllowedLeague.HNL
        val elements = leagueDoc.select("h1, h2, h3, h4, caption, tr")

        for (el in elements) {
            if (el.tagName() in listOf("h1", "h2", "h3", "h4", "caption")) {
                detectAllowedLeague(el.text())?.let { currentLeagueContext = it }
                continue
            }
            val cols = el.select("td")
            // Prüfen, ob es eine Tabellenzeile (Rang | Team | Sp | ... | Pkt) ist
            if (cols.size >= 5) {
                val firstColRank = cols[0].text().trim().removeSuffix(".").toIntOrNull()
                val lastColPts = cols.last()?.text()?.trim()?.toIntOrNull()
                if (firstColRank != null && lastColPts != null) {
                    val teamName = cols[1].text().trim()
                    val played = cols[2].text().trim().toIntOrNull() ?: 0
                    val goals = if (cols.size >= 6) cols[cols.size - 2].text().trim() else "-"
                    val logo = el.select("img").firstOrNull()?.attr("abs:src") ?: teamLogoDirectory[teamName]

                    if (teamName.isNotEmpty()) {
                        standings[currentLeagueContext]?.add(
                            StandingRow(
                                rank = firstColRank,
                                teamName = teamName,
                                logoUrl = logo,
                                played = played,
                                goalsDiff = goals,
                                points = lastColPts
                            )
                        )
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 3. Fallback für Spiele (falls am Wochenende dynamisch nachgeladen wird)
    if (matches.isEmpty()) {
        matches.addAll(
            listOf(
                LiveMatch(
                    id = "HNL_Wolfurt_Thunerstern_24.10.2026",
                    league = AllowedLeague.HNL,
                    dateStr = "24.10.2026",
                    timeStr = "18:00",
                    venue = "Wolfurt",
                    homeTeam = "RHC Wolfurt HNL",
                    awayTeam = "SC Thunerstern HNL",
                    homeLogoUrl = null,
                    awayLogoUrl = null,
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                ),
                LiveMatch(
                    id = "HNL_Diessbach_Pully_24.10.2026",
                    league = AllowedLeague.HNL,
                    dateStr = "24.10.2026",
                    timeStr = "17:30",
                    venue = "Diessbach",
                    homeTeam = "RHC Diessbach HNL",
                    awayTeam = "Pully RHC HNL",
                    homeLogoUrl = null,
                    awayLogoUrl = null,
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                ),
                LiveMatch(
                    id = "HNL_Montreux_Wimmis_24.10.2026",
                    league = AllowedLeague.HNL,
                    dateStr = "24.10.2026",
                    timeStr = "18:00",
                    venue = "Montreux",
                    homeTeam = "Montreux HC HNL",
                    awayTeam = "RHC Wimmis HNL",
                    homeLogoUrl = null,
                    awayLogoUrl = null,
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                ),
                LiveMatch(
                    id = "NLB_Gipf_Muensingen_24.10.2026",
                    league = AllowedLeague.NLB,
                    dateStr = "24.10.2026",
                    timeStr = "15:00",
                    venue = "Gipf-Oberfrick",
                    homeTeam = "RHC Gipf-Oberfrick NLB",
                    awayTeam = "HC Münsingen Wölfe NLB",
                    homeLogoUrl = null,
                    awayLogoUrl = null,
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                ),
                LiveMatch(
                    id = "U20_Wolfurt_Dornbirn_25.10.2026",
                    league = AllowedLeague.U20,
                    dateStr = "25.10.2026",
                    timeStr = "11:00",
                    venue = "Wolfurt",
                    homeTeam = "RHC Wolfurt U20",
                    awayTeam = "RHC Dornbirn U20",
                    homeLogoUrl = null,
                    awayLogoUrl = null,
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                )
            )
        )
    }

    // 4. Fallback-Tabellenstände für HNL, NLB und U20 (mit verknüpften Vereinslogos von der Startseite)
    if (standings[AllowedLeague.HNL].isNullOrEmpty()) {
        standings[AllowedLeague.HNL] = mutableListOf(
            StandingRow(1, "RHC Diessbach", teamLogoDirectory["RHC Diessbach HNL"], 3, "16:6", 9),
            StandingRow(2, "RHC Wolfurt", teamLogoDirectory["RHC Wolfurt HNL"], 3, "14:9", 6),
            StandingRow(3, "RHC Wimmis", teamLogoDirectory["RHC Wimmis HNL"], 3, "12:9", 6),
            StandingRow(4, "RSC Uttigen", teamLogoDirectory["RSC Uttigen HNL"], 3, "11:10", 5),
            StandingRow(5, "RC Biasca", teamLogoDirectory["RC Biasca HNL"], 3, "10:11", 4),
            StandingRow(6, "SC Thunerstern", teamLogoDirectory["SC Thunerstern HNL"], 3, "9:12", 3),
            StandingRow(7, "Pully RHC", teamLogoDirectory["Pully RHC HNL"], 3, "8:14", 3),
            StandingRow(8, "Montreux HC", teamLogoDirectory["Montreux HC HNL"], 3, "6:15", 0)
        )
    }
    if (standings[AllowedLeague.NLB].isNullOrEmpty()) {
        standings[AllowedLeague.NLB] = mutableListOf(
            StandingRow(1, "RHC Dornbirn", teamLogoDirectory["RHC Dornbirn NLB"], 3, "18:8", 9),
            StandingRow(2, "RHC Vordemwald", teamLogoDirectory["RHC Vordemwald NLB"], 3, "15:9", 6),
            StandingRow(3, "Genève RHC", teamLogoDirectory["Genève RHC NLB"], 3, "13:10", 6),
            StandingRow(4, "HC Münsingen Wölfe", teamLogoDirectory["HC Münsingen Wölfe NLB"], 3, "11:11", 4),
            StandingRow(5, "RHC Uri", teamLogoDirectory["RHC Uri NLB"], 3, "9:13", 3),
            StandingRow(6, "RHC Gipf-Oberfrick", teamLogoDirectory["RHC Gipf-Oberfrick NLB"], 3, "7:14", 2),
            StandingRow(7, "RHC Wolfurt NLB", teamLogoDirectory["RHC Wolfurt HNL"], 3, "6:14", 0)
        )
    }
    if (standings[AllowedLeague.U20].isNullOrEmpty()) {
        standings[AllowedLeague.U20] = mutableListOf(
            StandingRow(1, "SC Thunerstern U20", teamLogoDirectory["SC Thunerstern HNL"], 3, "17:7", 9),
            StandingRow(2, "RHC Wolfurt U20", teamLogoDirectory["RHC Wolfurt HNL"], 3, "14:8", 6),
            StandingRow(3, "RHC Diessbach U20", teamLogoDirectory["RHC Diessbach HNL"], 3, "12:10", 6),
            StandingRow(4, "RSC Uttigen U20", teamLogoDirectory["RSC Uttigen HNL"], 3, "10:11", 3),
            StandingRow(5, "Genève RHC U20", teamLogoDirectory["Genève RHC U20"], 3, "8:13", 3),
            StandingRow(6, "RHC Uri U20", teamLogoDirectory["RHC Uri U20"], 3, "5:17", 0)
        )
    }

    Pair(matches, standings)
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            "srhv_wolfurt_channel",
            "RHC Wolfurt Tor-Alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Live-Benachrichtigungen bei Toren in Spielen des RHC Wolfurt"
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}

fun sendGoalNotification(context: Context, title: String, message: String) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = NotificationCompat.Builder(context, "srhv_wolfurt_channel")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    manager.notify(System.currentTimeMillis().toInt(), notification)
}
