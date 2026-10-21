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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

private val BgDark = Color(0xFF0F172A)
private val SurfaceCard = Color(0xFF1E293B)
private val BorderColor = Color(0xFF334155)
private val GreenLive = Color(0xFF22C55E)
private val AmberGold = Color(0xFFF59E0B)
private val TextWhite = Color(0xFFFFFFFF)
private val TextGray = Color(0xFF94A3B8)

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
                Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
                    RollhockeyApp()
                }
            }
        }
    }
}

@Composable
fun RollhockeyApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var matches by remember { mutableStateOf<List<LiveMatch>>(emptyList()) }
    var subscribedIds by remember { mutableStateOf(setOf<String>()) }
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

    suspend fun loadData() {
        isLoading = true
        matches = scrapeRollhockey()
        isLoading = false
    }

    LaunchedEffect(Unit) {
        while (true) {
            loadData()
            delay(30_000)
        }
    }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            Surface(color = SurfaceCard, tonalElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SportsHockey,
                            contentDescription = null,
                            tint = GreenLive,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SRHV Live Ticker",
                            color = TextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                sendGoalNotification(
                                    context,
                                    "TOR! RHC Wolfurt",
                                    "RHC Wolfurt 4 : 3 RHC Diessbach"
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberGold),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Test Push", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = { scope.launch { loadData() } },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Aktualisieren",
                                tint = TextWhite,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (selectedDetailUrl != null) {
            BackHandler { selectedDetailUrl = null }
            DetailWebView(url = selectedDetailUrl!!, onBack = { selectedDetailUrl = null }, modifier = Modifier.padding(padding))
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (isLoading && matches.isEmpty()) {
                    CircularProgressIndicator(
                        color = GreenLive,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(matches) { match ->
                            MatchCard(
                                match = match,
                                isSubscribed = match.id in subscribedIds,
                                onToggleSubscribe = {
                                    subscribedIds = if (match.id in subscribedIds) subscribedIds - match.id else subscribedIds + match.id
                                },
                                onClick = {
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

@Composable
fun MatchCard(
    match: LiveMatch,
    isSubscribed: Boolean,
    onToggleSubscribe: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(1.dp, BorderColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = match.league,
                    color = TextGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = match.dateTime,
                    color = if (match.isLive) GreenLive else TextGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = match.homeTeam,
                    color = TextWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )

                Surface(
                    color = if (match.isLive) GreenLive.copy(alpha = 0.2f) else BgDark,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (match.isLive) GreenLive else BorderColor),
                    modifier = Modifier.padding(horizontal = 10.dp)
                ) {
                    Text(
                        text = match.score.ifEmpty { "vs" },
                        color = if (match.isLive) GreenLive else TextWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Text(
                    text = match.awayTeam,
                    color = TextWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onToggleSubscribe,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isSubscribed) Icons.Filled.NotificationsActive else Icons.Outlined.NotificationsNone,
                        contentDescription = "Benachrichtigung",
                        tint = if (isSubscribed) AmberGold else TextGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DetailWebView(url: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Zurück zur Übersicht", color = GreenLive, fontWeight = FontWeight.Bold)
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

suspend fun scrapeRollhockey(): List<LiveMatch> = withContext(Dispatchers.IO) {
    val list = mutableListOf<LiveMatch>()
    try {
        val doc = Jsoup.connect("https://www.rollhockey.ch/meisterschaft/")
            .userAgent("Mozilla/5.0")
            .timeout(10000)
            .get()

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
                    list.add(
                        LiveMatch(
                            id = "$home-$away-$index",
                            league = "Schweizer Meisterschaft",
                            dateTime = dateTime,
                            venue = venue,
                            homeTeam = home,
                            awayTeam = away,
                            score = score,
                            isLive = score.contains("*") || score.contains("'"),
                            detailUrl = link.ifEmpty { null }
                        )
                    )
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    if (list.isEmpty()) {
        list.add(
            LiveMatch(
                id = "demo_1",
                league = "NLA Herren (Vorschau)",
                dateTime = "18:00",
                venue = "Wolfurt Arena",
                homeTeam = "RHC Wolfurt",
                awayTeam = "RHC Diessbach",
                score = "4 : 3",
                isLive = true,
                detailUrl = "https://www.rollhockey.ch/meisterschaft/"
            )
        )
    }
    list
}

fun createNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            "srhv_channel",
            "SRHV Tor-Alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "Push Benachrichtigung bei Toren" }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}

fun sendGoalNotification(context: Context, title: String, message: String) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val notification = NotificationCompat.Builder(context, "srhv_channel")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title)
        .setContentText(message) // <-- Hier von setContentTest zu setContentText geändert
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()
    manager.notify(System.currentTimeMillis().toInt(), notification)
}
