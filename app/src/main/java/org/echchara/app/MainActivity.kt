@file:OptIn(ExperimentalMaterial3Api::class)

package org.echchara.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Brand = Color(0xFF1554D1)
private val Navy = Color(0xFF0B1B36)
private val Danger = Color(0xFFB42318)
private val DangerBg = Color(0xFFFEF3F2)
private val Warn = Color(0xFFB54708)
private val WarnBg = Color(0xFFFFFAEB)
private val Safe = Color(0xFF067647)
private val SafeBg = Color(0xFFECFDF3)
private val Muted = Color(0xFF667085)
private val Line = Color(0xFFE1E6ED)

private fun bandColor(b: Band) = when (b) { Band.DANGER -> Danger; Band.WARN -> Warn; Band.SAFE -> Safe }
private fun bandBg(b: Band) = when (b) { Band.DANGER -> DangerBg; Band.WARN -> WarnBg; Band.SAFE -> SafeBg }

private val SAMPLE_CALLS = listOf(
    Txt("Digital arrest call", "ಡಿಜಿಟಲ್ ಅರೆಸ್ಟ್ ಕರೆ") to ("+91 89xxx 41207" to listOf(
        "Hello, am I speaking with Mr. Prakash?",
        "This is Inspector Vikram Singh from the Mumbai Cyber Crime Branch.",
        "A FedEx parcel booked with your Aadhaar was seized with illegal drugs.",
        "Your Aadhaar is linked to a money laundering case and a warrant has been issued.",
        "You are under digital arrest. Do not disconnect this call and do not tell your family.",
        "To prove your innocence, transfer your savings to the RBI verification account.")),
    Txt("Kannada power-cut call", "ಕನ್ನಡ ವಿದ್ಯುತ್ ಕಡಿತ ಕರೆ") to ("+91 63xxx 90815" to listOf(
        "ನಮಸ್ಕಾರ ಸಾರ್, ನಾನು ಮೆಸ್ಕಾಂ ಕಚೇರಿಯಿಂದ ಮಾತನಾಡುತ್ತಿದ್ದೇನೆ.",
        "ನಿಮ್ಮ ಕಳೆದ ತಿಂಗಳ ಬಿಲ್ ಅಪ್‌ಡೇಟ್ ಆಗಿಲ್ಲ.",
        "ಇಂದು ರಾತ್ರಿ ನಿಮ್ಮ ವಿದ್ಯುತ್ ಸಂಪರ್ಕ ಕಡಿತಗೊಳಿಸಲಾಗುವುದು.",
        "ತಕ್ಷಣ ನಾನು ಕಳುಹಿಸುವ ಆ್ಯಪ್ ಡೌನ್‌ಲೋಡ್ ಮಾಡಿ, ಸಣ್ಣ ಶುಲ್ಕ ಪಾವತಿಸಿ.",
        "ನಿಮಗೆ ಬರುವ OTP ಅನ್ನು ನನಗೆ ಹೇಳಿ.")),
    Txt("Bank KYC call", "ಬ್ಯಾಂಕ್ KYC ಕರೆ") to ("+91 70xxx 22618" to listOf(
        "Hello sir, I am calling from SBI head office, KYC department.",
        "Your account will be blocked today because your KYC is pending.",
        "I am sending you a link now. Please share the OTP you receive so I can update your KYC.")),
    Txt("Genuine delivery call", "ನಿಜವಾದ ಡೆಲಿವರಿ ಕರೆ") to ("+91 99xxx 50334" to listOf(
        "Hello, I am calling from Amazon delivery. I am near your building with your order.",
        "Should I leave it with the security guard, or will you come down?",
        "Okay, I will hand it to security. Thank you, have a nice day."))
)

private val SAMPLE_MSGS = listOf(
    Txt("MESCOM power cut", "ಮೆಸ್ಕಾಂ ವಿದ್ಯುತ್ ಕಡಿತ") to "Dear Consumer, your electricity power will be disconnected tonight at 9.30 pm because your previous month bill was not updated. Please contact our electricity officer immediately 9845012345.",
    Txt("SBI KYC", "SBI ಕೆವೈಸಿ") to "Dear Customer, your SBI YONO account will be suspended today due to incomplete KYC. Update your PAN immediately at sbi-yono-kyc.top/verify",
    Txt("UPI refund", "UPI ರೀಫಂಡ್") to "flipkart.refund@ybl has sent you a collect request of Rs 4,999. Enter your UPI PIN to receive the refund.",
    Txt("Kannada prize", "ಕನ್ನಡ ಬಹುಮಾನ") to "ಅಭಿನಂದನೆಗಳು! ನೀವು ₹25,00,000 ಬಹುಮಾನ ಗೆದ್ದಿದ್ದೀರಿ. ಬಹುಮಾನ ಪಡೆಯಲು ಇಂದೇ ₹4,999 ಶುಲ್ಕ ಪಾವತಿಸಿ.",
    Txt("Real OTP", "ನಿಜವಾದ OTP") to "482913 is your OTP to log in to Swiggy. Do not share this code with anyone."
)

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)
    private val sharedText = mutableStateOf<String?>(null)
    private val startTab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); Store.channels(this)
        handleIntent(intent)
        setContent { App(resumeTick.intValue, sharedText, startTab) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    override fun onResume() { super.onResume(); resumeTick.intValue++ }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it; startTab.intValue = 1 }
        else if (i?.hasExtra("tab") == true) startTab.intValue = i.getIntExtra("tab", 0)
    }
}

@Composable
private fun App(tick: Int, shared: MutableState<String?>, startTab: MutableIntState) {
    var kn by remember { mutableStateOf(Store.kannada) }
    var tab by startTab
    fun t(en: String, k: String) = if (kn) k else en
    val scheme = lightColorScheme(primary = Brand, onPrimary = Color.White, background = Color(0xFFF5F7FA), surface = Color.White, onSurface = Color(0xFF101828), surfaceVariant = Color(0xFFEEF2F6), outline = Color(0xFFD0D5DD))
    MaterialTheme(colorScheme = scheme) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Echchara", fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); Text("ಎಚ್ಚರ", fontSize = 13.sp, color = Color(0xFFB7C2D4)) } },
                    actions = {
                        Row(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF))) {
                            listOf(false to "EN", true to "ಕನ್ನಡ").forEach { (v, label) ->
                                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (kn == v) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (kn == v) Color(0x33FFFFFF) else Color.Transparent).clickable { kn = v; Store.kannada = v }.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text(t("Protection", "ರಕ್ಷಣೆ")) })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.Search, null) }, label = { Text(t("Check", "ಪರಿಶೀಲಿಸಿ")) })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.Notifications, null) }, label = { Text(t("Alerts", "ಎಚ್ಚರಿಕೆಗಳು")) })
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(Color(0xFFF5F7FA))) {
                when (tab) {
                    0 -> ProtectionScreen(kn, tick)
                    1 -> CheckScreen(kn, shared)
                    else -> AlertsScreen(kn)
                }
            }
        }
    }
}

// ---------------- Protection ----------------
@Composable
private fun ProtectionScreen(kn: Boolean, tick: Int) {
    val ctx = LocalContext.current
    fun t(en: String, k: String) = if (kn) k else en
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var refresh by remember { mutableIntStateOf(0) }
    val key = tick + refresh
    val roleHeld = remember(key) { ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true }
    val overlay = remember(key) { Settings.canDrawOverlays(ctx) }
    val callPerms = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.RECORD_AUDIO, Manifest.permission.ANSWER_PHONE_CALLS)
    val callPermsOk = remember(key) { callPerms.all { has(it) } }
    val smsOk = remember(key) { has(Manifest.permission.RECEIVE_SMS) }
    val notifOk = remember(key) { Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS) }
    var callOn by remember { mutableStateOf(Store.callShield) }
    var smsOn by remember { mutableStateOf(Store.smsShield) }
    var note by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    val callReady = roleHeld && overlay && callPermsOk
    val shieldsOn = (if (callReady && callOn) 1 else 0) + (if (smsOk && smsOn) 1 else 0)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text(t("Protection status", "ರಕ್ಷಣೆಯ ಸ್ಥಿತಿ"), color = Color(0xFFB7C2D4), fontSize = 13.sp)
                Text(if (shieldsOn == 2) t("You are protected", "ನೀವು ರಕ್ಷಿತರಾಗಿದ್ದೀರಿ") else t("$shieldsOn of 2 shields on", "2 ರಲ್ಲಿ $shieldsOn ರಕ್ಷಣೆ ಆನ್"), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(t("Checks calls from unknown numbers and every SMS, on this phone.", "ಅಪರಿಚಿತ ಕರೆಗಳು ಮತ್ತು ಪ್ರತಿ SMS ಅನ್ನು ಈ ಫೋನ್‌ನಲ್ಲೇ ಪರಿಶೀಲಿಸುತ್ತದೆ."), color = Color(0xFFD6DEEB), fontSize = 13.sp)
            }
        }

        ShieldCard(
            icon = Icons.Filled.Call, title = t("Call Shield", "ಕಾಲ್ ಶೀಲ್ಡ್"),
            desc = t("For numbers not in your contacts: a pop-up appears when they call, the caller's words become text, and you get a red alert if it turns into fraud.", "ಸಂಪರ್ಕಗಳಲ್ಲಿ ಇಲ್ಲದ ಸಂಖ್ಯೆಗಳಿಗೆ: ಕರೆ ಬಂದಾಗ ಪಾಪ್-ಅಪ್, ಮಾತು ಪಠ್ಯವಾಗುತ್ತದೆ, ವಂಚನೆಯಾದರೆ ಕೆಂಪು ಎಚ್ಚರಿಕೆ."),
            ready = callReady, on = callOn, onToggle = { callOn = it; Store.callShield = it }, kn = kn
        ) {
            SetupRow(t("Screen incoming calls", "ಒಳಬರುವ ಕರೆಗಳ ಪರಿಶೀಲನೆ"), t("Lets Echchara see who is calling. Calls are never blocked.", "ಯಾರು ಕರೆ ಮಾಡುತ್ತಿದ್ದಾರೆ ಎಂದು ತಿಳಿಯಲು. ಕರೆ ಬ್ಲಾಕ್ ಆಗುವುದಿಲ್ಲ."), roleHeld, kn) {
                ctx.getSystemService(RoleManager::class.java)?.let { roleLauncher.launch(it.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) }
            }
            SetupRow(t("Show pop-up over calls", "ಕರೆಯ ಮೇಲೆ ಪಾಪ್-ಅಪ್"), t("Allow \"Display over other apps\".", "\"ಇತರ ಆ್ಯಪ್‌ಗಳ ಮೇಲೆ ತೋರಿಸು\" ಅನುಮತಿಸಿ."), overlay, kn) {
                roleLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            }
            SetupRow(t("Contacts, phone, microphone", "ಸಂಪರ್ಕ, ಫೋನ್, ಮೈಕ್ರೊಫೋನ್"), t("To skip your contacts, know when you answer, and hear the caller.", "ಸಂಪರ್ಕಗಳನ್ನು ಬಿಡಲು, ಉತ್ತರಿಸಿದಾಗ ತಿಳಿಯಲು, ಕರೆ ಮಾಡಿದವರನ್ನು ಕೇಳಲು."), callPermsOk, kn) {
                permLauncher.launch(callPerms.toTypedArray())
            }
        }

        ShieldCard(
            icon = Icons.Filled.Email, title = t("SMS Shield", "SMS ಶೀಲ್ಡ್"),
            desc = t("Checks every incoming SMS on the phone and notifies you about scams.", "ಪ್ರತಿ ಒಳಬರುವ SMS ಪರಿಶೀಲಿಸಿ ವಂಚನೆಯ ಬಗ್ಗೆ ತಿಳಿಸುತ್ತದೆ."),
            ready = smsOk, on = smsOn, onToggle = { smsOn = it; Store.smsShield = it }, kn = kn
        ) {
            SetupRow(t("Read incoming SMS", "ಒಳಬರುವ SMS ಓದಲು"), t("Messages are checked on the phone and never uploaded.", "ಸಂದೇಶಗಳು ಫೋನ್‌ನಲ್ಲೇ ಪರಿಶೀಲನೆ, ಅಪ್‌ಲೋಡ್ ಇಲ್ಲ."), smsOk, kn) {
                permLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
            }
            if (Build.VERSION.SDK_INT >= 33) SetupRow(t("Notifications", "ಅಧಿಸೂಚನೆಗಳು"), t("So warnings can reach you.", "ಎಚ್ಚರಿಕೆಗಳು ತಲುಪಲು."), notifOk, kn) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t("Try Call Shield now", "ಈಗ ಕಾಲ್ ಶೀಲ್ಡ್ ಪ್ರಯತ್ನಿಸಿ"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(t("Run a sample scam call to see the real pop-up, or test live speech with your microphone. For a real test, ask a friend whose number isn't saved to call you and play a scam script on speaker.", "ನಿಜವಾದ ಪಾಪ್-ಅಪ್ ನೋಡಲು ಮಾದರಿ ಕರೆ ಚಲಾಯಿಸಿ, ಅಥವಾ ಮೈಕ್ರೊಫೋನ್ ಮೂಲಕ ಪರೀಕ್ಷಿಸಿ. ನಿಜವಾದ ಪರೀಕ್ಷೆಗೆ, ಉಳಿಸದ ಸಂಖ್ಯೆಯ ಸ್ನೇಹಿತರಿಂದ ಕರೆ ಮಾಡಿಸಿ."), fontSize = 13.sp, color = Muted)
                SAMPLE_CALLS.forEach { (name, data) ->
                    OutlinedButton(onClick = {
                        if (!Settings.canDrawOverlays(ctx)) note = t("Turn on \"Show pop-up over calls\" first.", "ಮೊದಲು \"ಕರೆಯ ಮೇಲೆ ಪಾಪ್-ಅಪ್\" ಆನ್ ಮಾಡಿ.")
                        else { note = ""; Guard.startSample(ctx, data.second, data.first); (ctx as? ComponentActivity)?.moveTaskToBack(true) }
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(name.get(kn)) }
                }
                Button(onClick = {
                    when {
                        !Settings.canDrawOverlays(ctx) -> note = t("Turn on \"Show pop-up over calls\" first.", "ಮೊದಲು \"ಕರೆಯ ಮೇಲೆ ಪಾಪ್-ಅಪ್\" ಆನ್ ಮಾಡಿ.")
                        !has(Manifest.permission.RECORD_AUDIO) -> permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        else -> { note = ""; Guard.startMic(ctx) }
                    }
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.Phone, null); Spacer(Modifier.width(6.dp)); Text(t("Test with my microphone", "ನನ್ನ ಮೈಕ್ರೊಫೋನ್‌ನಿಂದ ಪರೀಕ್ಷಿಸಿ")) }
                if (note.isNotEmpty()) Text(note, color = Danger, fontSize = 13.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = DangerBg), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("Lost money to fraud?", "ವಂಚನೆಯಿಂದ ಹಣ ಕಳೆದುಕೊಂಡಿರಾ?"), fontWeight = FontWeight.SemiBold)
                    Text(t("Call the Cyber Crime Helpline in the first hour.", "ಮೊದಲ ಗಂಟೆಯಲ್ಲಿ ಸೈಬರ್ ಸಹಾಯವಾಣಿಗೆ ಕರೆ ಮಾಡಿ."), fontSize = 13.sp, color = Muted)
                }
                Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))) }, colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Text("1930", fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ShieldCard(icon: ImageVector, title: String, desc: String, ready: Boolean, on: Boolean, onToggle: (Boolean) -> Unit, kn: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFEBF1FF)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Brand) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    val (label, c, bg) = when { !ready -> Triple(if (kn) "ಸೆಟಪ್ ಬೇಕು" else "Setup needed", Warn, WarnBg); on -> Triple(if (kn) "ಆನ್" else "On", Safe, SafeBg); else -> Triple(if (kn) "ಆಫ್" else "Off", Muted, Color(0xFFF2F4F7)) }
                    Text(label, color = c, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(99.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Switch(checked = on && ready, onCheckedChange = onToggle, enabled = ready)
            }
            Spacer(Modifier.height(8.dp))
            Text(desc, fontSize = 13.sp, color = Muted)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SetupRow(title: String, sub: String, done: Boolean, kn: Boolean, action: () -> Unit) {
    HorizontalDivider(color = Line)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = if (done) Safe else Warn, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium); Text(sub, fontSize = 12.sp, color = Muted) }
        if (!done) TextButton(onClick = action) { Text(if (kn) "ಆನ್ ಮಾಡಿ" else "Turn on") }
    }
}

// ---------------- Check ----------------
@Composable
private fun CheckScreen(kn: Boolean, shared: MutableState<String?>) {
    fun t(en: String, k: String) = if (kn) k else en
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Analysis?>(null) }
    var feedback by remember { mutableStateOf(false) }
    LaunchedEffect(shared.value) { shared.value?.let { input = it; result = Engine.analyze(it); feedback = false; shared.value = null } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(t("Check a message or link", "ಸಂದೇಶ ಅಥವಾ ಲಿಂಕ್ ಪರಿಶೀಲಿಸಿ"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(t("Paste an SMS, WhatsApp message or link. Tip: in WhatsApp, long-press a message → Share → Echchara.", "SMS, ವಾಟ್ಸಾಪ್ ಸಂದೇಶ ಅಥವಾ ಲಿಂಕ್ ಅಂಟಿಸಿ. ವಾಟ್ಸಾಪ್‌ನಲ್ಲಿ ಸಂದೇಶ ಒತ್ತಿ ಹಿಡಿದು → Share → Echchara."), fontSize = 13.sp, color = Muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SAMPLE_MSGS.forEach { (n, v) -> AssistChip(onClick = { input = v; result = Engine.analyze(v); feedback = false }, label = { Text(n.get(kn), fontSize = 12.sp) }) }
        }
        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), placeholder = { Text(t("Paste the message here", "ಸಂದೇಶವನ್ನು ಇಲ್ಲಿ ಅಂಟಿಸಿ")) }, shape = RoundedCornerShape(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { if (input.isNotBlank()) { result = Engine.analyze(input); feedback = false } }, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp)); Text(t("Check", "ಪರಿಶೀಲಿಸಿ")) }
            OutlinedButton(onClick = { input = ""; result = null }, shape = RoundedCornerShape(10.dp)) { Text(t("Clear", "ಅಳಿಸಿ")) }
        }
        result?.let { a -> ResultCard(a, kn, feedback) { y -> Store.addFeedback(y, input); feedback = true } }
    }
}

@Composable
private fun ResultCard(a: Analysis, kn: Boolean, feedbackGiven: Boolean, onFeedback: (Int) -> Unit) {
    fun t(en: String, k: String) = if (kn) k else en
    val b = a.band
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(bandBg(b)), contentAlignment = Alignment.Center) {
                    Icon(when (b) { Band.DANGER -> Icons.Filled.Close; Band.WARN -> Icons.Filled.Warning; else -> Icons.Filled.CheckCircle }, null, tint = bandColor(b))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.label.get(kn), color = bandColor(b), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    a.type?.let { Text(it.get(kn) + (a.org?.let { o -> " · " + t("posing as ", "ನಟನೆ: ") + o } ?: ""), fontSize = 13.sp, color = Muted) }
                }
                Column(horizontalAlignment = Alignment.End) { Text("${a.score}", color = bandColor(b), fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(t("risk / 100", "ಅಪಾಯ / 100"), fontSize = 11.sp, color = Muted) }
            }
            LinearProgressIndicator(progress = { maxOf(.03f, a.score / 100f) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), color = bandColor(b), trackColor = Color(0xFFEEF2F6))
            Text(a.summary.get(kn), fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFEBF1FF)).padding(12.dp))
            Row { Text(t("Pattern engine", "ಮಾದರಿ ಎಂಜಿನ್") + ": ${a.ruleScore}/100", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f)); Text(t("ML classifier", "ML ವರ್ಗೀಕರಣ") + ": ${(a.ml * 100).toInt()}%", fontSize = 12.sp, color = Muted) }
            if (a.flags.isNotEmpty()) {
                Text(t("Findings", "ಕಂಡುಬಂದವು"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                a.flags.take(7).forEach { f ->
                    val c = when (f.sev) { Sev.HIGH -> Danger; Sev.MID -> Warn; Sev.LOW -> Muted; Sev.SAFE -> Safe }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF9FAFB)).padding(10.dp)) {
                        Box(Modifier.padding(top = 4.dp).size(8.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(10.dp))
                        Column { Text(f.label.get(kn), fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(f.why.get(kn), fontSize = 12.sp, color = Muted) }
                    }
                }
            }
            if (a.todos.isNotEmpty()) {
                Text(t("What to do now", "ಈಗ ಏನು ಮಾಡಬೇಕು"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                a.todos.take(4).forEachIndexed { i, td -> Text("${i + 1}. " + td.get(kn), fontSize = 13.sp) }
            }
            HorizontalDivider(color = Line)
            if (feedbackGiven) Text(t("Thanks. The classifier retrained on your example.", "ಧನ್ಯವಾದ. ನಿಮ್ಮ ಉದಾಹರಣೆಯಿಂದ ವರ್ಗೀಕರಣ ಮತ್ತೆ ಕಲಿಯಿತು."), fontSize = 12.sp, color = Muted)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("Was this right?", "ಇದು ಸರಿಯೇ?"), fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = { onFeedback(1) }) { Text(t("It's a scam", "ವಂಚನೆ"), fontSize = 12.sp) }
                TextButton(onClick = { onFeedback(0) }) { Text(t("It's genuine", "ನಿಜವಾದದ್ದು"), fontSize = 12.sp) }
            }
        }
    }
}

// ---------------- Alerts ----------------
@Composable
private fun AlertsScreen(kn: Boolean) {
    fun t(en: String, k: String) = if (kn) k else en
    var items by remember { mutableStateOf(Store.history()) }
    DisposableEffect(Unit) { val l = { items = Store.history() }; Store.listeners.add(l); onDispose { Store.listeners.remove(l) } }
    var open by remember { mutableStateOf<Long?>(null) }
    val fmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Alerts", "ಎಚ್ಚರಿಕೆಗಳು"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) TextButton(onClick = { Store.clearHistory() }) { Icon(Icons.Filled.Delete, null); Text(t("Clear", "ಅಳಿಸಿ")) }
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.CheckCircle, null, tint = Safe, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(8.dp))
                Text(t("No scams caught yet", "ಇನ್ನೂ ಯಾವುದೇ ವಂಚನೆ ಪತ್ತೆಯಾಗಿಲ್ಲ"), fontWeight = FontWeight.SemiBold)
                Text(t("Suspicious SMS and calls checked by Echchara appear here.", "ಎಚ್ಚರ ಪರಿಶೀಲಿಸಿದ ಅನುಮಾನಾಸ್ಪದ SMS ಮತ್ತು ಕರೆಗಳು ಇಲ್ಲಿ ಕಾಣುತ್ತವೆ."), fontSize = 13.sp, color = Muted)
            }
        }
        items.forEach { h ->
            val b = bandOf(h.score)
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Line), modifier = Modifier.clickable { open = if (open == h.time) null else h.time }) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (h.source == "call") Icons.Filled.Call else Icons.Filled.Email, null, tint = bandColor(b), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.sender.ifBlank { t("Unknown", "ಅಪರಿಚಿತ") }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((if (h.source == "call") t("Call", "ಕರೆ") else "SMS") + " · " + fmt.format(Date(h.time)), fontSize = 12.sp, color = Muted)
                        }
                        Text(b.label.get(kn) + " · ${h.score}", color = bandColor(b), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(99.dp)).background(bandBg(b)).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    (if (kn) h.typeKn else h.type)?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
                    Text(h.text, fontSize = 12.sp, color = Muted, maxLines = if (open == h.time) 40 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
