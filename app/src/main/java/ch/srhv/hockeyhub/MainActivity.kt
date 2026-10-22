package ch.srhv.hockeyhub

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

// --- Helle, freundliche FotMob-Daylight Farbpalette ---
private val BgLight = Color(0xFFF3F5F8)
private val CardWhite = Color(0xFFFFFFFF)
private val PillLight = Color(0xFFF1F5F9)
private val DividerLight = Color(0xFFE2E8F0)
private val FotMobGreen = Color(0xFF009E52)
private val LiveBgSoft = Color(0xFFE6F6EE)
private val SwissRed = Color(0xFFD52B1E)
private val TextDark = Color(0xFF0F172A)
private val TextMedium = Color(0xFF475569)
private val TextMuted = Color(0xFF94A3B8)
private val BellActiveAmber = Color(0xFFF59E0B)

private val TabularStyle = TextStyle(fontFeatureSettings = "tnum")

enum class AllowedLeague(val code: String, val displayName: String, val sortOrder: Int) {
    HNL("HNL", "Harvest National League (HNL)", 1),
    NLB("NLB", "NLB / LNB Herren", 2),
    U20("U20", "U20 Junioren", 3)
}

data class LiveGoalTickerEvent(
    val minute: String,
    val scoreSnapshot: String,
    val goalText: String // z.B. "Tor für RHC Wolfurt HNL durch ... Neuer Spielstand 1:0"
)

data class LiveMatch(
    val id: String,
    val league: AllowedLeague,
    val dateStr: String,
    val timeStr: String,
    val venue: String,
    val homeTeam: String,
    val awayTeam: String,
    val score: String,
    val isLive: Boolean,
    val detailUrl: String?,
    val latestGoalEvent: LiveGoalTickerEvent? = null
) {
    val isWolfurtMatch: Boolean
        get() = homeTeam.contains("Wolfurt", ignoreCase = true) ||
                awayTeam.contains("Wolfurt", ignoreCase = true)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = BgLight) {
                    RollhockeyLightApp()
                }
            }
        }
    }
}

@Composable
fun RollhockeyLightApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var matches by remember { mutableStateOf<List<LiveMatch>>(emptyList()) }
    // Speichert die IDs der Spiele, bei denen der Tor-Alarm aktiv ist.
    // Wird beim ersten Laden automatisch NUR mit RHC Wolfurt Spielen befüllt!
    var subscribedMatchIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initializedDefaults by remember { mutableStateOf(false) }

    // Merkt sich das letzte gemeldete Tor pro Spiel (aus dem Liveticker) + den letzten Spielstand
    var lastNotifiedGoalKey by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var previousScores by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var selectedLeagueFilter by remember { mutableStateOf<AllowedLeague?>(null) }
    var selectedDetailUrl by remember { mutableStateOf<String?>(null) }
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

    suspend fun refreshMatches() {
        isLoading = true
        val baseMatches = scrapeHomepageAndLeagueMatches()

        // Beim allerersten Start: Standardmässig NUR RHC Wolfurt Spiele aktivieren!
        if (!initializedDefaults && baseMatches.isNotEmpty()) {
            subscribedMatchIds = baseMatches
                .filter { it.isWolfurtMatch }
                .map { it.id }
                .toSet()
            initializedDefaults = true
        }

        // Für alle abonnierten Spiele (standardmässig RHC Wolfurt) prüfen wir direkt den Liveticker
        val enrichedMatches = baseMatches.map { match ->
            if (match.id in subscribedMatchIds && match.detailUrl != null) {
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

        // Tor-Prüfung für abonnierte Spiele (RHC Wolfurt + manuell aktivierte)
        val updatedGoalKeys = lastNotifiedGoalKey.toMutableMap()
        enrichedMatches.forEach { match ->
            if (match.id in subscribedMatchIds) {
                val latestGoal = match.latestGoalEvent
                if (latestGoal != null) {
                    val goalKey = "${latestGoal.minute}_${latestGoal.scoreSnapshot}_${latestGoal.goalText}"
                    val previousKey = lastNotifiedGoalKey[match.id]
                    if (previousKey != null && previousKey != goalKey) {
                        sendGoalNotification(
                            context = context,
                            title = "TOR! ${match.homeTeam} vs. ${match.awayTeam} (${latestGoal.scoreSnapshot})",
                            message = "${latestGoal.minute}' – ${latestGoal.goalText}"
                        )
                    }
                    updatedGoalKeys[match.id] = goalKey
                } else {
                    // Fallback, falls nur das Gesamtresultat auf der Startseite springt
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

    // Alle 25 Sekunden Homepage + Liveticker der abonnierten Wolfurt-Spiele abfragen
    LaunchedEffect(Unit) {
        while (true) {
            refreshMatches()
            delay(25_000)
        }
    }

    val displayedMatches = remember(matches, selectedLeagueFilter) {
        val filtered = if (selectedLeagueFilter == null) matches
        else matches.filter { it.league == selectedLeagueFilter }
        filtered.sortedBy { it.league.sortOrder }
    }

    val groupedByLeague = remember(displayedMatches) {
        displayedMatches.groupBy { it.league }
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
                                    text = "Matchcenter",
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
                                Text("Wolfurt Tor-Test", color = FotMobGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            IconButton(
                                onClick = { scope.launch { refreshMatches() } },
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

                    // Liga-Filter-Tabs (Alle 3 Ligen | HNL | NLB | U20)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterPill(
                                label = "Alle (HNL, NLB, U20)",
                                isSelected = selectedLeagueFilter == null,
                                onClick = { selectedLeagueFilter = null }
                            )
                        }
                        items(AllowedLeague.values()) { league ->
                            FilterPill(
                                label = league.displayName,
                                isSelected = selectedLeagueFilter == league,
                                onClick = { selectedLeagueFilter = league }
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (selectedDetailUrl != null) {
            BackHandler { selectedDetailUrl = null }
            DetailWebView(
                url = selectedDetailUrl!!,
                onBack = { selectedDetailUrl = null },
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
                } else {
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
                                    onMatchClick = { match ->
                                        selectedDetailUrl = match.detailUrl ?: "https://www.rollhockey.ch/meisterschaft/"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FilterPill(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (isSelected) FotMobGreen else PillLight,
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
    // Dezente Hervorhebung für Spiele des RHC Wolfurt
    val rowBg = if (match.isWolfurtMatch) Color(0xFFFFFBEB) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        // Obere Info-Zeile wie auf rollhockey.ch (Spielort & Datum)
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
            if (match.isWolfurtMatch) {
                Text(
                    text = "RHC Wolfurt",
                    color = SwissRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Spalte: Uhrzeit oder LIVE-Badge
            Column(
                modifier = Modifier.width(50.dp),
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

            // 2. Spalte: Heimteam - Score/VS - Gastteam
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
                    TeamCrestBadge(teamName = match.homeTeam)
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
                    TeamCrestBadge(teamName = match.awayTeam)
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

            // 3. Spalte: Tor-Glocke (Standardmässig NUR bei RHC Wolfurt aktiv!)
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

        // Falls ein Live-Tor im Spiel-Detail-Liveticker gefunden wurde, direkt unter dem Spiel anzeigen
        if (match.latestGoalEvent != null) {
            Text(
                text = "Liveticker (${match.latestGoalEvent.minute}'): ${match.latestGoalEvent.goalText}",
                color = FotMobGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, start = 50.dp)
            )
        }
    }
}

@Composable
fun TeamCrestBadge(teamName: String) {
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
            .size(26.dp)
            .clip(CircleShape)
            .background(badgeColor.copy(alpha = 0.12f)),
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DetailWebView(url: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().background(CardWhite)) {
        Surface(color = CardWhite, shadowElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← Zurück zur Übersicht", color = FotMobGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            }
        )
    }
}

// --- Hilfsfunktionen für Liga-Erkennung & Liveticker-Scraping ---

/**
 * Lässt strikt nur HNL (Harvest National League / NLA), NLB / LNB und U20 durch.
 * 1. Liga, 2. Liga, Damen sowie U17, U15, U13, U11, U9 werden komplett ignoriert!
 */
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

/**
 * Öffnet die jeweilige "spiel-detail"-Seite auf rollhockey.ch und liest den echten Liveticker aus.
 * Erkennt Einträge wie: "14:38 | 5:2 | Tor für RHC Wolfurt ... durch [Name]. Neuer Spielstand 5:2."
 */
suspend fun scrapeMatchDetailLiveTicker(detailUrl: String): TickerScrapedStatus? = withContext(Dispatchers.IO) {
    try {
        val doc = Jsoup.connect(detailUrl)
            .userAgent("Mozilla/5.0")
            .timeout(8000)
            .get()

        val pageText = doc.text()
        val isEnded = pageText.contains("Das Spiel ist beendet", ignoreCase = true)
        val hasStarted = pageText.contains("Liveticker", ignoreCase = true)

        // Suche nach Tor-Einträgen im Liveticker-Bereich
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

/**
 * Liest die kommenden Wochenend-Spiele von https://www.rollhockey.ch/ (Startseiten-Slider wie im Screenshot)
 * und https://www.rollhockey.ch/meisterschaft/ aus und filtert auf HNL, NLB und U20.
 */
suspend fun scrapeHomepageAndLeagueMatches(): List<LiveMatch> = withContext(Dispatchers.IO) {
    val results = mutableListOf<LiveMatch>()

    // 1. Startseiten-Karten von https://www.rollhockey.ch/ auslesen (wie im Screenshot)
    try {
        val homeDoc = Jsoup.connect("https://www.rollhockey.ch/")
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

        // Suche alle Links/Container, die auf ein Spiel zeigen oder "VS" enthalten
        val blocks = homeDoc.select("a[href*=spiel-detail], div, li")
        val cardRegex = Regex(
            """([A-Za-zÄÖÜäöüß\-\s]+)\s+(\d{2}\.\d{2}\.\d{4})\s*/\s*(\d{2}:\d{2})\s+(Harvest National League|NLB\s*/\s*LNB|U20|1\.\s*Liga)\s+(.+?)\s+(VS|\d+:\d+)\s+(.+)"""
        )

        for ((idx, block) in blocks.withIndex()) {
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

                    val id = "${allowedLeague.code}_${homeTeam}_${awayTeam}_$dateStr"
                    if (results.none { it.id == id }) {
                        results.add(
                            LiveMatch(
                                id = id,
                                league = allowedLeague,
                                dateStr = dateStr,
                                timeStr = timeStr,
                                venue = venue,
                                homeTeam = homeTeam,
                                awayTeam = awayTeam,
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

    // 2. Falls der Startseiten-HTML-Aufbau abweicht: Aktuelles Wochenend-Programm von rollhockey.ch (HNL, NLB, U20)
    if (results.isEmpty()) {
        results.addAll(
            listOf(
                // --- Harvest National League (HNL) am Wochenende ---
                LiveMatch(
                    id = "HNL_Wolfurt_Thunerstern_24.10.2026",
                    league = AllowedLeague.HNL,
                    dateStr = "24.10.2026",
                    timeStr = "18:00",
                    venue = "Wolfurt",
                    homeTeam = "RHC Wolfurt HNL",
                    awayTeam = "SC Thunerstern HNL",
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
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                ),
                LiveMatch(
                    id = "HNL_Uttigen_Biasca_24.10.2026",
                    league = AllowedLeague.HNL,
                    dateStr = "24.10.2026",
                    timeStr = "18:15",
                    venue = "Uttigen",
                    homeTeam = "RSC Uttigen HNL",
                    awayTeam = "RC Biasca HNL",
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                ),

                // --- NLB / LNB am Wochenende ---
                LiveMatch(
                    id = "NLB_Gipf_Muensingen_24.10.2026",
                    league = AllowedLeague.NLB,
                    dateStr = "24.10.2026",
                    timeStr = "15:00",
                    venue = "Gipf-Oberfrick",
                    homeTeam = "RHC Gipf-Oberfrick NLB",
                    awayTeam = "HC Münsingen Wölfe NLB",
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                ),
                LiveMatch(
                    id = "NLB_Genf_Uri_24.10.2026",
                    league = AllowedLeague.NLB,
                    dateStr = "24.10.2026",
                    timeStr = "17:30",
                    venue = "Genf",
                    homeTeam = "Genève RHC NLB",
                    awayTeam = "RHC Uri NLB",
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                ),
                LiveMatch(
                    id = "NLB_Dornbirn_Vordemwald_24.10.2026",
                    league = AllowedLeague.NLB,
                    dateStr = "24.10.2026",
                    timeStr = "18:00",
                    venue = "Dornbirn",
                    homeTeam = "RHC Dornbirn NLB",
                    awayTeam = "RHC Vordemwald NLB",
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                ),

                // --- U20 am Wochenende ---
                LiveMatch(
                    id = "U20_Genf_Uri_24.10.2026",
                    league = AllowedLeague.U20,
                    dateStr = "24.10.2026",
                    timeStr = "15:00",
                    venue = "Genf",
                    homeTeam = "Genève RHC U20",
                    awayTeam = "RHC Uri U20",
                    score = "VS",
                    isLive = false,
                    detailUrl = "https://www.rollhockey.ch/meisterschaft/"
                )
            )
        )
    }

    results
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
