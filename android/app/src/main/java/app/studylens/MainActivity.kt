package app.studylens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Bg = Color(0xFFECECEE)
private val CardC = Color(0xFFF7F7F8)
private val Ink = Color(0xFF1C1C1E)
private val Sub = Color(0xFF55555A)
private val Line = Color(0xFFE2E2E5)
private val Chip = Color(0xFFE2E2E5)
private val Agrave = FontFamily(Font(R.font.agrave_pro, FontWeight.Normal))

enum class Screen { Welcome, Setup, Home, History, Detail, Settings }

class MainActivity : ComponentActivity() {
    private var openScan = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        openScan.value = intent.getLongExtra("scan", -1).takeIf { it > 0 }
        setContent {
            // Scale the whole UI to the screen width so it keeps the mockup's proportions
            // whatever the phone's display-size setting (designed at 390dp wide).
            val base = androidx.compose.ui.platform.LocalDensity.current
            val widthDp = resources.displayMetrics.widthPixels / base.density
            val factor = uiScale(widthDp)
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
                androidx.compose.ui.unit.Density(base.density * factor, maxOf(base.fontScale, 1f))) {
                App(Store(this), openScan)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openScan.value = intent.getLongExtra("scan", -1).takeIf { it > 0 }
    }
}

// ---------------------------------------------------------------- app shell

@Composable
fun App(store: Store, openScan: MutableState<Long?>) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(if (store.onboarded) Screen.Home else Screen.Welcome) }
    var history by remember { mutableStateOf(store.history()) }
    var detailId by remember { mutableStateOf<Long?>(null) }
    var tick by remember { mutableIntStateOf(0) } // bumps on resume so permission states refresh

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { history = store.history(); tick++ } }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(openScan.value) {
        openScan.value?.let { detailId = it; history = store.history(); screen = Screen.Detail; openScan.value = null }
    }
    // Keep the bubble running whenever it should be.
    LaunchedEffect(tick) {
        if (store.onboarded && store.bubbleOn && Settings.canDrawOverlays(ctx) && !BubbleService.running) startBubble(ctx)
    }
    BackHandler(screen in listOf(Screen.Setup, Screen.History, Screen.Detail, Screen.Settings)) {
        screen = when (screen) { Screen.Detail -> Screen.History; Screen.Setup -> if (store.onboarded) Screen.Settings else Screen.Welcome; else -> Screen.Home }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        when (screen) {
            Screen.Welcome -> Welcome { screen = Screen.Setup }
            Screen.Setup -> SetupScreen(store, tick, onBack = { screen = Screen.Welcome }) {
                store.onboarded = true; store.bubbleOn = true; startBubble(ctx); screen = Screen.Home
            }
            Screen.Home -> HomeScreen(store, history, tick, onNav = { screen = it }) { detailId = it; screen = Screen.Detail }
            Screen.History -> HistoryScreen(history, onNav = { screen = it }) { detailId = it; screen = Screen.Detail }
            Screen.Detail -> history.firstOrNull { it.id == detailId }?.let { scan ->
                DetailScreen(store, scan, onBack = { screen = Screen.History }) { store.delete(scan.id); history = store.history(); screen = Screen.History }
            } ?: run { screen = Screen.History }
            Screen.Settings -> SettingsScreen(store, onNav = { screen = it }) { history = store.history() }
        }
    }
}

/** How much to enlarge the 390dp design for this screen width. */
fun uiScale(widthDp: Float) = (widthDp / 470f).coerceIn(0.85f, 1.35f)

fun startBubble(ctx: android.content.Context) {
    ctx.startForegroundService(Intent(ctx, BubbleService::class.java).setAction(BubbleService.ACTION_SHOW))
}

fun stopBubble(ctx: android.content.Context) {
    ctx.startService(Intent(ctx, BubbleService::class.java).setAction(BubbleService.ACTION_HIDE))
}

// ---------------------------------------------------------------- shared pieces

@Composable
fun Title(text: String, modifier: Modifier = Modifier, size: Int = 38, align: TextAlign? = null) =
    androidx.compose.material3.Text(text, modifier, color = Ink, textAlign = align, style = TextStyle(fontFamily = Agrave, fontSize = size.sp, lineHeight = (size * 1.08).sp))

@Composable
fun T(text: String, size: Int = 14, color: Color = Ink, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null) =
    androidx.compose.material3.Text(text, modifier, color = color, fontSize = size.sp, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align, lineHeight = (size * 1.45).sp)

@Composable
fun Card(modifier: Modifier = Modifier, radius: Int = 22, padding: Int = 16, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(radius.dp)).background(CardC).padding(padding.dp), content = content)

@Composable
fun RoundIcon(glyph: String, dark: Boolean = false, size: Int = 40, onClick: (() -> Unit)? = null) =
    Box(Modifier.size(size.dp).clip(CircleShape).background(if (dark) Ink else CardC).let { if (onClick != null) it.clickable { onClick() } else it },
        contentAlignment = Alignment.Center) { T(glyph, 16, if (dark) Color.White else Ink) }

@Composable
fun PillButton(label: String, dark: Boolean = true, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Box(modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (dark) Ink else CardC)
        .graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clickable(enabled) { onClick() }, contentAlignment = Alignment.Center) {
        T(label, 15, if (dark) Color.White else Ink, FontWeight.SemiBold)
    }

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) =
    Box(Modifier.size(50.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) Ink else Color(0xFFC9C9CE)).clickable { onChange(!on) }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Color.White))
    }

@Composable
fun Letter(answer: String, size: Int = 36) =
    Box(Modifier.size(size.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) {
        T(answer.take(5), if (answer.length > 2) 11 else 14, Color.White, FontWeight.SemiBold)
    }

@Composable
fun NavBar(current: Screen, onNav: (Screen) -> Unit) =
    Row(Modifier.padding(horizontal = 60.dp).padding(bottom = 28.dp).fillMaxWidth().height(60.dp).clip(RoundedCornerShape(30.dp)).background(Ink),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
        listOf(Screen.Home to "⌂", Screen.History to "◷", Screen.Settings to "≡").forEach { (s, g) ->
            val sel = s == current
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (sel) Color.White else Ink).clickable { onNav(s) }, contentAlignment = Alignment.Center) {
                T(g, 18, if (sel) Ink else Color.White)
            }
        }
    }

private fun timeLabel(ms: Long): String {
    val now = Calendar.getInstance(); val then = Calendar.getInstance().apply { timeInMillis = ms }
    val mins = (System.currentTimeMillis() - ms) / 60000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) && now.get(Calendar.YEAR) == then.get(Calendar.YEAR) ->
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

private fun dayLabel(ms: Long): String {
    val c = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = ms }
    val sameYear = c.get(Calendar.YEAR) == t.get(Calendar.YEAR)
    val d = c.get(Calendar.DAY_OF_YEAR) - t.get(Calendar.DAY_OF_YEAR)
    return when { sameYear && d == 0 -> "Today"; sameYear && d == 1 -> "Yesterday"; else -> SimpleDateFormat("EEEE d MMM", Locale.getDefault()).format(Date(ms)) }
}

@Composable
fun ScanRow(scan: Scan, divider: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (scan.fullScreen && scan.answers.size > 1)
            Box(Modifier.size(36.dp).clip(CircleShape).background(Chip), contentAlignment = Alignment.Center) { T("×${scan.answers.size}", 12, weight = FontWeight.SemiBold) }
        else scan.answers.firstOrNull().let { a ->
            if (a != null && a.isLetter) Letter(a.answer)
            else Box(Modifier.size(36.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) { T(if (a?.type == "work") "∑" else "Aa", 12, Color.White, FontWeight.SemiBold) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            T(scan.title, 13, weight = FontWeight.Medium, maxLines = 1)
            T("${if (scan.fullScreen) "Full screen" else "One question"} · ${timeLabel(scan.id)}", 11, Sub)
        }
        T("›", 18, Color(0xFF8A8A90))
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
}

// ---------------------------------------------------------------- 1 · welcome

@Composable
fun Welcome(onNext: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // Hero fades into the background instead of ending in a hard edge.
        Image(painterResource(R.drawable.hero), null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.66f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black, 0.7f to Color.Black.copy(alpha = 0.6f), 0.98f to Color.Transparent), blendMode = BlendMode.DstIn)
                })
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                T("STUDYLENS", 13, weight = FontWeight.SemiBold)
                T("Skip", 13, Sub, modifier = Modifier.clickable { onNext() })
            }
            Spacer(Modifier.weight(1f))
            Title("Point at a question.\nUnderstand the answer.", Modifier.fillMaxWidth().padding(horizontal = 32.dp), 40, TextAlign.Center)
            T("A floating lens that reads questions on your screen and explains them — one at a time, or the whole page at once.",
                14, Sub, modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp), align = TextAlign.Center)
            T(Disclaimer.SHORT, 12, Sub, FontWeight.Medium, Modifier.fillMaxWidth().padding(horizontal = 32.dp), align = TextAlign.Center)
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
                Box(Modifier.size(18.dp, 6.dp).clip(CircleShape).background(Ink)); Spacer(Modifier.width(6.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFB9B9BE))); Spacer(Modifier.width(6.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFB9B9BE)))
            }
            Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 32.dp).clickable { onNext() },
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Title("Next", size = 40)
                RoundIcon("→", dark = true, size = 52, onClick = onNext)
            }
        }
    }
}

// ---------------------------------------------------------------- 2 · setup

@Composable
fun SetupScreen(store: Store, tick: Int, onBack: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val shotOk = remember(tick) { ShotService.isEnabled(ctx) }
    var key by remember { mutableStateOf(store.apiKey) }
    var agreed by remember { mutableStateOf(store.onboarded) }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 24.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon("‹", onClick = onBack); Spacer(Modifier.width(12.dp)); T("Step 2 of 2", 13, Sub)
        }
        Title("Set up your lens", Modifier.padding(start = 24.dp, top = 24.dp, bottom = 6.dp))
        T("Three quick things so the bubble can see questions and answer them.", 14, Sub, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 22.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionCard("◎", "Show over other apps", "Lets the lens bubble float on screen", overlayOk) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
            PermissionCard("▭", "Screenshot access", "Turn on “StudyLens screenshots” — one screenshot per tap, no recording", shotOk) {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Chip), contentAlignment = Alignment.Center) { T("⚿", 18) }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        T("Groq API key", 15, weight = FontWeight.SemiBold)
                        T("Free at console.groq.com/keys", 12, Sub)
                    }
                }
                Spacer(Modifier.height(12.dp))
                KeyField(key) { key = it; store.apiKey = it }
            }
            DisclaimerCard(agreed) { agreed = it }
        }
        Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        PillButton("Start the lens  →", modifier = Modifier.padding(16.dp).fillMaxWidth(), enabled = overlayOk && shotOk && key.isNotBlank() && agreed, onClick = onDone)
    }
}

@Composable
fun DisclaimerCard(agreed: Boolean, onChange: (Boolean) -> Unit) = Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Chip), contentAlignment = Alignment.Center) { T("✎", 18) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            T("Educational use only", 15, weight = FontWeight.SemiBold)
            T("Not for exams, tests or graded work", 12, Sub)
        }
    }
    T(Disclaimer.FULL, 12, Sub, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).clickable { onChange(!agreed) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(if (agreed) Ink else Chip), contentAlignment = Alignment.Center) {
            if (agreed) T("✓", 13, Color.White, FontWeight.Bold)
        }
        T("I'll use StudyLens to learn, not to cheat", 13, weight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
fun KeyField(value: String, onChange: (String) -> Unit) {
    BasicTextField(value, onChange, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        textStyle = TextStyle(fontSize = 14.sp, color = Ink),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
            .border(BorderStroke(1.dp, Color(0xFFD6D6DA)), RoundedCornerShape(14.dp)).padding(14.dp),
        decorationBox = { inner -> if (value.isEmpty()) T("Paste key (gsk_…)", 14, Color(0xFF8A8A90)); inner() })
}

@Composable
fun PermissionCard(glyph: String, title: String, sub: String, ok: Boolean, onAllow: () -> Unit) = Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Chip), contentAlignment = Alignment.Center) { T(glyph, 18) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) { T(title, 15, weight = FontWeight.SemiBold); T(sub, 12, Sub) }
        if (ok) T("✓ Allowed", 12, weight = FontWeight.SemiBold)
        else Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Ink).clickable { onAllow() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
            T("Allow", 12, Color.White, FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------- 3 · home

@Composable
fun HomeScreen(store: Store, history: List<Scan>, tick: Int, onNav: (Screen) -> Unit, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    var on by remember(tick) { mutableStateOf(store.bubbleOn && Settings.canDrawOverlays(ctx)) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.app_icon), "StudyLens", Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
                T("STUDYLENS", 13, weight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp).weight(1f))
                RoundIcon("≡") { onNav(Screen.Settings) }
            }
            Title(if (on) "Your lens\nis floating" else "Your lens\nis resting", Modifier.padding(start = 24.dp, top = 22.dp, bottom = 18.dp))
            Card(Modifier.padding(horizontal = 16.dp), radius = 26, padding = 18) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.mark), null, Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        T(if (on) "Bubble on" else "Bubble off", 15, weight = FontWeight.SemiBold)
                        T("Tap it in any app to capture a question", 12, Sub)
                    }
                    Toggle(on) {
                        if (it && !Settings.canDrawOverlays(ctx)) { onNav(Screen.Setup); return@Toggle }
                        on = it; store.bubbleOn = it; if (it) startBubble(ctx) else stopBubble(ctx)
                    }
                }
            }
            if (!ShotService.isEnabled(ctx)) Box(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp)) {
                PermissionCard("▭", "Screenshot access is off", "Needed so the bubble can read questions", false) {
                    ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeCard("⛶", "Full screen", "All questions visible", Modifier.weight(1f))
                ModeCard("⬚", "One question", "Drag over it", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                T("Recent", 15, weight = FontWeight.SemiBold)
                T("See all", 13, Sub, modifier = Modifier.clickable { onNav(Screen.History) })
            }
            Card(Modifier.padding(horizontal = 16.dp), padding = 0) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (history.isEmpty()) T("Nothing yet — tap the bubble in any app.", 13, Sub, modifier = Modifier.padding(vertical = 16.dp))
                    history.take(3).forEachIndexed { i, s -> ScanRow(s, i < minOf(2, history.size - 1)) { onOpen(s.id) } }
                }
            }
            T(Disclaimer.SHORT, 12, Sub, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), align = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
        NavBar(Screen.Home, onNav)
    }
}

@Composable
fun ModeCard(glyph: String, title: String, sub: String, modifier: Modifier) =
    Card(modifier) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Chip), contentAlignment = Alignment.Center) { T(glyph, 18) }
        Spacer(Modifier.height(22.dp))
        T(title, 14, weight = FontWeight.SemiBold); T(sub, 12, Sub)
    }

// ---------------------------------------------------------------- 4 · history

@Composable
fun HistoryScreen(history: List<Scan>, onNav: (Screen) -> Unit, onOpen: (Long) -> Unit) {
    var filter by remember { mutableIntStateOf(0) } // 0 all, 1 one, 2 full
    val shown = history.filter { filter == 0 || (filter == 1 && !it.fullScreen) || (filter == 2 && it.fullScreen) }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Title("History", Modifier.padding(start = 24.dp, top = 60.dp, bottom = 16.dp))
            Row(Modifier.padding(start = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "One question", "Full screen").forEachIndexed { i, l ->
                    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if (filter == i) Ink else CardC).clickable { filter = i }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        T(l, 12, if (filter == i) Color.White else Ink, FontWeight.Medium)
                    }
                }
            }
            if (shown.isEmpty()) T("No answers yet.", 13, Sub, modifier = Modifier.padding(24.dp))
            shown.groupBy { dayLabel(it.id) }.forEach { (day, items) ->
                T(day, 12, Sub, FontWeight.Medium, Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp))
                Card(Modifier.padding(horizontal = 16.dp), padding = 0) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        items.forEachIndexed { i, s -> ScanRow(s, i < items.size - 1) { onOpen(s.id) } }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        NavBar(Screen.History, onNav)
    }
}

// ---------------------------------------------------------------- 5 · detail

@Composable
fun DetailScreen(store: Store, scan: Scan, onBack: () -> Unit, onDelete: () -> Unit) {
    val shot = remember(scan.id) { runCatching { android.graphics.BitmapFactory.decodeFile(store.shotFile(scan.id).path)?.asImageBitmap() }.getOrNull() }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon("‹", onClick = onBack)
            T("${if (scan.fullScreen) "Full screen" else "One question"} · ${timeLabel(scan.id)}", 13, weight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), align = TextAlign.Center)
            RoundIcon("⌫", onClick = onDelete)
        }
        Title(if (scan.fullScreen) "${scan.answers.size} answers" else (scan.answers.firstOrNull()?.answerText?.ifBlank { null } ?: "Answer"),
            Modifier.padding(start = 24.dp, top = 12.dp, bottom = 14.dp), 34)
        shot?.let {
            var expanded by remember { mutableStateOf(false) }
            Image(it, "Captured screenshot", Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                .heightIn(max = if (expanded) 2000.dp else 260.dp).clip(RoundedCornerShape(22.dp))
                .border(1.dp, Color(0xFFDDDDE1), RoundedCornerShape(22.dp)).background(Color.White).clickable { expanded = !expanded },
                contentScale = ContentScale.Fit)
            T(if (expanded) "Tap to shrink" else "Tap screenshot to enlarge", 11, Sub, modifier = Modifier.padding(start = 24.dp, top = 6.dp))
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            scan.answers.forEachIndexed { i, a ->
                AnswerCard(a, i)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun AnswerCard(a: Answer, i: Int) = Card {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T("QUESTION ${a.number.ifBlank { "${i + 1}" }}", 11, Sub, FontWeight.SemiBold, Modifier.weight(1f))
        if (a.isLetter) Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Ink).padding(horizontal = 12.dp, vertical = 5.dp)) { T(a.answer, 13, Color.White, FontWeight.SemiBold) }
    }
    if (a.question.isNotBlank()) T(a.question, 13, Sub, modifier = Modifier.padding(top = 6.dp))
    if (a.isLetter) {
        if (a.answerText.isNotBlank()) Title(a.answerText, Modifier.padding(top = 8.dp), 22)
    } else Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
        T("ANSWER", 10, Sub, FontWeight.SemiBold)
        a.parts.ifEmpty { listOf(a.answer) }.forEach { T(it, 15, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)) }
    }
    if (a.steps.isNotEmpty()) {
        T("WORKING", 10, Sub, FontWeight.SemiBold, Modifier.padding(top = 14.dp, bottom = 4.dp))
        a.steps.forEachIndexed { n, st ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) { T("${n + 1}", 11, Color.White, FontWeight.SemiBold) }
                T(st, 13, Color(0xFF3A3A3F), modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
    if (a.explanation.isNotBlank()) {
        Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(Line))
        T(a.explanation, 13, Sub)
    }
}

// ---------------------------------------------------------------- 6 · settings

@Composable
fun SettingsScreen(store: Store, onNav: (Screen) -> Unit, onCleared: () -> Unit) {
    val ctx = LocalContext.current
    var editingKey by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(store.apiKey) }
    var bubble by remember { mutableStateOf(store.bubbleOn) }
    var size by remember { mutableIntStateOf(store.bubbleSize) }
    var explain by remember { mutableStateOf(store.explanations) }
    var confirmClear by remember { mutableStateOf(false) }

    @Composable fun Row2(label: String, trailing: @Composable () -> Unit, divider: Boolean = true, onClick: (() -> Unit)? = null) {
        Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            T(label, 14, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); trailing()
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
    @Composable fun Section(name: String, content: @Composable ColumnScope.() -> Unit) {
        T(name, 12, Sub, FontWeight.Medium, Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp))
        Card(Modifier.padding(horizontal = 16.dp), padding = 0) { Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), content = content) }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Title("Settings", Modifier.padding(start = 24.dp, top = 60.dp, bottom = 4.dp))
            Section("AI") {
                Row2("Groq API key", { T(if (store.apiKey.length > 6) "gsk_••••${store.apiKey.takeLast(3)}" else "Not set", 13, Sub) }) { editingKey = !editingKey }
                if (editingKey) Column(Modifier.padding(vertical = 10.dp)) {
                    KeyField(key) { key = it }
                    Spacer(Modifier.height(8.dp))
                    PillButton("Save key", modifier = Modifier.fillMaxWidth().height(44.dp)) { store.apiKey = key; store.model = ""; editingKey = false }
                }
                Row2("Model", { T(store.model.ifBlank { "Auto" }.substringAfterLast('/'), 13, Sub, maxLines = 1) }, divider = false)
            }
            Section("Bubble") {
                Row2("Show bubble", { Toggle(bubble) {
                    if (it && !Settings.canDrawOverlays(ctx)) { onNav(Screen.Setup); return@Toggle }
                    bubble = it; store.bubbleOn = it; if (it) startBubble(ctx) else stopBubble(ctx)
                } })
                Column(Modifier.padding(vertical = 13.dp)) {
                    T("Bubble size", 14, weight = FontWeight.Medium)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Small", "Medium", "Large").forEachIndexed { i, l ->
                            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (size == i) Ink else Chip).clickable {
                                size = i; store.bubbleSize = i
                                if (store.bubbleOn) { stopBubble(ctx); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ startBubble(ctx) }, 400) }
                            }.padding(horizontal = 16.dp, vertical = 9.dp)) { T(l, 12, if (size == i) Color.White else Ink, FontWeight.Medium) }
                        }
                    }
                }
            }
            Section("Answers") {
                Row2("Include explanation", { Toggle(explain) { explain = it; store.explanations = it } })
                Row2(if (confirmClear) "Tap again to clear everything" else "Clear history", { T("›", 18, Color(0xFF8A8A90)) }, divider = false) {
                    if (confirmClear) { store.clear(); onCleared(); confirmClear = false } else confirmClear = true
                }
            }
            Section("Educational use only") {
                T(Disclaimer.FULL, 13, Sub, modifier = Modifier.padding(vertical = 14.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
        NavBar(Screen.Settings, onNav)
    }
}
