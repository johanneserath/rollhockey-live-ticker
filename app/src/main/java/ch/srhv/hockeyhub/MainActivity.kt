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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SportsHockey
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

private val BgObsidian = Color(0xFF090D16)
private val SurfaceSlate = Color(0xFF121824)
private val SurfaceElevated = Color(0xFF1A2232)
private val BorderHairline = Color(0xFF222D42)
private val AccentLiveEmerald = Color(0xFF00E676)
private val AccentGoalAmber = Color(0xFFFFB300)
private val AccentDangerCrimson = Color(0xFFFF3366)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextSecondary = Color(0xFF94A3B8)
private val TextMuted = Color(0xFF64748B)
private val TabularNumStyle = TextStyle(fontFeatureSettings = "tnum")

data class LiveMatch(
    val id: String,
    val league: String,
    val dateTime: String,
    val venue: String,
    val homeTeam: String,
    val awayTeam: String,
    val score: String,
    val isLive: Boolean,
    val detailUrl: String?
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BgObsidian) {
                    RollhockeyFotMobApp()
                }
            }
        }
    }
}

@Composable
fun RollhockeyFotMobApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var matches by remember { mutableStateOf<List<LiveMatch>>(emptyList()) }
    var previousScores by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var subscribedIds by remember { mutableStateOf(setOf<String>()) }
    var favoriteFilter by remember { mutableStateOf("Alle") }
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

    suspend fun refreshData() {
        isLoading = true
        val loaded = scrapeRollhockeyCh()
        loaded.forEach { match ->
            val oldScore = previousScores[match.id]
            if (oldScore != null && oldScore != match.score && match.score.contains(":")) {
                if (match.id in subscribedIds || match.homeTeam.contains("Wolfurt", ignoreCase = true) || match.awayTeam.contains("Wolfurt", ignoreCase = true)) {
                    sendGoalNotification(
                        context = context,
                        title = "TOR! ${match.homeTeam} vs. ${match.awayTeam}",
                        message = "Neuer Spielstand: ${match.score} (${match.league})"
                    )
                }
            }
        }
        previousScores = loaded.associate { it.id to it.score }
        matches = loaded
        isLoading = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshData()
            delay(30_000)
        }
    }

    val filterOptions = listOf("Alle", "Wolfurt", "Diessbach", "Wimmis", "Dornbirn", "Thunerstern")

    val filteredMatches = remember(matches, favoriteFilter) {
        if (favoriteFilter == "Alle") matches
        else matches.filter {
            it.homeTeam.contains(favoriteFilter, ignoreCase = true) ||
            it.awayTeam.contains(favoriteFilter, ignoreCase = true)
        }
    }

    val groupedMatches = remember(filteredMatches) {
        filteredMatches.groupBy { it.league }
    }

    Scaffold(
        containerColor = BgObsidian,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgObsidian)
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(AccentDangerCrimson)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("SRHV", color = TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Black)
                        Text("LIVE", color = AccentLiveEmerald, fontSize = 21.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 4.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(SurfaceElevated)
                                .border(1.dp, AccentGoalAmber.copy(alpha = 0.5f), RoundedCornerShape(50))
                                .clickable {
                                    sendGoalNotification(
                                        context = context,
                                        title = "TOR für RHC Wolfurt! (38')",
                                        message = "RHC Wolfurt 4 : 3 RHC Diessbach – NLA Herren"
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Tor-Push testen", color = AccentGoalAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = { scope.launch { refreshData() } },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(SurfaceSlate)
                                .border(1.dp, BorderHairline, CircleShape)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren", tint = TextPrimary, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filterOptions.take(4).forEach { club ->
                        val isSelected = favoriteFilter == club
                        val bg by animateColorAsState(if (isSelected) SurfaceElevated else Color.Transparent, label = "clubBg")
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(bg)
                                .border(1.dp, if (isSelected) AccentLiveEmerald else BorderHairline, RoundedCornerShape(50))
                                .clickable { favoriteFilter = club }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(club, color = if (isSelected) TextPrimary else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(color = BorderHairline, thickness = 1.dp)
            }
        }
    ) { innerPadding ->
        if (selectedDetailUrl != null) {
            BackHandler { selectedDetailUrl = null }
            DetailTickerWebView(url = selectedDetailUrl!!, onBack = { selectedDetailUrl = null }, modifier = Modifier.padding(innerPadding))
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (isLoading && matches.isEmpty()) {
                    CircularProgressIndicator(color = AccentLiveEmerald, modifier = Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        groupedMatches.forEach { (leagueName, leagueMatches) ->
                            item(key = leagueName) {
                                LeagueCard(
                                    leagueName = leagueName,
                                    matches = leagueMatches,
                                    subscribedIds = subscribedIds,
                                    onToggleSubscribe = { id ->
                                        subscribedIds = if (id in subscribedIds) subscribedIds - id else subscribedIds + id
                                    },
                                    onMatchClick = { match ->
                                        if (match.detailUrl != null) {
                                            selectedDetailUrl = match.detailUrl
                                        }
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
fun LeagueCard(
    leagueName: String,
    matches: List<LiveMatch>,
    subscribedIds: Set<String>,
    onToggleSubscribe: (String) -> Unit,
    onMatchClick: (LiveMatch) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceSlate)
            .border(1.dp, BorderHairline, RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(SurfaceElevated, SurfaceSlate)))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.SportsHockey, contentDescription = null, tint = AccentDangerCrimson, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(leagueName.uppercase(), color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
        }

        matches.forEachIndexed { idx, match ->
            if (idx > 0) {
                HorizontalDivider(color = BorderHairline.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 14.dp))
            }
            MatchRow(match = match, isSubscribed = match.id in subscribedIds, onToggleSubscribe = { onToggleSubscribe(match.id) }, onClick = { onMatchClick(match) })
        }
    }
}

@Composable
fun MatchRow(match: LiveMatch, isSubscribed: Boolean, onToggleSubscribe: () -> Unit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.width(56.dp)) {
                if (match.isLive) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot()
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("LIVE", color = AccentLiveEmerald, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(text = match.dateTime, color = TextMuted, fontSize = 10.sp, lineHeight = 12.sp, style = TabularNumStyle)
                }
            }

            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(match.homeTeam, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .widthIn(min = 52.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (match.isLive) AccentLiveEmerald.copy(alpha = 0.15f) else SurfaceElevated)
                        .border(1.dp, if (match.isLive) AccentLiveEmerald.copy(alpha = 0.5f) else BorderHairline, RoundedCornerShape(9.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = match.score.ifEmpty { "vs" }, color = if (match.isLive) AccentLiveEmerald else TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, style = TabularNumStyle)
                }
                Text(match.awayTeam, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }

            IconButton(onClick = onToggleSubscribe, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = if (isSubscribed) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone,
                    contentDescription = "Tor-Alarm",
                    tint = if (isSubscribed) AccentGoalAmber else TextMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(1f, 0.2f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(AccentLiveEmerald.copy(alpha = alpha)))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DetailTickerWebView(url: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(SurfaceSlate).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("← Zurück", color = AccentLiveEmerald, fontWeight = FontWeight.Bold) }
            Text("Offizieller Spielbericht", color = TextSecondary, fontSize = 12.sp)
        }
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = true; webViewClient = WebViewClient(); loadUrl(url) } })
    }
}

suspend fun scrapeRollhockeyCh(): List<LiveMatch> = withContext(Dispatchers.IO) {
    val list = mutableListOf<LiveMatch>()
    try {
        val doc = Jsoup.connect("https://www.rollhockey.ch/meisterschaft/").userAgent("Mozilla/5.0").timeout(10000).get()
        val rows = doc.select("table tbody tr")
        for ((index, row) in rows.withIndex()) {
            val cols = row.select("td")
            if (cols.size >= 5) {
                val dateTime = cols[0].text().trim()
                val venue = cols[1].text().trim()
                val home = cols[2].text().trim()
                val score = cols[3].text().trim()
                val away = cols[4].text().trim()
                val link = row.select("a[href*=spiel-detail]").attr("abs:href")
                if (home.isNotEmpty() && away.isNotEmpty()) {
                    list.add(LiveMatch("$home-$away-$dateTime-$index", "Schweizer Meisterschaft (SRHV)", dateTime, venue, home, away, score.ifEmpty { "vs" }, score.contains("*") || row.text().contains("Live", ignoreCase = true), link.ifEmpty { null }))
                }
            }
        }
    } catch (e: Exception) { e.printStackTrace() }
    if (list.isEmpty()) {
        list.add(LiveMatch("demo_1", "NLA Herren (Demo-Vorschau)", "LIVE 38'", "HockeyArena Wolfurt", "RHC Wolfurt", "RHC Diessbach", "4 : 3", true, "https://www.rollhockey.ch/meisterschaft/"))
    }
    list
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel("srhv_goal_channel", "SRHV Tor-Alarm", NotificationManager.IMPORTANCE_HIGH).apply { description = "Benachrichtigungen bei Toren" }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}

fun sendGoalNotification(context: Context, title: String, message: String) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = NotificationCompat.Builder(context, "srhv_goal_channel")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(message)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    manager.notify(System.currentTimeMillis().toInt(), notification)
}
