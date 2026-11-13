package com.example.myapplication

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.delay
import java.net.URLEncoder
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException

import androidx.compose.ui.graphics.nativeCanvas
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

private val Ink = Color(0xFF071027)
private val Blue = Color(0xFF1264F5)
private val LightBlue = Color(0xFFE8F1FF)
private val Muted = Color(0xFF596B8D)
private val Field = Color(0xFFF3F6FB)
private val Green = Color(0xFF09A87B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Blue)) { AirportApp() } }
    }
}

private enum class Page { Welcome, Login, Register, Home, Places, Navigation, Technician, LiveSignals, BeaconTest }
private enum class AccountType { User, Administrator }
private enum class PlaceType(val label: String, val icon: ImageVector, val color: Color) {
    Gate("Gates", Icons.Default.Flight, Blue),
    Baggage("Baggage\nClaim", Icons.Outlined.Luggage, Blue),
    Restaurant("Restaurants", Icons.Default.Restaurant, Green),
    Shop("Shops", Icons.Outlined.ShoppingBag, Color(0xFFB36528)),
    More("More", Icons.Default.MoreHoriz, Muted)
}
private data class AirportPlace(val name: String, val detail: String, val type: PlaceType)

@Composable
private fun AirportApp() {
    var page by remember { mutableStateOf(Page.Welcome) }
    var registeredEmail by remember { mutableStateOf("") }
    var registrationNotice by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.User) }
    var destination by remember { mutableStateOf("Gate C7") }
    var selectedDestination by remember { mutableStateOf<ServerDestination?>(null) }
    var destinationCategory by remember { mutableStateOf<PlaceType?>(null) }
    var selectedBeaconId by remember { mutableStateOf<Long?>(null) }
    var placesQuery by remember { mutableStateOf("") }
    val appScope = rememberCoroutineScope()
    fun signOut() {
        val oldToken = BackendSession.token
        BackendSession.token = null
        BackendSession.email = null
        registeredEmail = ""; registrationNotice = ""; type = AccountType.User
        selectedDestination = null; destinationCategory = null; selectedBeaconId = null; placesQuery = ""; destination = "Gate C7"
        page = Page.Welcome
        if (oldToken != null) appScope.launch {
            try { technicianRequest("/auth/logout", oldToken, JSONObject()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Local sign-out completes even if server is offline. */ }
        }
    }
    BackHandler(page != Page.Welcome) {
        page = when (page) {
            Page.BeaconTest -> Page.Places
            Page.Navigation, Page.Places, Page.LiveSignals -> Page.Home
            Page.Home, Page.Technician -> { signOut(); Page.Welcome }
            Page.Register -> Page.Login
            Page.Login -> Page.Welcome
            else -> Page.Welcome
        }
    }
    when (page) {
        Page.Welcome -> WelcomeScreen({ page = Page.Login }, { page = Page.Login })
        Page.Login -> LoginScreen(
            type, { type = it }, { page = if (type == AccountType.Administrator) Page.Technician else Page.Home },
            { registrationNotice = ""; page = Page.Register }, registeredEmail, registrationNotice
        )
        Page.Register -> RegistrationScreen(
            onBack = { page = Page.Login },
            onCreated = {
                registeredEmail = it
                registrationNotice = "Account created. Please sign in."
                type = AccountType.User
                page = Page.Login
            }
        )
        Page.Home -> HomeScreen(
            onCategory = { destinationCategory = it; placesQuery = ""; page = Page.Places },
            onSearch = { destinationCategory = null; placesQuery = ""; page = Page.Places },
            onNavigate = { destination = it; selectedDestination = null; page = Page.Navigation },
            onSignOut = { signOut() },
            onLiveSignals = { page = Page.LiveSignals },
            onBeacon = { selectedBeaconId = it.id; page = Page.BeaconTest }
        )
        Page.Places -> PlacesScreen(destinationCategory, { page = Page.Home }, onBeacon = {
            selectedBeaconId = it.id
            page = Page.BeaconTest
        }, query = placesQuery, onQueryChange = { placesQuery = it }) {
            selectedDestination = it
            destination = it.name
            page = Page.Navigation
        }
        Page.BeaconTest -> UserBeaconHomeScreen(
            onSignOut = { signOut() },
            onDestinations = { page = Page.Places },
            onRoutes = { page = Page.Navigation },
            onDiagnostics = { page = Page.LiveSignals },
            initialBeaconId = selectedBeaconId,
            onBack = { page = Page.Places }
        )
        Page.LiveSignals -> LiveBeaconScreen(onBack = { page = Page.Home })
        Page.Technician -> TechnicianScreen(onSignOut = { signOut() })
        Page.Navigation -> PlacesScreen(null, { page = Page.Home },
            onBeacon = { selectedBeaconId = it.id; page = Page.BeaconTest },
            query = placesQuery, onQueryChange = { placesQuery = it }, onPlace = {})
    }
}

@Composable
private fun WelcomeScreen(onStart: () -> Unit, onSignIn: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.White)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.linearGradient(
                    listOf(Color(0xFFD9E9FF), Color.White),
                    Offset(size.width * .5f, 0f),
                    Offset(size.width, size.height * .8f)
                )
            )
            drawRect(Color(0xFF99A6B8), Offset(size.width * .49f, 0f), androidx.compose.ui.geometry.Size(12f, size.height * .48f))
            drawLine(Color(0xFFA9B4C4), Offset(0f, size.height * .19f), Offset(size.width, size.height * .16f), 8f)
            drawLine(Color(0xFFC7D0DD), Offset(size.width * .62f, size.height * .68f), Offset(size.width, size.height * .57f), 30f)
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 28.dp)) {
            TextButton(onSignIn, Modifier.align(Alignment.End)) { Text("Skip", color = Blue) }
            Spacer(Modifier.height(24.dp))
            Text("Hello!", fontSize = 46.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Text("Welcome to\nBeirut Hariri\nInternational Airport", fontSize = 31.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
            Spacer(Modifier.height(12.dp))
            Text("Your smart companion\nto a smoother journey.", fontSize = 18.sp, color = Muted)
            Spacer(Modifier.height(28.dp))
            WelcomeFeature(Icons.Default.Navigation, "Find Your Way", "Get directions to gates, services and facilities.")
            WelcomeFeature(Icons.Outlined.Schedule, "Save Time", "Explore the fastest and most accessible routes.")
            WelcomeFeature(Icons.Outlined.Luggage, "A Better Airport Experience", "All the information you need, right at your fingertips.")
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(3) { Box(Modifier.padding(5.dp).size(if (it == 0) 11.dp else 9.dp).background(if (it == 0) Blue else Color(0xFFD3DDEA), CircleShape)) }
            }
            Spacer(Modifier.height(18.dp))
            Button(onStart, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Get Started", fontSize = 17.sp); Spacer(Modifier.width(12.dp)); Icon(Icons.Default.ArrowForward, null)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text("Already have an account?", color = Muted); TextButton(onSignIn) { Text("Sign In") }
            }
        }
    }
}

@Composable
private fun WelcomeFeature(icon: ImageVector, title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(58.dp).background(LightBlue, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Blue, modifier = Modifier.size(29.dp)) }
        Spacer(Modifier.width(17.dp))
        Column { Text(title, fontWeight = FontWeight.Bold, color = Ink); Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp) }
    }
}

@Composable
private fun LoginScreen(type: AccountType, onType: (AccountType) -> Unit, onSignIn: () -> Unit, onRegister: () -> Unit, initialEmail: String, notice: String) {
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun submitLogin() {
        if (loading) return
        loginError = null

        if (email.isBlank() || password.isEmpty()) {
            loginError = "Enter your email and password."
            return
        }
        loading = true
        scope.launch {
            try {
                val token = requestLogin(email.trim(), password)
                if (type == AccountType.Administrator) {
                    val access = technicianRequest("/technician/access", token)
                    if (!access.optBoolean("authorized", false)) throw LoginRejected("This account does not have technician access.")
                }
                BackendSession.token = token
                BackendSession.email = email.trim()
                password = ""
                onSignIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoginRejected) {
                loginError = e.message
            } catch (e: IOException) {
                loginError = "Cannot reach the server. Check Wi-Fi and that Go is running."
            } catch (e: Exception) {
                loginError = "Login failed. Check the server address and network permissions."
            } finally {
                loading = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) submitLogin()
        else loginError = "Local network access is required to connect to your backend laptop."
    }


    Box(Modifier.fillMaxSize().background(Color(0xFFF3F7FF)).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(22.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(24.dp)).background(Color.White, RoundedCornerShape(24.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(58.dp).background(Blue, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Flight, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.height(14.dp))
            Text("Welcome to Beirut Hariri\nInternational Airport", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, textAlign = TextAlign.Center)
            Text("Please select your account type and sign in", color = Muted, modifier = Modifier.padding(vertical = 14.dp), textAlign = TextAlign.Center)
            Label("Account Type")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TypeButton("User", type == AccountType.User, Modifier.weight(1f)) { onType(AccountType.User) }
                TypeButton("Technician", type == AccountType.Administrator, Modifier.weight(1f)) { onType(AccountType.Administrator) }
            }
            Spacer(Modifier.height(14.dp)); Label("Email")
            AirportField(email, { email = it }, "Enter your email", Icons.Outlined.Email, KeyboardType.Email)
            Spacer(Modifier.height(12.dp)); Label("Password")
            AirportField(password, { password = it }, "Enter your password", Icons.Outlined.Lock, KeyboardType.Password, {
                IconButton({ visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Toggle password") }
            }, if (visible) VisualTransformation.None else PasswordVisualTransformation())
            TextButton({}, Modifier.align(Alignment.End)) { Text("Forgot password?", color = Ink, textDecoration = TextDecoration.Underline) }
            TextButton(onClick = onRegister, enabled = !loading) { Text("Create new account") }
            if (notice.isNotBlank()) Text(notice, color = Green)
            if (loginError != null) {
                Text(
                    loginError!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 37 &&
                        context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
                        PackageManager.PERMISSION_GRANTED) {
                        permissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
                    } else {
                        submitLogin()
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Ink)
            ) {
                Text(if (loading) "Signing in..." else "Sign In")
            }
        }
    }
}

@Composable private fun Label(text: String) = Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
@Composable private fun TypeButton(text: String, selected: Boolean, modifier: Modifier, click: () -> Unit) {
    OutlinedButton(click, modifier.height(44.dp), shape = RoundedCornerShape(9.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) Ink else Color.White, contentColor = if (selected) Color.White else Ink)) { Text(text) }
}
@Composable
private fun AirportField(
    value: String,
    change: (String) -> Unit,
    hint: String,
    icon: ImageVector,
    keyboard: KeyboardType,
    trailing: (@Composable () -> Unit)? = null,
    transform: VisualTransformation = VisualTransformation.None
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxWidth()) {
        TextField(
            value = value,
            onValueChange = change,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { focused = it.isFocused },
            placeholder = { Text(hint) },
            leadingIcon = { Icon(icon, null) },
            trailingIcon = trailing,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = transform
        )

        Text(if (focused) "Field focused" else "Field not focused")

        TextButton(onClick = {
            requester.requestFocus()
            keyboardController?.show()
        }) {
            Text("Focus this field")
        }
    }
}

@Composable
private fun HomeScreen(onCategory: (PlaceType) -> Unit, onSearch: () -> Unit, onNavigate: (String) -> Unit,
                       onSignOut: () -> Unit, onLiveSignals: () -> Unit, onBeacon: (RegisteredBeacon) -> Unit) {
    Scaffold(containerColor = Color.White, bottomBar = { MainBottomBar(onSearch = onSearch, onRoutes = onSearch) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Welcome back", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(BackendSession.email.orEmpty(), color = Muted, fontSize = 13.sp)
                }
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }
            OutlinedButton(onClick = onSearch, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(12.dp))
                Text("Search ESP32s, e.g. Beacon 4")
                Spacer(Modifier.weight(1f))
            }
            Text("Your registered ESP32s", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            RegisteredHomeMap(onBeacon)
            TextButton(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Text("Find a beacon and open its live test") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun RegisteredHomeMap(onBeacon: (RegisteredBeacon) -> Unit) {
    var beacons by remember { mutableStateOf<List<RegisteredBeacon>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var floor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(retry) {
        loading = true; error = null
        try {
            beacons = requestRegisteredBeacons()
            if (beacons.none { it.floorId == floor }) floor = beacons.firstOrNull()?.floorId
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { beacons = emptyList(); error = "Could not load your registered ESP32s." }
        finally { loading = false }
    }
    if (loading) CircularProgressIndicator()
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = { retry++ }) { Text("Reload devices") }
    }
    if (!loading && error == null) {
        if (beacons.isEmpty()) Text("No active ESP32s registered for this location.", color = Muted)
        else {
            val floors = beacons.map { it.floorId }.distinct()
            if (floors.size > 1) AdminChoice("Floor", floor?.toString(), floors.map { it.toString() to "Floor ID $it" }, true) { floor = it.toLong() }
            UserLiveBeaconMap(beacons.filter { it.floorId == floor }, emptySet(), null, null, onBeacon)
            Text("${beacons.size} registered devices. Tap a marker or use search to open live readings.", color = Muted, fontSize = 12.sp)
            Text("Marker positions come from MySQL. Measure and update these coordinates before using them as real floor positions.", color = Muted, fontSize = 12.sp)
        }
    }
}
@Composable private fun CategoryButton(type: PlaceType, click: () -> Unit) {
    Column(Modifier.width(66.dp).clickable(onClick = click), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).background(type.color.copy(alpha = .1f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Icon(type.icon, null, tint = type.color, modifier = Modifier.size(28.dp)) }
        Text(type.label, color = Ink, fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun AirportMap(modifier: Modifier, onNavigate: (String) -> Unit) {
    Box(modifier.fillMaxWidth().background(Color(0xFFEAF1FA))) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(7) { i -> drawRect(if (i % 2 == 0) Color(0xFFD8E4F1) else Color(0xFFF3F7FB), Offset(i * size.width / 7, (i % 3) * size.height / 8), androidx.compose.ui.geometry.Size(size.width / 9, size.height * .65f)) }
            repeat(6) { i -> drawLine(Color.White, Offset(0f, i * size.height / 6), Offset(size.width, (i + 1) * size.height / 7), 4f) }
        }
        MapLabel(Icons.Default.Flight, "Gate A1", Modifier.align(Alignment.TopStart).padding(42.dp)) { onNavigate("Gate A1") }
        MapLabel(Icons.Default.Flight, "Gate A3", Modifier.align(Alignment.TopEnd).padding(40.dp)) { onNavigate("Gate A3") }
        MapLabel(Icons.Default.Restaurant, "Restaurant", Modifier.align(Alignment.BottomCenter).padding(28.dp)) { onNavigate("Restaurant") }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp) { Text("Demo airport map", Modifier.padding(12.dp), fontWeight = FontWeight.Bold) }
            Box(Modifier.padding(top = 7.dp).size(58.dp).background(Blue.copy(alpha = .2f), CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(28.dp).background(Blue, CircleShape)) }
        }
        FloatingActionButton({ onNavigate("Gate C7") }, Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = Color.White, contentColor = Blue) { Icon(Icons.Default.Navigation, "Navigate") }
    }
}
@Composable private fun MapLabel(icon: ImageVector, text: String, modifier: Modifier, click: () -> Unit) {
    Column(modifier.clickable(onClick = click), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, tint = Muted); Text(text, color = Muted, fontSize = 12.sp) }
}

@Composable private fun MainBottomBar(onSearch: () -> Unit = {}, onRoutes: () -> Unit = {}) {
    NavigationBar(containerColor = Color.White) {
        listOf(Icons.Default.Home to "Home", Icons.Default.Search to "Search", Icons.Outlined.Memory to "Beacons", Icons.Outlined.Notifications to "Alerts", Icons.Outlined.Person to "Profile").forEachIndexed { i, item ->
            NavigationBarItem(i == 0, { when (i) { 1 -> onSearch(); 2 -> onRoutes(); else -> {} } }, { Icon(item.first, item.second) }, label = { Text(item.second, fontSize = 9.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue, indicatorColor = Color.Transparent))
        }
    }
}

private data class ServerDestination(
    val id: Long,
    val name: String,
    val type: String,
    val floorId: Long,
    val floorName: String,
    val navigationNodeId: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacesScreen(category: PlaceType?, onBack: () -> Unit, onBeacon: (RegisteredBeacon) -> Unit,
                         query: String, onQueryChange: (String) -> Unit, onPlace: (ServerDestination) -> Unit) {
    var beacons by remember { mutableStateOf<List<RegisteredBeacon>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(retry) {
        loading = true; error = null; beacons = emptyList()
        try { beacons = requestRegisteredBeacons() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not load registered ESP32s. Check the backend connection." }
        finally { loading = false }
    }
    val matches = beacons.filter { beaconMatchesSearch(it, query) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Find an ESP32", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(18.dp)) {
            OutlinedTextField(query, onQueryChange, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search Beacon 4 or a device name") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, shape = RoundedCornerShape(15.dp))
            Spacer(Modifier.height(12.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text("Retry") }
            }
            if (!loading && error == null && matches.isEmpty()) Text("No registered ESP32 matches this search.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(matches, key = { "beacon:${it.id}" }) { beacon ->
                    Surface(modifier = Modifier.fillMaxWidth().clickable { keyboard?.hide(); onBeacon(beacon) },
                        shape = RoundedCornerShape(16.dp), shadowElevation = 3.dp) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Memory, null, tint = Blue)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Beacon ${beacon.minor}", fontWeight = FontWeight.Bold)
                                Text(beacon.label, color = Muted)
                                Text("Open map and live signal", color = Muted, fontSize = 12.sp)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }
}
// Demo airport location ID from the database. Later, use the selected building/location.
private const val DEMO_LOCATION_ID = 1

private suspend fun requestDestinations(query: String): List<ServerDestination> = withContext(Dispatchers.IO) {
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val connection = URL("$BACKEND_URL/destinations?location_id=$DEMO_LOCATION_ID&q=$encodedQuery")
        .openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        if (connection.responseCode != 200) throw IOException("Destination request failed")
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val json = JSONObject(body)
        check(json.getInt("location_id") == DEMO_LOCATION_ID)
        val array = json.getJSONArray("destinations")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ServerDestination(
                id = item.getLong("id"), name = item.getString("name"),
                type = item.getString("type"), floorId = item.getLong("floor_id"),
                floorName = item.getString("floor_name"), navigationNodeId = item.getLong("navigation_node_id")
            )
        }
    } finally {
        connection.disconnect()
    }
}


@Composable
private fun NavigationScreen(destination: String, onBack: () -> Unit, onEnd: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Text("Navigation to $destination", Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            IconButton({}) { Icon(Icons.Default.MoreVert, "More") }
        }
        DetailedRouteMap(destination)
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxWidth().height(112.dp), RoundedCornerShape(18.dp), shadowElevation = 5.dp) {
            Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Metric(Icons.Outlined.Schedule, "8", "minutes", LightBlue, Blue, Modifier.weight(1f))
                VerticalDivider(Modifier.height(55.dp), color = Color(0xFFE5EAF2))
                Metric(Icons.Default.DirectionsWalk, "450", "meters", Color(0xFFDDF8ED), Green, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxWidth().height(82.dp), RoundedCornerShape(16.dp), shadowElevation = 3.dp) {
            Row(Modifier.padding(horizontal = 17.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.TurnRight, null, tint = Blue, modifier = Modifier.size(38.dp)); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text("Turn right onto Aviation Blvd", fontWeight = FontWeight.SemiBold); Text("in 150 meters", color = Muted) }; IconButton({}) { Icon(Icons.Default.VolumeUp, "Audio", tint = Blue) } }
        }
        Spacer(Modifier.weight(1f))
        Button(onEnd, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1735))) { Icon(Icons.Default.StopCircle, null); Spacer(Modifier.width(9.dp)); Text("End Navigation") }
    }
}

@Composable
private fun DetailedRouteMap(destination: String) {
    Box(Modifier.fillMaxWidth().height(385.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFFEAF1F9))) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(8) { i -> drawRect(if (i % 2 == 0) Color(0xFFD8E4F0) else Color(0xFFF3F7FB), Offset(i * size.width / 8, (i % 4) * 36f), androidx.compose.ui.geometry.Size(size.width / 10, size.height * .72f)) }
            val p = Path().apply { moveTo(size.width*.25f,size.height*.82f); cubicTo(size.width*.37f,size.height*.7f,size.width*.42f,size.height*.6f,size.width*.47f,size.height*.5f); cubicTo(size.width*.5f,size.height*.38f,size.width*.7f,size.height*.39f,size.width*.72f,size.height*.28f); cubicTo(size.width*.74f,size.height*.2f,size.width*.82f,size.height*.21f,size.width*.82f,size.height*.15f) }
            drawPath(p, Color.White, style = Stroke(14f)); drawPath(p, Blue, style = Stroke(7f))
        }
        MapLabel(Icons.Default.Security, "Security Check", Modifier.align(Alignment.TopStart).offset(42.dp, 118.dp)) {}
        MapLabel(Icons.Outlined.ShoppingBag, "Shops", Modifier.align(Alignment.CenterEnd).padding(62.dp)) {}
        MapLabel(Icons.Default.Wc, "Restrooms", Modifier.align(Alignment.BottomEnd).padding(64.dp)) {}
        Column(Modifier.align(Alignment.BottomStart).offset(70.dp, (-35).dp), horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(36.dp).background(Blue, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(14.dp).background(Color.White, CircleShape)) }; Surface(shape = RoundedCornerShape(9.dp), shadowElevation = 3.dp) { Text("Your location", Modifier.padding(8.dp), fontSize = 11.sp) } }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 34.dp, end = 47.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.LocationOn, null, tint = Color(0xFFED1936), modifier = Modifier.size(50.dp)); Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 3.dp) { Row(Modifier.padding(9.dp)) { Icon(Icons.Default.Flight, null, tint = Blue); Text(destination, fontWeight = FontWeight.Bold) } } }
        Surface(Modifier.align(Alignment.CenterEnd).padding(10.dp), RoundedCornerShape(14.dp), shadowElevation = 4.dp) { Column { listOf("2F", "1F", "GF").forEach { floor -> Text(floor, color = if (floor == "1F") Color.White else Muted, modifier = Modifier.background(if (floor == "1F") Blue else Color.White).padding(horizontal = 15.dp, vertical = 10.dp)) } } }
        FloatingActionButton({}, Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = Color.White, contentColor = Blue) { Icon(Icons.Default.Navigation, "Recenter") }
    }
}
@Composable private fun Metric(icon: ImageVector, value: String, unit: String, bg: Color, tint: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { Box(Modifier.size(43.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }; Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold); Text(unit, color = Muted, fontSize = 12.sp) }
}

// Development backend on the other laptop. Update if its Wi-Fi IP changes.
private const val BACKEND_URL = "http://192.168.110.31:8080"


// Memory only: no password or token is written to disk.
private object BackendSession {
    var token: String? = null
    var email: String? = null
}

private class LoginRejected(message: String) : Exception(message)

private suspend fun requestLogin(email: String, password: String): String =
    withContext(Dispatchers.IO) {
        val connection = URL("$BACKEND_URL/auth/login").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            val body = JSONObject().put("email", email).put("password", password)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status == 401) throw LoginRejected("Invalid email or password.")
            if (status !in 200..299) {
                throw LoginRejected("The server could not sign you in (HTTP $status). Try again.")
            }
            val token = JSONObject(text).optString("access_token")
            if (token.isBlank()) throw LoginRejected("The server returned no session token.")
            token
        } finally {
            connection.disconnect()
        }
    }
@Composable
private fun RegistrationScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun submit() {
        if (loading) return
        error = null
        val cleanEmail = email.trim()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            error = "Enter a valid email address."
            return
        }
        if (cleanEmail.codePointCount(0, cleanEmail.length) > 150 ||
            firstName.codePointCount(0, firstName.length) > 100 ||
            lastName.codePointCount(0, lastName.length) > 100) {
            error = "Email must be at most 150 characters; names at most 100."
            return
        }
        if (password.codePointCount(0, password.length) < 15 || password.toByteArray(Charsets.UTF_8).size > 72) {
            error = "Use at least 15 characters and at most 72 UTF-8 bytes for your password."
            return
        }
        if (password != confirmation) {
            error = "Passwords do not match."
            return
        }
        loading = true
        scope.launch {
            try {
                requestRegistration(firstName.trim(), lastName.trim(), cleanEmail, password)
                password = ""
                confirmation = ""
                onCreated(cleanEmail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LoginRejected) {
                error = e.message
            } catch (e: IOException) {
                error = "Cannot reach the server. Check Wi-Fi and that Go is running."
            } catch (e: Exception) {
                error = "Registration failed. Check network permissions and try again."
            } finally {
                loading = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) submit() else error = "Allow local network access to reach the backend."
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFFF3F7FF)).safeDrawingPadding()
            .imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack, enabled = !loading) { Text("Back to Sign In") }
        Text("Create account", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Create a user account to explore the airport.", color = Muted)
        Label("First name (optional)")
        AirportField(firstName, { firstName = it }, "First name", Icons.Outlined.Person, KeyboardType.Text)
        Label("Last name (optional)")
        AirportField(lastName, { lastName = it }, "Last name", Icons.Outlined.Person, KeyboardType.Text)
        Label("Email")
        AirportField(email, { email = it }, "Email address", Icons.Outlined.Email, KeyboardType.Email)
        Label("Password")
        AirportField(password, { password = it }, "At least 15 characters", Icons.Outlined.Lock,
            KeyboardType.Password, transform = PasswordVisualTransformation())
        Label("Confirm password")
        AirportField(confirmation, { confirmation = it }, "Repeat password", Icons.Outlined.Lock,
            KeyboardType.Password, transform = PasswordVisualTransformation())
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= 37 &&
                    context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch("android.permission.ACCESS_LOCAL_NETWORK")
                } else submit()
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (loading) "Creating account..." else "Create Account") }
    }
}

private suspend fun requestRegistration(firstName: String, lastName: String, email: String, password: String) =
    withContext(Dispatchers.IO) {
        val connection = URL("$BACKEND_URL/auth/register").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val body = JSONObject().put("first_name", firstName).put("last_name", lastName)
                .put("email", email).put("password", password)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status == 409) throw LoginRejected("An account with this email already exists. Sign in instead.")
            if (status == 400) {
                val message = runCatching { JSONObject(response).optString("error") }.getOrNull()
                throw LoginRejected(message?.takeIf { it.isNotBlank() } ?: "Check your account details.")
            }
            if (status != 201) throw LoginRejected("Could not create account (HTTP $status). Try again.")
            val result = JSONObject(response)
            if (result.optLong("user_id", 0) <= 0) throw LoginRejected("Unexpected server response. Try signing in to check your account.")
            Unit
        } finally {
            connection.disconnect()
        }
    }





data class RoutingNode(val id: Long, val code: String, val floorId: Long, val x: Double, val y: Double)
data class RoutingEdge(val from: Long, val to: Long, val meters: Double, val accessible: Boolean)
data class RoutingGraph(val nodes: List<RoutingNode>, val edges: List<RoutingEdge>)
data class RouteResult(val nodeIds: List<Long>, val meters: Double)

// Directed edges: reverse travel requires a separate edge from the server.
fun shortestRoute(graph: RoutingGraph, start: Long, target: Long, accessibleOnly: Boolean): RouteResult? {
    require(graph.nodes.all { it.x.isFinite() && it.y.isFinite() }) { "Invalid coordinates" }
    val ids = graph.nodes.map { it.id }.toSet()
    require(ids.size == graph.nodes.size) { "Duplicate graph node IDs" }
    require(start in ids && target in ids) { "Unknown start or destination" }
    graph.edges.forEach {
        require(it.from in ids && it.to in ids) { "Edge references an unknown node" }
        require(it.meters.isFinite() && it.meters >= 0) { "Invalid edge distance" }
    }
    if (start == target) return RouteResult(listOf(start), 0.0)
    val adjacency = graph.edges.filter { !accessibleOnly || it.accessible }.groupBy { it.from }
    data class Entry(val node: Long, val distance: Double)
    val queue = java.util.PriorityQueue<Entry>(compareBy<Entry> { it.distance }.thenBy { it.node })
    val distance = mutableMapOf(start to 0.0)
    val previous = mutableMapOf<Long, Long>()
    queue.add(Entry(start, 0.0))
    while (queue.isNotEmpty()) {
        val current = queue.remove()
        if (current.distance != distance[current.node]) continue
        if (current.node == target) {
            val route = mutableListOf(target)
            var node = target
            while (node != start) {
                node = previous.getValue(node)
                route.add(node)
            }
            return RouteResult(route.reversed(), current.distance)
        }
        for (edge in adjacency[current.node].orEmpty()) {
            val candidate = current.distance + edge.meters
            require(candidate.isFinite()) { "Route distance overflow" }
            if (candidate < (distance[edge.to] ?: Double.POSITIVE_INFINITY)) {
                distance[edge.to] = candidate
                previous[edge.to] = current.node
                queue.add(Entry(edge.to, candidate))
            }
        }
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingTestScreen(destinationId: Long?, onBack: () -> Unit) {
    var graph by remember { mutableStateOf<RoutingGraph?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var start by remember { mutableStateOf<Long?>(null) }
    var target by remember { mutableStateOf<Long?>(destinationId) }
    var accessibleOnly by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<RouteResult?>(null) }
    var calculated by remember { mutableStateOf(false) }
    var calculating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(retry) {
        loading = true
        error = null
        graph = null
        route = null
        calculated = false
        try {
            val loaded = requestRoutingGraph()
            graph = loaded
            start = loaded.nodes.firstOrNull()?.id
            target = loaded.nodes.firstOrNull { it.id == destinationId }?.id
                ?: loaded.nodes.lastOrNull()?.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Could not load the navigation graph. Check the backend and retry."
        } finally {
            loading = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Route test") }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Dijkstra uses the paths and distances stored in your database.", color = Muted)
            Text("Manual starting point for testing. Live BLE positioning is not connected yet.", color = Muted)
            if (loading) CircularProgressIndicator()
            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
                Button({ retry++ }, enabled = !calculating) { Text("Reload graph") }
            }
            val currentGraph = graph
            if (currentGraph != null) {
                if (currentGraph.nodes.isEmpty()) {
                    Text("No active navigation nodes found.")
                } else {
                    RoutingNodePicker("Starting point", currentGraph.nodes, start, !calculating) {
                        start = it; calculated = false; route = null
                    }
                    RoutingNodePicker("Destination", currentGraph.nodes, target, !calculating) {
                        target = it; calculated = false; route = null
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = accessibleOnly, enabled = !calculating, onCheckedChange = {
                            accessibleOnly = it; calculated = false; route = null
                        })
                        Text("Use only paths marked accessible")
                    }
                    Button(
                        enabled = !calculating && start != null && target != null,
                        onClick = {
                            val from = start!!
                            val to = target!!
                            val accessible = accessibleOnly
                            calculating = true
                            calculated = false
                            error = null
                            scope.launch {
                                try {
                                    route = withContext(Dispatchers.Default) {
                                        shortestRoute(currentGraph, from, to, accessible)
                                    }
                                    calculated = true
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = "Cannot calculate this route. Check the graph data."
                                } finally {
                                    calculating = false
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()
                    ) { Text(if (calculating) "Calculating..." else "Calculate route") }
                    if (calculated) {
                        val result = route
                        if (result == null) {
                            Text("No available route between these points.")
                        } else {
                            Text(String.format(java.util.Locale.US, "Distance: %.1f meters", result.meters),
                                fontWeight = FontWeight.Bold, fontSize = 23.sp)
                            if (result.nodeIds.size == 1) Text("Already at the selected destination.")
                            val byId = currentGraph.nodes.associateBy { it.id }
                            Text(result.nodeIds.joinToString(" → ") { routingNodeLabel(byId.getValue(it)) })
                            Text("Node IDs: " + result.nodeIds.joinToString(" → "), color = Muted)
                            Text("Distance follows the stored edges. Demo values are not measured building distances.", color = Muted)
                            RouteMapWithSimulation(currentGraph, result, accessibleOnly)
                            ThreeBeaconTrilaterationDemo(currentGraph, result.nodeIds.last(), accessibleOnly)
                        }
                    }
                }
            }
        }
    }
}

private fun routingNodeLabel(node: RoutingNode): String =
    node.code.removePrefix("DEMO_").replace('_', ' ')

@Composable
private fun RoutingNodePicker(
    label: String,
    nodes: List<RoutingNode>,
    selected: Long?,
    enabled: Boolean,
    onSelect: (Long) -> Unit
) {
    AdminChoice(
        title = label,
        selected = selected?.toString(),
        choices = nodes.map { it.id.toString() to routingNodeLabel(it) },
        enabled = enabled
    ) { id -> onSelect(id.toLong()) }
}
private suspend fun requestRoutingGraph(): RoutingGraph = withContext(Dispatchers.IO) {
    val connection = URL("$BACKEND_URL/navigation/graph?location_id=$DEMO_LOCATION_ID")
        .openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        if (connection.responseCode != 200) throw IOException("Graph request failed")
        val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        check(json.getInt("location_id") == DEMO_LOCATION_ID)
        check(json.getBoolean("directed"))
        val nodeArray = json.getJSONArray("nodes")
        val edgeArray = json.getJSONArray("edges")
        RoutingGraph(
            List(nodeArray.length()) { i ->
                val n = nodeArray.getJSONObject(i)
                RoutingNode(n.getLong("id"), n.getString("code"), n.getLong("floor_id"), n.getDouble("x"), n.getDouble("y"))
            },
            List(edgeArray.length()) { i ->
                val e = edgeArray.getJSONObject(i)
                RoutingEdge(e.getLong("from_node_id"), e.getLong("to_node_id"),
                    e.getDouble("distance_meters"), e.getBoolean("is_accessible"))
            }
        )
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun CalculatedRouteMap(graph: RoutingGraph, route: RouteResult) {
    var beacons by remember { mutableStateOf<List<RegisteredBeacon>>(emptyList()) }
    var beaconLoading by remember { mutableStateOf(true) }
    var beaconError by remember { mutableStateOf(false) }
    var beaconRetry by remember { mutableStateOf(0) }
    LaunchedEffect(beaconRetry) {
        beaconLoading = true
        beaconError = false
        beacons = emptyList()
        try {
            beacons = requestRegisteredBeacons()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            beaconError = true
        } finally {
            beaconLoading = false
        }
    }
    Text("Registered beacon positions", fontWeight = FontWeight.Bold)
    if (beaconLoading) Text("Loading registered beacons...")
    if (beaconError) {
        Text("Could not load beacon configuration. Check the backend connection.", color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { beaconRetry++ }) { Text("Retry beacons") }
    }
    if (!beaconLoading && !beaconError && beacons.isEmpty()) Text("No registered active beacons returned.")
    val floors = graph.nodes.filter { it.id in route.nodeIds }.map { it.floorId }.distinct()
    var floor by remember(floors) { mutableStateOf(floors.first()) }
    Text("Calculated route map", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    if (floors.size > 1) {
        Text("Select a floor to view its part of the route.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            floors.forEach { id ->
                FilterChip(selected = floor == id, onClick = { floor = id }, label = { Text("Floor ID $id") })
            }
        }
    }
    val visibleNodes = graph.nodes.filter { it.floorId == floor }
    val visibleBeacons = beacons.filter { it.floorId == floor }
    if (!beaconLoading && !beaconError && beacons.isNotEmpty()) {
        Text("${visibleBeacons.size} registered beacon(s) on this floor. Orange ring: stored beacon position.")
        visibleBeacons.forEach { beacon ->
            Text("${beacon.label} beacon: (${beacon.x}, ${beacon.y}) • Major ${beacon.major} / Minor ${beacon.minor}", color = Muted)
        }
    }
    val byId = graph.nodes.associateBy { it.id }
    val segments = route.nodeIds.zipWithNext()
    val startId = route.nodeIds.first()
    val endId = route.nodeIds.last()
    val labelPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(7, 16, 39)
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFEAF1F9))) {
        if (visibleNodes.isEmpty()) return@Canvas
        val margin = 55.dp.toPx()
        val minX = (visibleNodes.map { it.x } + visibleBeacons.map { it.x }).minOrNull()!!
        val maxX = (visibleNodes.map { it.x } + visibleBeacons.map { it.x }).maxOrNull()!!
        val minY = (visibleNodes.map { it.y } + visibleBeacons.map { it.y }).minOrNull()!!
        val maxY = (visibleNodes.map { it.y } + visibleBeacons.map { it.y }).maxOrNull()!!
        val spanX = (maxX - minX).coerceAtLeast(1.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)
        val scale = minOf((size.width - 2 * margin).coerceAtLeast(1f) / spanX,
            (size.height - 2 * margin).coerceAtLeast(1f) / spanY)
        fun point(node: RoutingNode): Offset = Offset(
            (size.width / 2 + (node.x - (minX + maxX) / 2) * scale).toFloat(),
            (size.height / 2 - (node.y - (minY + maxY) / 2) * scale).toFloat()
        )
        val visibleIds = visibleNodes.map { it.id }.toSet()
        graph.edges.filter { it.from in visibleIds && it.to in visibleIds }.forEach { edge ->
            drawLine(Color(0xFFBCC9DA), point(byId.getValue(edge.from)), point(byId.getValue(edge.to)), 3.dp.toPx())
        }
        segments.forEach { (from, to) ->
            val a = byId.getValue(from)
            val b = byId.getValue(to)
            if (a.floorId == floor && b.floorId == floor) {
                val p = point(a)
                val q = point(b)
                drawLine(Blue, p, q, 6.dp.toPx())
                val dx = q.x - p.x
                val dy = q.y - p.y
                val length = kotlin.math.sqrt(dx * dx + dy * dy)
                if (length > 1f) {
                    val ux = dx / length
                    val uy = dy / length
                    val tip = Offset(p.x + dx * .62f, p.y + dy * .62f)
                    val arrow = 10.dp.toPx()
                    drawLine(Blue, tip, Offset(tip.x - ux * arrow - uy * arrow * .6f,
                        tip.y - uy * arrow + ux * arrow * .6f), 3.dp.toPx())
                    drawLine(Blue, tip, Offset(tip.x - ux * arrow + uy * arrow * .6f,
                        tip.y - uy * arrow - ux * arrow * .6f), 3.dp.toPx())
                }
            }
        }
        labelPaint.textSize = 12.sp.toPx()
        visibleNodes.forEach { node ->
            val p = point(node)
            val color = when (node.id) {
                startId -> Green
                endId -> Color(0xFFEA2446)
                else -> if (node.id in route.nodeIds) Blue else Color(0xFF8591AF)
            }
            drawCircle(Color.White, 12.dp.toPx(), p)
            drawCircle(color, 8.dp.toPx(), p)
            drawContext.canvas.nativeCanvas.drawText(routingNodeLabel(node), p.x, p.y - 19.dp.toPx(), labelPaint)
        }
        visibleBeacons.forEach { beacon ->
            val p = Offset(
                (size.width / 2 + (beacon.x - (minX + maxX) / 2) * scale).toFloat(),
                (size.height / 2 - (beacon.y - (minY + maxY) / 2) * scale).toFloat()
            )
            drawCircle(Color(0xFFB85B00), 16.dp.toPx(), p,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            val caption = "${beacon.label} beacon"
            labelPaint.textSize = 11.sp.toPx()
            val halfWidth = labelPaint.measureText(caption) / 2
            val labelX = if (halfWidth * 2 < size.width - 8.dp.toPx())
                p.x.coerceIn(halfWidth + 4.dp.toPx(), size.width - halfWidth - 4.dp.toPx())
            else size.width / 2
            drawContext.canvas.nativeCanvas.drawText(caption, labelX, p.y + 32.dp.toPx(), labelPaint)
        }
    }
    Text(if (startId == endId) "Green: start and destination" else "Green: start • Red: destination • Blue: calculated route", color = Muted)
    Text("Demo schematic: stored coordinates, not measured placement. Beacon markers do not confirm BLE reception. The start is manually selected.", color = Muted)
    if (floors.size > 1) Text("Floor changes: " + segments.filter {
        byId.getValue(it.first).floorId != byId.getValue(it.second).floorId
    }.joinToString { "${routingNodeLabel(byId.getValue(it.first))} → ${routingNodeLabel(byId.getValue(it.second))}" })
}

private data class RegisteredBeacon(
    val id: Long, val uuid: String, val major: Int, val minor: Int,
    val floorId: Long, val label: String, val x: Double, val y: Double, val txPower: Int
)

private suspend fun requestRegisteredBeacons(): List<RegisteredBeacon> = withContext(Dispatchers.IO) {
    val connection = URL("$BACKEND_URL/beacons?location_id=$DEMO_LOCATION_ID")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        if (connection.responseCode != 200) throw IOException("Beacon request failed")
        val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(json.getInt("location_id") == DEMO_LOCATION_ID)
        val array = json.getJSONArray("beacons")
        val result = List(array.length()) { index ->
            val b = array.getJSONObject(index)
            RegisteredBeacon(b.getLong("id"), b.getString("uuid"), b.getInt("major"),
                b.getInt("minor"), b.getLong("floor_id"), b.optString("label", "Beacon"),
                b.getDouble("x"), b.getDouble("y"), b.getInt("tx_power_calibration")).also {
                require(it.id > 0 && it.floorId > 0 && it.x.isFinite() && it.y.isFinite())
                require(it.major in 0..65535 && it.minor in 0..65535)
                require(java.util.UUID.fromString(it.uuid).toString().equals(it.uuid, ignoreCase = true))
            }
        }
        require(result.map { it.id }.distinct().size == result.size)
        result
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun RouteMapWithSimulation(graph: RoutingGraph, original: RouteResult, accessibleOnly: Boolean) {
    var simulation by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    val a = graph.nodes.firstOrNull { it.code == "DEMO_GATE_A1" }
    val b = graph.nodes.firstOrNull { it.code == "DEMO_GATE_A3" }
    val forward = graph.edges.filter {
        it.from == a?.id && it.to == b?.id && (!accessibleOnly || it.accessible)
    }.minByOrNull { it.meters }
    val reverse = graph.edges.filter {
        it.from == b?.id && it.to == a?.id && (!accessibleOnly || it.accessible)
    }.minByOrNull { it.meters }
    val available = a != null && b != null && a.floorId == b.floorId &&
            forward != null && reverse != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = simulation, onCheckedChange = { simulation = it }, enabled = available)
        Spacer(Modifier.width(8.dp))
        Text("Simulate movement (no Bluetooth)", fontWeight = FontWeight.Bold)
    }
    if (!available) Text("Simulation needs A1 and A3 on the same floor with paths in both directions.")
    if (!simulation || !available) {
        CalculatedRouteMap(graph, original)
        return
    }
    val from = a!!
    val to = b!!
    val fraction = progress.toDouble()
    val x = from.x + (to.x - from.x) * fraction
    val y = from.y + (to.y - from.y) * fraction
    Text("SIMULATION • Drag the slider to move the green position dot.", color = Color(0xFFB85B00), fontWeight = FontWeight.Bold)
    Text("This follows a straight demo corridor. It does not read ESP32 signals or use Kalman filtering or trilateration.", color = Muted)
    Text("Gate A1 → Gate A3: ${(progress * 100).toInt()}%")
    Slider(value = progress, onValueChange = { progress = it }, valueRange = 0f..1f)
    Text(String.format(java.util.Locale.US, "Simulated coordinates: (%.1f, %.1f)", x, y))
    val destination = original.nodeIds.last()
    val simulatedGraph: RoutingGraph
    val simulatedStart: Long
    if (progress <= 0f) {
        simulatedGraph = graph
        simulatedStart = from.id
    } else if (progress >= 1f) {
        simulatedGraph = graph
        simulatedStart = to.id
    } else {
        // Temporary start on the A1-A3 edge; never written to the database.
        var virtualId = -1L
        val ids = graph.nodes.map { it.id }.toSet()
        while (virtualId in ids) virtualId--
        simulatedStart = virtualId
        simulatedGraph = RoutingGraph(
            graph.nodes + RoutingNode(virtualId, "SIMULATED POSITION", from.floorId, x, y),
            graph.edges + listOf(
                RoutingEdge(virtualId, to.id, forward!!.meters * (1.0 - fraction), forward.accessible),
                RoutingEdge(virtualId, from.id, reverse!!.meters * fraction, reverse.accessible)
            )
        )
    }
    val simulatedRoute = remember(simulatedGraph, simulatedStart, destination, accessibleOnly) {
        shortestRoute(simulatedGraph, simulatedStart, destination, accessibleOnly)
    }
    Text("Simulation destination: ${routingNodeLabel(graph.nodes.first { it.id == destination })}")
    if (simulatedRoute == null) {
        Text("No route from this simulated position.")
    } else {
        Text(String.format(java.util.Locale.US, "Simulated remaining distance: %.1f meters", simulatedRoute.meters), fontWeight = FontWeight.Bold)
        CalculatedRouteMap(simulatedGraph, simulatedRoute)
    }
}
private suspend fun technicianRequest(path: String, token: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL("$BACKEND_URL$path").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status == 404) throw LoginRejected("Technician endpoints are not installed on the backend yet.")
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (status !in 200..299) throw LoginRejected(json.optString("error", "Request failed (HTTP $status)."))
        json
    } finally { connection.disconnect() }
}

@Composable
private fun TechnicianScreen(onSignOut: () -> Unit) {
    val teal = Color(0xFF008F9B)
    var tab by remember { mutableStateOf(0) }
    var locations by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var config by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedMessage by remember { mutableStateOf<String?>(null) }
    var selectedID by remember { mutableStateOf<Long?>(null) }
    var serial by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var floorID by remember { mutableStateOf<Long?>(null) }
    var nodeID by remember { mutableStateOf<Long?>(null) }
    var minor by remember { mutableStateOf("") }
    var x by remember { mutableStateOf("") }
    var y by remember { mutableStateOf("") }
    var calibration by remember { mutableStateOf("-59") }
    var status by remember { mutableStateOf("ACTIVE") }
    var users by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var accountsLoading by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun token() = BackendSession.token ?: throw LoginRejected("Your session ended. Sign in again.")
    fun clearForm() {
        selectedID = null; serial = ""; label = ""; floorID = null; nodeID = null
        minor = ""; x = ""; y = ""; calibration = "-59"; status = "ACTIVE"; commands = null
    }
    fun loadLocation(id: Long) {
        if (saving) return
        loading = true; error = null; config = null; savedMessage = null; clearForm()
        scope.launch {
            try { config = technicianRequest("/technician/config?location_id=$id", token()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load location." }
            finally { loading = false }
        }
    }
    fun loadLocations() {
        loading = true; error = null
        scope.launch {
            try {
                val array = technicianRequest("/technician/locations", token()).getJSONArray("locations")
                locations = List(array.length()) { array.getJSONObject(it) }
                if (locations.isEmpty()) { config = null; loading = false }
                else {
                    val preferred = locations.firstOrNull { it.getLong("id") == DEMO_LOCATION_ID.toLong() } ?: locations.first()
                    loadLocation(preferred.getLong("id"))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load locations."; loading = false }
        }
    }
    fun edit(b: JSONObject) {
        selectedID = b.getLong("id"); serial = b.getString("device_serial"); label = b.optString("label")
        floorID = b.getLong("floor_id"); nodeID = if (b.isNull("navigation_node_id")) null else b.getLong("navigation_node_id")
        minor = b.getInt("minor").toString(); x = b.getDouble("x").toString(); y = b.getDouble("y").toString()
        calibration = b.getInt("tx_power_calibration").toString(); status = b.getString("status")
        commands = null; error = null; savedMessage = null; tab = 0
    }
    LaunchedEffect(Unit) { loadLocations() }
    MaterialTheme(colorScheme = lightColorScheme(primary = teal, secondary = teal, background = Color.White)) {
        Scaffold(containerColor = Color.White, bottomBar = {
            NavigationBar(containerColor = Color.White) {
                listOf("Devices" to Icons.Outlined.Memory, "Overview" to Icons.Outlined.Dashboard, "Settings" to Icons.Outlined.Settings).forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Memory, null, tint = teal, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("ESP32 Admin", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSignOut, enabled = !saving) { Text("Sign out") }
                }
                HorizontalDivider()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                savedMessage?.let {
                    Surface(color = Color(0xFFE7F6ED), shape = RoundedCornerShape(10.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Green)
                            Spacer(Modifier.width(8.dp)); Text(it, color = Ink)
                        }
                    }
                }
                if (loading || saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val current = config
                if (locations.isNotEmpty()) {
                    AdminChoice("Location", current?.getJSONObject("location")?.getLong("id")?.toString(),
                        locations.map { it.getLong("id").toString() to it.getString("name") }, !loading && !saving) {
                        loadLocation(it.toLong())
                    }
                }
                if (!loading && current == null) {
                    Text(if (locations.isEmpty()) "No location loaded. Check that an active location exists in MySQL." else "Load a location to configure its ESP32s.")
                    OutlinedButton(onClick = { loadLocations() }, enabled = !saving) { Text("Reload locations") }
                }
                if (tab == 2) {
                    Text("Technician account", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(BackendSession.email.orEmpty())
                    Text("Backend: $BACKEND_URL", color = Muted)
                    Text("Accounts are registered through Create account. Technician permission is assigned in MySQL.", color = Muted)
                    OutlinedButton(onClick = {
                        accountsLoading = true; error = null
                        scope.launch {
                            try {
                                val array = technicianRequest("/technician/users", token()).getJSONArray("users")
                                users = List(array.length()) { array.getJSONObject(it) }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Could not load account emails." }
                            finally { accountsLoading = false }
                        }
                    }, enabled = !accountsLoading && !saving) { Text("Load registered account emails") }
                    if (accountsLoading) CircularProgressIndicator()
                    users.forEach { u ->
                        Text("${u.getString("email")} • ${if (u.getBoolean("is_active")) "Active" else "Inactive"}")
                    }
                } else if (current != null) {
                    val location = current.getJSONObject("location")
                    val floorsArray = current.getJSONArray("floors")
                    val floors = List(floorsArray.length()) { floorsArray.getJSONObject(it) }
                    LaunchedEffect(current, floorID) {
                        if (floorID == null && floors.size == 1) floorID = floors.single().getLong("id")
                    }
                    if (floors.isEmpty()) {
                        Text("The backend returned no floors for this location. Reload the location and check the backend response.", color = MaterialTheme.colorScheme.error)
                    }
                    val nodesArray = current.getJSONArray("nodes")
                    val nodes = List(nodesArray.length()) { nodesArray.getJSONObject(it) }
                    val devicesArray = current.getJSONArray("beacons")
                    val devices = List(devicesArray.length()) { devicesArray.getJSONObject(it) }
                    val selectedFloor = floors.firstOrNull { it.getLong("id") == floorID }
                    if (tab == 1) {
                        Text("Registered ESP32s", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("${devices.size} saved devices • ${devices.count { it.getString("status") == "ACTIVE" }} active", color = Muted)
                        Text("These records come from MySQL. Active status does not verify Bluetooth reception.", color = Muted)
                        if (devices.isEmpty()) Text("No devices registered here yet.")
                        devices.forEach { b ->
                            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(b.optString("label"), fontWeight = FontWeight.Bold, color = Ink)
                                    Text("Device ID #${b.getLong("id")} • ${b.getString("device_serial")}")
                                    Text("Floor ID ${b.getLong("floor_id")} • Minor ${b.getInt("minor")} • ${b.getString("status")}", color = Muted)
                                    Text("Position (${b.getDouble("x")}, ${b.getDouble("y")}) m", color = Muted)
                                    TextButton(onClick = { edit(b) }, enabled = !saving && !loading) { Text("Edit placement") }
                                }
                            }
                        }
                        Button(onClick = { clearForm(); savedMessage = null; tab = 0 }, enabled = !saving && !loading) { Text("Register another ESP32") }
                        OutlinedButton(onClick = { loadLocation(location.getLong("id")) }, enabled = !saving && !loading) { Text("Refresh database records") }
                    } else {
                        Text(if (selectedID == null) "Register an ESP32" else "Configure ESP32 #$selectedID", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("Save device placement to your database", color = Muted)
                        Surface(color = Color(0xFFE9F5F6), shape = RoundedCornerShape(10.dp)) {
                            Text("Manual registration • copy the device serial from PlatformIO STATUS", Modifier.padding(12.dp), color = teal)
                        }
                        Text("Bluetooth scanning is available in a later phone-testing step.", color = Muted, fontSize = 12.sp)
                        AdminTextField("Device name", label, { label = it }, !saving, hint = "e.g. Gate C7")
                        AdminTextField("Device ID (assigned by database)", selectedID?.toString() ?: "Assigned when saved", {}, false, readOnly = true)
                        AdminTextField("Device serial", serial, { serial = it }, !saving, hint = "e.g. 0855CA4185C8")
                        AdminTextField("Location UUID", location.getString("uuid"), {}, false, readOnly = true)
                        AdminChoice("Floor", floorID?.toString(), floors.map { it.getLong("id").toString() to it.getString("name") }, !saving) {
                            floorID = it.toLong(); nodeID = null
                        }
                        AdminTextField("Major (from selected floor)", selectedFloor?.getInt("major")?.toString() ?: "Select a floor", {}, false, readOnly = true)
                        AdminTextField("Minor", minor, { minor = it }, !saving, hint = "Unique on this floor, 0–65535")
                        AdminChoice("Nearby navigation node", nodeID?.toString() ?: "none",
                            listOf("none" to "No linked node") + nodes.filter { it.getLong("floor_id") == floorID }.map { it.getLong("id").toString() to it.getString("code") }, !saving) {
                            nodeID = it.toLongOrNull()
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) { AdminTextField("X position (m)", x, { x = it }, !saving) }
                            Box(Modifier.weight(1f)) { AdminTextField("Y position (m)", y, { y = it }, !saving) }
                        }
                        Text("Use measured beacon coordinates in the same floor-map coordinate system. Linking a node does not copy its coordinates.", color = Muted, fontSize = 12.sp)
                        AdminTextField("RSSI at 1 metre (dBm)", calibration, { calibration = it }, !saving)
                        AdminChoice("Status", status, listOf("ACTIVE", "INACTIVE", "MAINTENANCE", "REPLACED").map { it to it }, !saving) { status = it }
                        Button(onClick = {
                            val f = floorID; val m = minor.toIntOrNull(); val xx = x.toDoubleOrNull(); val yy = y.toDoubleOrNull(); val power = calibration.toIntOrNull()
                            if (f == null || serial.isBlank() || serial.trim().length > 32 || label.isBlank() || label.trim().length > 32 || label.contains('\n') || label.contains('\r') ||
                                m == null || m !in 0..65535 || xx == null || !xx.isFinite() || yy == null || !yy.isFinite() || power == null || power !in -127..127) {
                                error = "Enter the serial, device name, floor, valid Minor, coordinates and calibration."
                            } else {
                                val locationID = location.getLong("id")
                                val cleanLabel = label.trim()
                                val body = JSONObject().put("id", selectedID ?: JSONObject.NULL).put("location_id", locationID).put("floor_id", f)
                                    .put("navigation_node_id", nodeID ?: JSONObject.NULL).put("device_serial", serial.trim()).put("minor", m).put("label", cleanLabel)
                                    .put("x", xx).put("y", yy).put("tx_power_calibration", power).put("status", status)
                                val firmwareCommands = "SET_UUID ${location.getString("uuid")}\nSET_FLOOR ${selectedFloor!!.getInt("major")}\nSET_MINOR $m\nSET_LABEL $cleanLabel\nSET_TXPOWER $power\nSAVE"
                                saving = true; error = null; savedMessage = null; commands = null
                                scope.launch {
                                    try {
                                        val response = technicianRequest("/technician/beacons", token(), body)
                                        require(response.getBoolean("saved"))
                                        selectedID = response.getLong("id")
                                        savedMessage = "Saved to MySQL • device ID #$selectedID"
                                        commands = firmwareCommands
                                        try { config = technicianRequest("/technician/config?location_id=$locationID", token()) }
                                        catch (e: CancellationException) { throw e }
                                        catch (_: Exception) { error = "Saved, but the database list could not refresh. Use Refresh database records." }
                                    } catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message ?: "Save failed. Check connection before retrying." }
                                    finally { saving = false }
                                }
                            }
                        }, enabled = !saving && !loading, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Outlined.Storage, null); Spacer(Modifier.width(8.dp))
                            Text(if (saving) "Saving..." else "Save to database")
                        }
                        TextButton(onClick = { clearForm(); savedMessage = null }, enabled = !saving) { Text("Clear / new device") }
                        commands?.let { text ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Apply to ESP32 in PlatformIO", fontWeight = FontWeight.Bold)
                                    Text("The database save does not write firmware. Send these commands one at a time through USB Serial Monitor.", color = Muted)
                                    androidx.compose.foundation.text.selection.SelectionContainer { Text(text) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminTextField(title: String, value: String, change: (String) -> Unit, enabled: Boolean,
                           readOnly: Boolean = false, hint: String = "") {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Muted, fontSize = 13.sp)
        OutlinedTextField(value = value, onValueChange = change,
            modifier = Modifier.fillMaxWidth().focusRequester(requester), enabled = enabled || readOnly,
            readOnly = readOnly, singleLine = true, shape = RoundedCornerShape(9.dp),
            placeholder = { Text(hint) },
            trailingIcon = if (readOnly) null else ({
                IconButton(onClick = { requester.requestFocus(); keyboard?.show() }, enabled = enabled) {
                    Icon(Icons.Outlined.Edit, "Focus $title")
                }
            }))
    }
}

@Composable
private fun AdminChoice(title: String, selected: String?, choices: List<Pair<String, String>>, enabled: Boolean,
                        change: (String) -> Unit) {
    var showChoices by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Muted, fontSize = 13.sp)
        OutlinedButton(onClick = { showChoices = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp)) {
            Text(choices.firstOrNull { it.first == selected }?.second ?: "Select $title", modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
    }
    if (showChoices) {
        AlertDialog(
            onDismissRequest = { showChoices = false },
            title = { Text("Select $title") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (choices.isEmpty()) Text("No options returned by the backend for $title.")
                    choices.forEach { (id, name) ->
                        OutlinedButton(onClick = {
                            showChoices = false
                            change(id)
                        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text(name, modifier = Modifier.weight(1f))
                            if (id == selected) Icon(Icons.Default.Check, "Selected")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChoices = false }) { Text("Close") } }
        )
    }
}
// Calibrations belong to this receiver and test environment, not to every phone.
private data class LiveRangeCalibration(val atOneMetre: Double, val exponent: Double)
private fun liveIdentity(b: RegisteredBeacon) = LiveBeaconIdentity(b.uuid.lowercase(java.util.Locale.ROOT), b.major, b.minor)
private fun calibrationKey(id: LiveBeaconIdentity) = "${id.uuid}:${id.major}:${id.minor}"
private fun sampleMedian(values: List<Double>): Double {
    require(values.isNotEmpty() && values.all { it.isFinite() })
    val ordered = values.sorted()
    return if (ordered.size % 2 == 1) ordered[ordered.size / 2]
    else (ordered[ordered.size / 2 - 1] + ordered[ordered.size / 2]) / 2.0
}
private fun fitLiveCalibration(one: Double, two: Double): LiveRangeCalibration {
    require(one.isFinite() && two.isFinite() && one in -127.0..-1.0 && two in -127.0..-1.0)
    val exponent = (one - two) / (10.0 * kotlin.math.log10(2.0))
    // This is a practical acceptance gate for this test, not a universal propagation bound.
    require(exponent in 0.5..6.0) {
        val reason = when {
            two >= one -> "The 2 m signal was as strong as, or stronger than, the 1 m signal."
            exponent < 0.5 -> "The signal decreased too little for this test's distance model."
            else -> "The signal decreased too much for this test's distance model."
        }
        String.format(java.util.Locale.US,
            "Not saved: 1 m = %.1f dBm; 2 m = %.1f dBm; decrease = %.1f dB; fitted exponent = %.2f (test accepts 0.5–6). %s Repeat with the same phone height/orientation and an unobstructed path. No distance model was forced onto these readings.",
            one, two, one - two, exponent, reason)
    }
    return LiveRangeCalibration(one, exponent)
}
private fun liveRangeMetres(rssi: Double, cal: LiveRangeCalibration): Double {
    require(rssi.isFinite() && rssi in -127.0..-1.0 && cal.atOneMetre.isFinite() &&
            cal.atOneMetre in -127.0..-1.0 && cal.exponent.isFinite() && cal.exponent in 0.5..6.0)
    val distance = Math.pow(10.0, (cal.atOneMetre - rssi) / (10.0 * cal.exponent))
    require(distance.isFinite() && distance > 0.0)
    return distance
}

// Refine squared-equation trilateration by minimizing actual range residuals.
// Never project/clamp the answer into the triangle; that would conceal error.
private fun solveLivePosition(anchors: List<RangeAnchor>): TrilateratedPosition {
    val initial = trilaterate2D(anchors) // validates data and non-collinear placement
    fun cost(x: Double, y: Double) = anchors.sumOf {
        val e = kotlin.math.hypot(x - it.x, y - it.y) - it.distance; e * e
    }
    var x = initial.x; var y = initial.y
    val cx = anchors.map { it.x }.average(); val cy = anchors.map { it.y }.average()
    if (cost(cx, cy) < cost(x, y)) { x = cx; y = cy }
    var damping = 0.01
    repeat(60) {
        var aa = damping; var ab = 0.0; var bb = damping; var ga = 0.0; var gb = 0.0
        anchors.forEach { a ->
            val d = kotlin.math.hypot(x - a.x, y - a.y).coerceAtLeast(1e-6)
            val ux = (x - a.x) / d; val uy = (y - a.y) / d
            val e = d - a.distance
            aa += ux * ux; ab += ux * uy; bb += uy * uy; ga += ux * e; gb += uy * e
        }
        val determinant = aa * bb - ab * ab
        if (determinant <= 0.0 || !determinant.isFinite()) return@repeat
        val dx = (bb * ga - ab * gb) / determinant
        val dy = (aa * gb - ab * ga) / determinant
        val nx = x - dx; val ny = y - dy
        if (nx.isFinite() && ny.isFinite() && cost(nx, ny) < cost(x, y)) {
            x = nx; y = ny; damping = (damping / 2.0).coerceAtLeast(1e-8)
        } else damping = (damping * 4.0).coerceAtMost(1e10)
    }
    return TrilateratedPosition(x, y, kotlin.math.sqrt(cost(x, y) / anchors.size))
}

private data class CalibrationPoint(val metres: Double, val medianRssi: Double, val spreadDb: Double, val samples: Int)
private data class CheckedCalibration(val model: LiveRangeCalibration, val fitDb: Double, val checkErrorMetres: Double)
private val calibrationSteps = listOf(1.0, 2.0, 3.0, 1.5)

// Practical screening rules for this small-room experiment, not accuracy guarantees.
// The 1.5 m observation is withheld from the fit and tests its distance prediction.
private fun fitCheckedCalibration(points: List<CalibrationPoint>): CheckedCalibration {
    require(points.size == 4 && points.map { it.metres }.toSet() == calibrationSteps.toSet()) { "Record all four distances first." }
    require(points.all { it.samples >= 20 && it.medianRssi.isFinite() && it.medianRssi in -127.0..-1.0 &&
            it.spreadDb.isFinite() && it.spreadDb >= 0.0 }) { "Each distance needs at least 20 valid samples." }
    val training = points.filter { it.metres != 1.5 }.sortedBy { it.metres }
    val decrease = training.first().medianRssi - training.last().medianRssi
    require(decrease >= 6.0) {
        String.format(java.util.Locale.US, "Not saved: the 1-to-3 m signal decrease is only %.1f dB. This test needs at least 6 dB of separation to avoid the unstable distance conversion seen earlier. This is an experimental quality rule, not a universal radio limit.", decrease)
    }
    require(training.maxOf { it.spreadDb } * 2.0 <= decrease) {
        "Not saved: signal variation is too large compared with the change over distance. The model is not well supported by these readings."
    }
    val xs = training.map { kotlin.math.log10(it.metres) }; val ys = training.map { it.medianRssi }
    val mx = xs.average(); val my = ys.average()
    val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xs.sumOf { (it - mx) * (it - mx) }
    val model = LiveRangeCalibration(my - slope * mx, -slope / 10.0)
    require(model.atOneMetre in -127.0..-1.0 && model.exponent in 0.5..6.0) { "Not saved: no supported decreasing distance model could be fitted." }
    val fit = kotlin.math.sqrt(xs.indices.sumOf {
        val error = ys[it] - (model.atOneMetre - 10.0 * model.exponent * xs[it]); error * error
    } / training.size)
    require(fit <= 3.0) { String.format(java.util.Locale.US, "Not saved: the training readings disagree with the model (%.1f dB RMS; test limit 3 dB).", fit) }
    val check = points.first { it.metres == 1.5 }
    val predicted = liveRangeMetres(check.medianRssi, model)
    val error = kotlin.math.abs(predicted - 1.5)
    require(error <= 0.5) { String.format(java.util.Locale.US,
        "Not saved: the independent 1.5 m check was predicted as %.2f m (error %.2f m; test limit 0.50 m). More measurements did not make this model reliable at the check point.", predicted, error) }
    require(check.spreadDb <= 3.0) { "Not saved: the independent check varied too much (robust spread above 3 dB)." }
    return CheckedCalibration(model, fit, error)
}

private data class RangeCapture(val identity: LiveBeaconIdentity, val metres: Double)

private fun positionTrialSummary(errors: List<Double>, passed: Int, unavailable: Int): String {
    require(errors.all { it.isFinite() && it >= 0.0 } && passed in 0..errors.size && unavailable >= 0)
    if (errors.isEmpty()) return "No position could be calculated. $unavailable checks had no new estimate. Check the live range details; this test cannot measure an error without a calculated position."
    return String.format(java.util.Locale.US,
        "Midpoint error across ALL calculated estimates, including rejected ones: median %.2f m; worst %.2f m. %d of %d estimates passed the position quality checks. %d checks had no new estimate. %sThese results describe this test only.",
        sampleMedian(errors), errors.maxOrNull()!!, passed, errors.size, unavailable,
        if (errors.size < 10) "Too few estimates for a useful assessment. " else "")
}

@Composable
private fun MeasuredLivePositionPanel(all: List<RegisteredBeacon>, fresh: Map<LiveBeaconIdentity, LiveSignalReading>,
                                      scanning: Boolean, target: RegisteredBeacon?, now: Long, onSelect: (RegisteredBeacon) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("measured-ranging-v1", android.content.Context.MODE_PRIVATE) }
    val anchors = all.filter { it.floorId == 1L && it.minor in 1..3 }.sortedBy { it.minor }
    // Only the triangle explicitly measured by the user is eligible for this test.
    fun separation(a: Int, b: Int): Double {
        val p = anchors.first { it.minor == a }; val q = anchors.first { it.minor == b }
        return kotlin.math.hypot(p.x - q.x, p.y - q.y)
    }
    val placementOK = anchors.size == 3 && anchors.map { it.minor }.toSet() == setOf(1, 2, 3) &&
            anchors.map { it.uuid to it.major }.distinct().size == 1 &&
            kotlin.math.abs(separation(1, 3) - 2.51) < 0.03 &&
            kotlin.math.abs(separation(2, 3) - 2.65) < 0.03 && kotlin.math.abs(separation(1, 2) - 2.83) < 0.03
    if (!placementOK) {
        Text("Position test needs the measured A1/A3/C7 triangle on floor 1. Reload saved beacons after updating MySQL.", color = MaterialTheme.colorScheme.error)
        return
    }
    val anchorKey = anchors.map { listOf(it.uuid, it.major, it.minor, it.x, it.y) }
    var revision by remember { mutableIntStateOf(0) }
    val calibrations = remember(anchorKey, revision) {
        anchors.mapNotNull { b ->
            val id = liveIdentity(b); val key = calibrationKey(id)
            val a = prefs.getFloat("$key:A", Float.NaN).toDouble()
            val n = prefs.getFloat("$key:n", Float.NaN).toDouble()
            if (prefs.getInt("$key:version", 0) == 2 && a.isFinite() && a in -127.0..-1.0 && n.isFinite() && n in 0.5..6.0)
                id to LiveRangeCalibration(a, n) else null
        }.toMap()
    }
    var showCalibration by remember { mutableStateOf(false) }
    var calibrationMinor by remember { mutableStateOf("3") }
    val recordings = remember(anchorKey) {
        mutableStateMapOf<LiveBeaconIdentity, List<CalibrationPoint>>().apply {
            anchors.forEach { b ->
                val id = liveIdentity(b)
                val loaded = runCatching {
                    val array = org.json.JSONArray(prefs.getString("${calibrationKey(id)}:points2", "[]"))
                    (0 until array.length()).map { i ->
                        val p = array.getJSONObject(i)
                        CalibrationPoint(p.getDouble("distance"), p.getDouble("rssi"), p.getDouble("spread"), p.getInt("samples"))
                    }.filter { it.metres in calibrationSteps && it.medianRssi.isFinite() && it.spreadDb.isFinite() && it.samples >= 20 }
                        .distinctBy { it.metres }
                }.getOrDefault(emptyList())
                put(id, loaded)
            }
        }
    }
    var capture by remember(anchorKey) { mutableStateOf<RangeCapture?>(null) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val latestFresh by rememberUpdatedState(fresh)
    val latestScanning by rememberUpdatedState(scanning)
    LaunchedEffect(capture) {
        val task = capture ?: return@LaunchedEffect
        val started = android.os.SystemClock.elapsedRealtime()
        val samples = mutableListOf<Double>(); var lastSeen = -1L
        while (android.os.SystemClock.elapsedRealtime() - started < 30_000L) {
            if (!latestScanning) { message = "Recording cancelled: start scanning and try again."; capture = null; return@LaunchedEffect }
            val r = latestFresh[task.identity]
            if (r != null && r.seenAt >= started && r.seenAt > lastSeen) {
                samples += r.raw.toDouble(); lastSeen = r.seenAt
            }
            progress = "${((android.os.SystemClock.elapsedRealtime() - started) / 1000).coerceAtMost(30)} / 30 s · ${samples.size} samples"
            delay(250)
        }
        if (samples.size < 20) message = "Minor ${task.identity.minor}: only ${samples.size} fresh samples; at least 20 are needed. Keep the app open, check Bluetooth and retry."
        else {
            val median = sampleMedian(samples)
            val spread = 1.4826 * sampleMedian(samples.map { kotlin.math.abs(it - median) })
            val point = CalibrationPoint(task.metres, median, spread, samples.size)
            val points = recordings[task.identity].orEmpty().filter { it.metres != task.metres } + point
            recordings[task.identity] = points
            val array = org.json.JSONArray()
            points.forEach { p -> array.put(JSONObject().put("distance", p.metres).put("rssi", p.medianRssi)
                .put("spread", p.spreadDb).put("samples", p.samples)) }
            val key = calibrationKey(task.identity)
            prefs.edit().putString("$key:points2", array.toString()).apply()
            message = String.format(java.util.Locale.US, "Minor %d: %.1f m recorded, %.1f dBm median, %.1f dB robust spread, %d samples. %d/4 recordings complete.",
                task.identity.minor, task.metres, median, spread, samples.size, points.size)
            if (points.size == 4) {
                try {
                    val checked = fitCheckedCalibration(points); val cal = checked.model
                    prefs.edit().putFloat("$key:A", cal.atOneMetre.toFloat()).putFloat("$key:n", cal.exponent.toFloat())
                        .putInt("$key:version", 2).putFloat("$key:checkError", checked.checkErrorMetres.toFloat()).apply()
                    revision++
                    message = String.format(java.util.Locale.US, "Minor %d: model saved after independent check. At 1.5 m, range error was %.2f m; training fit %.1f dB RMS. This does not establish position accuracy. Test C7 first before recording A1/A3.",
                        task.identity.minor, checked.checkErrorMetres, checked.fitDb)
                } catch (e: IllegalArgumentException) {
                    // A replacement attempt that fails must not keep an old model active.
                    prefs.edit().remove("$key:version").apply(); revision++
                    message = "Minor ${task.identity.minor}: ${e.message ?: "Model not supported."} Stop here and send this message before repeating all beacons."
                }
            }
        }
        capture = null
    }
    val usable = anchors.mapNotNull { b ->
        val r = fresh[liveIdentity(b)]; val cal = calibrations[liveIdentity(b)]
        if (scanning && capture == null && r != null && r.samples >= 5 && now - r.seenAt in 0L..2500L && cal != null)
            RangeAnchor(b.x, b.y, liveRangeMetres(r.filtered, cal)) else null
    }
    val times = anchors.mapNotNull { fresh[liveIdentity(it)]?.seenAt }
    val synchronized = times.size == 3 && times.maxOrNull()!! - times.minOrNull()!! <= 1500L
    val solution = if (usable.size == 3 && synchronized) runCatching { solveLivePosition(usable) }.getOrNull() else null
    val span = maxOf(separation(1, 2), separation(1, 3), separation(2, 3))
    // Suppress nonsensical far-away results rather than pinning them onto a beacon.
    val position = solution?.takeIf { it.residualMeters <= 0.75 &&
            it.x in (anchors.minOf { b -> b.x } - span)..(anchors.maxOf { b -> b.x } + span) &&
            it.y in (anchors.minOf { b -> b.y } - span)..(anchors.maxOf { b -> b.y } + span) }
    val c7 = anchors.first { it.minor == 3 }; val a1 = anchors.first { it.minor == 1 }
    val truthX = (c7.x + a1.x) / 2.0; val truthY = (c7.y + a1.y) / 2.0
    var showTruth by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkStatus by remember { mutableStateOf("") }
    val latestPosition by rememberUpdatedState(position)
    val latestSolution by rememberUpdatedState(solution)
    val latestTimes by rememberUpdatedState(times)
    LaunchedEffect(checking) {
        if (!checking) return@LaunchedEffect
        val errors = mutableListOf<Double>(); val started = android.os.SystemClock.elapsedRealtime()
        var previousTimes = emptyList<Long>()
        var passed = 0; var unavailable = 0
        while (android.os.SystemClock.elapsedRealtime() - started < 15_000L) {
            if (!latestScanning) { checkStatus = "Test cancelled: scanning stopped."; checking = false; return@LaunchedEffect }
            // Measure raw candidates too: using only accepted positions would hide failures.
            val p = latestSolution; val stamps = latestTimes
            if (p != null && stamps != previousTimes) {
                errors += kotlin.math.hypot(p.x - truthX, p.y - truthY); previousTimes = stamps.toList()
                if (latestPosition != null) passed++
            } else unavailable++
            checkStatus = "Testing · ${errors.size} calculated / $passed passed quality checks · keep the phone at the marked midpoint"
            delay(500)
        }
        checkStatus = positionTrialSummary(errors, passed, unavailable)
        checking = false
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Live position test · A1 / A3 / C7", fontWeight = FontWeight.Bold)
            Text("Measured triangle only. Beacon 4's unmeasured placement is excluded.", color = Muted, fontSize = 12.sp)
            MeasuredPositionMap(anchors, fresh.keys, target?.id, position,
                if (showTruth) truthX to truthY else null, onSelect)
            Text("Blue dot = estimated phone · green = live beacon · orange ring = destination · purple cross = test midpoint", fontSize = 12.sp, color = Muted)
            when {
                capture != null -> Text("Recording calibration; position is paused.", color = Muted)
                calibrations.size < 3 -> Text("Checked calibration needed (${calibrations.size}/3). Old two-point models are retained in storage but are no longer used. Start with C7 only.")
                !scanning -> Text("Start scanning to calculate your position.")
                usable.size < 3 || !synchronized -> Text("Waiting for three fresh, synchronized signals. No position shown.")
                position == null -> {
                    Text(when {
                        solution == null -> "No numerical position could be calculated from these ranges."
                        solution.residualMeters > 0.75 -> String.format(java.util.Locale.US,
                            "Position hidden: range-fit mismatch %.2f m exceeds the 0.75 m quality limit.", solution.residualMeters)
                        else -> "Position hidden: the calculated point is too far outside this measured test area."
                    }, color = MaterialTheme.colorScheme.error)
                    Text("You can still run the midpoint accuracy test below. It records rejected estimates without treating them as a usable position.", fontSize = 13.sp)
                }
                else -> {
                    Text(String.format(java.util.Locale.US, "Estimated phone: X %.2f m · Y %.2f m", position.x, position.y), fontWeight = FontWeight.Bold, color = Blue)
                    Text(String.format(java.util.Locale.US, "Range-fit mismatch: %.2f m. This is not a position-accuracy measurement.", position.residualMeters), fontSize = 12.sp, color = Muted)
                    val destination = anchors.firstOrNull { it.id == target?.id }
                    if (destination != null) Text(String.format(java.util.Locale.US, "Estimated straight-line distance to %s: %.2f m", destination.label,
                        kotlin.math.hypot(position.x - destination.x, position.y - destination.y)), fontWeight = FontWeight.Bold)
                }
            }
            if (solution != null && position == null) Text(String.format(java.util.Locale.US,
                "Rejected calculation (diagnostic only): X %.2f m · Y %.2f m · range mismatch %.2f m",
                solution.x, solution.y, solution.residualMeters), color = Muted, fontSize = 12.sp)
            Text("Live range details", fontWeight = FontWeight.Bold)
            anchors.forEach { b ->
                val r = fresh[liveIdentity(b)]; val cal = calibrations[liveIdentity(b)]
                val detail = when {
                    r == null -> "no fresh signal"
                    cal == null -> "calibration needed"
                    else -> String.format(java.util.Locale.US, "RSSI %.1f dBm → %.2f m estimate · age %.1f s",
                        r.filtered, liveRangeMetres(r.filtered, cal), (now - r.seenAt).coerceAtLeast(0) / 1000.0)
                }
                Text("${b.label}: $detail", fontSize = 12.sp, color = Muted)
            }
            TextButton(onClick = { showTruth = !showTruth }, enabled = !checking) { Text(if (showTruth) "Hide midpoint test" else "Check positioning accuracy") }
            if (showTruth) {
                Text("Stand halfway between C7 and A1: 1.255 m from each beacon on their connecting line. Keep the phone at beacon height. The purple cross marks that known point.", fontSize = 13.sp)
                Text("This diagnostic test includes rejected calculations. It does not enable unreliable positions for navigation.", fontSize = 12.sp, color = Muted)
                Button(onClick = { checking = true }, enabled = scanning && calibrations.size == 3 && !checking && capture == null,
                    modifier = Modifier.fillMaxWidth()) { Text(if (checking) "Testing for 15 seconds…" else "Measure error here · 15 seconds") }
                if (checkStatus.isNotBlank()) Text(checkStatus, fontSize = 13.sp)
            }
            if (target?.minor !in 1..3) Text("Tap C7 (3), A1 (1), or A3 (2) to choose a measured destination.", color = Blue)
            Text("This tests positioning. A straight line is not a verified walking route. Dijkstra walking guidance is not enabled here.", fontSize = 12.sp, color = Muted)
            TextButton(onClick = { showCalibration = !showCalibration }) { Text(if (showCalibration) "Hide calibration" else "Calibrate signals") }
            if (showCalibration) {
                Text("Start with C7 only. Record at 1 m, 2 m and 3 m along the same unobstructed line from the fixed beacon. Then record a separate check at 1.5 m. Keep the same phone grip, height and orientation you will use for testing; do not switch from lying flat to holding upright. Each recording takes 30 s. Keep the app open.", fontSize = 13.sp)
                Text("The 1.5 m recording is not used to fit the model. Saving requires its estimated range to be within 0.50 m, sufficient change across 1–3 m and stable readings. These experimental checks do not guarantee accurate navigation.", fontSize = 12.sp, color = Muted)
                AdminChoice("Beacon to calibrate", calibrationMinor, anchors.map { it.minor.toString() to it.label }, capture == null && !checking) { calibrationMinor = it }
                val chosen = anchors.first { it.minor.toString() == calibrationMinor }; val chosenID = liveIdentity(chosen)
                anchors.forEach { b ->
                    val id = liveIdentity(b); val cal = calibrations[id]; val points = recordings[id].orEmpty()
                    Text(if (cal != null) String.format(java.util.Locale.US, "%s: CHECKED · reference %.1f dBm · exponent %.2f", b.label, cal.atOneMetre, cal.exponent)
                    else "${b.label}: ${points.size}/4 recordings · model not checked", fontSize = 12.sp)
                }
                if (capture == null) {
                    val points = recordings[chosenID].orEmpty()
                    val next = calibrationSteps.firstOrNull { d -> points.none { it.metres == d } }
                    points.sortedBy { calibrationSteps.indexOf(it.metres) }.forEach { p ->
                        Text(String.format(java.util.Locale.US, "%.1f m: %.1f dBm · spread %.1f dB · %d samples", p.metres, p.medianRssi, p.spreadDb, p.samples), fontSize = 12.sp)
                    }
                    if (next == null) Text("All four recordings are stored. You can replace a single recording below without discarding the others. Keep the beacon, environment and phone orientation unchanged. If the setup changed, restart the complete series.", fontSize = 12.sp, color = Muted)
                    calibrationSteps.forEach { d ->
                        OutlinedButton(onClick = { message = ""; capture = RangeCapture(chosenID, d) },
                            enabled = scanning && !checking && chosenID in fresh && (next == d || next == null),
                            modifier = Modifier.fillMaxWidth()) {
                            Text(when {
                                d == 1.5 && next == null -> "Repeat independent check at 1.5 m · 30 s"
                                d == 1.5 -> "Independent check at 1.5 m · 30 s"
                                next == null -> "Re-record at ${d.toInt()} m · 30 seconds"
                                else -> "Record at ${d.toInt()} m · 30 seconds"
                            })
                        }
                    }
                    TextButton(onClick = {
                        prefs.edit().remove("${calibrationKey(chosenID)}:points2").remove("${calibrationKey(chosenID)}:version").apply()
                        recordings[chosenID] = emptyList(); revision++; message = "Record a new complete series for Minor ${chosen.minor}."
                    }, enabled = !checking && points.isNotEmpty()) { Text("Restart measurements for this beacon") }
                } else {
                    Text("Recording Minor ${capture!!.identity.minor} at ${capture!!.metres} m · $progress")
                    TextButton(onClick = { capture = null; message = "Recording cancelled." }) { Text("Cancel recording") }
                }
                if (message.isNotBlank()) Text(message, fontSize = 13.sp,
                    color = if (message.contains("Not saved") || message.contains("only ")) MaterialTheme.colorScheme.error else Ink)
            }
        }
    }
}

@Composable
private fun MeasuredPositionMap(beacons: List<RegisteredBeacon>, fresh: Set<LiveBeaconIdentity>, destination: Long?,
                                position: TrilateratedPosition?, truth: Pair<Double, Double>?, onSelect: (RegisteredBeacon) -> Unit) {
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 44.dp.toPx() }
    // Include a valid estimate in the bounds, without changing its coordinates.
    val xs = beacons.map { it.x } + listOfNotNull(position?.x, truth?.first)
    val ys = beacons.map { it.y } + listOfNotNull(position?.y, truth?.second)
    val lowX = xs.minOrNull() ?: 0.0; val highX = xs.maxOrNull() ?: 1.0
    val lowY = ys.minOrNull() ?: 0.0; val highY = ys.maxOrNull() ?: 1.0
    fun point(x: Double, y: Double): Offset {
        val scale = minOf((canvasSize.width - 2 * margin).coerceAtLeast(1f) / (highX - lowX).coerceAtLeast(1.0),
            (canvasSize.height - 2 * margin).coerceAtLeast(1f) / (highY - lowY).coerceAtLeast(1.0))
        return Offset((canvasSize.width / 2f + (x - (lowX + highX) / 2) * scale).toFloat(),
            (canvasSize.height / 2f - (y - (lowY + highY) / 2) * scale).toFloat())
    }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }
    Canvas(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(16.dp)).background(LightBlue)
        .onSizeChanged { canvasSize = it }
        .pointerInput(beacons, canvasSize, lowX, highX, lowY, highY) {
            detectTapGestures { tap ->
                val b = beacons.minByOrNull { val p = point(it.x, it.y); kotlin.math.hypot((p.x - tap.x).toDouble(), (p.y - tap.y).toDouble()) }
                if (b != null) { val p = point(b.x, b.y)
                    if ((p - tap).getDistance() < with(density) { 30.dp.toPx() }) onSelect(b)
                }
            }
        }) {
        truth?.let { val p = point(it.first, it.second); val r = 9.dp.toPx()
            drawLine(Color(0xFF8B35B5), p - Offset(r, r), p + Offset(r, r), 3.dp.toPx())
            drawLine(Color(0xFF8B35B5), p + Offset(-r, r), p + Offset(r, -r), 3.dp.toPx())
        }
        beacons.forEach { b ->
            val p = point(b.x, b.y)
            drawCircle(if (liveIdentity(b) in fresh) Green else Muted, 15.dp.toPx(), p)
            if (b.id == destination) drawCircle(Color(0xFFC96A00), 21.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            paint.textSize = 13.sp.toPx(); paint.color = android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(b.minor.toString(), p.x, p.y + 4.dp.toPx(), paint)
            paint.color = android.graphics.Color.rgb(7, 16, 39)
            val label = when (b.minor) { 1 -> "A1"; 2 -> "A3"; else -> "C7" }
            drawContext.canvas.nativeCanvas.drawText(label, p.x, p.y - 28.dp.toPx(), paint)
        }
        position?.let { val p = point(it.x, it.y)
            drawCircle(Color.White, 11.dp.toPx(), p); drawCircle(Blue, 8.dp.toPx(), p)
            paint.textSize = 12.sp.toPx(); paint.color = android.graphics.Color.rgb(18, 100, 245)
            drawContext.canvas.nativeCanvas.drawText("You (estimate)", p.x.coerceIn(50.dp.toPx(), size.width - 50.dp.toPx()), p.y + 24.dp.toPx(), paint)
        }
    }
}

private data class RangeAnchor(val x: Double, val y: Double, val distance: Double)
private data class TrilateratedPosition(val x: Double, val y: Double, val residualMeters: Double)

// Linear least squares of squared range equations, relative to the first anchor.
// Inputs use meters in one floor's shared coordinate system.
private fun trilaterate2D(anchors: List<RangeAnchor>): TrilateratedPosition {
    require(anchors.size >= 3) { "At least three beacon ranges are required." }
    require(anchors.all { it.x.isFinite() && it.y.isFinite() && it.distance.isFinite() && it.distance >= 0 }) {
        "Beacon coordinates and ranges must be finite; ranges cannot be negative."
    }
    val origin = anchors.first()
    var aa = 0.0; var ab = 0.0; var bb = 0.0; var ac = 0.0; var bc = 0.0
    for (anchor in anchors.drop(1)) {
        val dx = anchor.x - origin.x
        val dy = anchor.y - origin.y
        val a = 2.0 * dx
        val b = 2.0 * dy
        val c = origin.distance * origin.distance - anchor.distance * anchor.distance + dx * dx + dy * dy
        aa += a * a; ab += a * b; bb += b * b; ac += a * c; bc += b * c
    }
    val determinant = aa * bb - ab * ab
    val trace = aa + bb
    require(determinant.isFinite() && trace.isFinite() && trace > 0 && determinant > 1e-10 * trace * trace) {
        "Beacon placement is collinear or too close to collinear. Arrange the beacons in a triangle."
    }
    val x = origin.x + (ac * bb - bc * ab) / determinant
    val y = origin.y + (bc * aa - ac * ab) / determinant
    require(x.isFinite() && y.isFinite()) { "Could not calculate a finite position." }
    val residual = kotlin.math.sqrt(anchors.sumOf {
        val error = kotlin.math.hypot(x - it.x, y - it.y) - it.distance
        error * error
    } / anchors.size)
    require(residual.isFinite()) { "Invalid range residual." }
    return TrilateratedPosition(x, y, residual)
}

private class TrilaterationRssiFilter {
    private var value: Double? = null
    private var variance = 16.0
    fun update(reading: Double): Double {
        require(reading.isFinite())
        val old = value
        if (old == null) { value = reading; return reading }
        val predicted = variance + 0.5
        val gain = predicted / (predicted + 16.0)
        val next = old + gain * (reading - old)
        variance = (1.0 - gain) * predicted
        value = next
        return next
    }
}
private data class TrilaterationReading(val beacon: RegisteredBeacon, val rawDistance: Double, val distance: Double)

@Composable
private fun ThreeBeaconTrilaterationDemo(graph: RoutingGraph, destination: Long, accessibleOnly: Boolean) {
    var allBeacons by remember { mutableStateOf<List<RegisteredBeacon>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var noisy by remember { mutableStateOf(false) }
    var horizontal by remember { mutableStateOf(0.35f) }
    var vertical by remember { mutableStateOf(0.25f) }
    var running by remember { mutableStateOf(true) }
    var reset by remember { mutableStateOf(0) }
    var readings by remember { mutableStateOf<List<TrilaterationReading>>(emptyList()) }
    var estimate by remember { mutableStateOf<TrilateratedPosition?>(null) }
    var solveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(retry) {
        loading = true; loadError = null; allBeacons = emptyList()
        try { allBeacons = requestRegisteredBeacons() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = "Could not load the three beacons. Check /beacons and the server address." }
        finally { loading = false }
    }
    Text("Three-beacon trilateration • SIMULATION", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Text("Sliders generate a virtual position. Ranges are then solved independently to estimate it. This does not receive phone Bluetooth readings.", color = Muted)
    if (loading) { CircularProgressIndicator(); return }
    if (loadError != null) {
        Text(loadError!!, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { retry++ }) { Text("Reload trilateration beacons") }
        return
    }
    // Keep all ranging anchors on one floor and in one advertised UUID/Major group.
    val anchors = allBeacons.groupBy { Triple(it.floorId, it.uuid.lowercase(), it.major) }
        .values.firstOrNull { it.size >= 3 }?.sortedBy { it.minor }?.take(3).orEmpty()
    if (anchors.size < 3) { Text("Register at least three active beacons on the same floor with the same UUID and Major."); return }
    if (anchors.map { it.minor }.distinct().size != anchors.size) { Text("Beacon Minor values must be unique."); return }
    val minX = anchors.minOf { it.x }; val maxX = anchors.maxOf { it.x }
    val minY = anchors.minOf { it.y }; val maxY = anchors.maxOf { it.y }
    val trueX = minX + (maxX - minX) * horizontal
    val trueY = minY + (maxY - minY) * vertical
    val latestX by rememberUpdatedState(trueX)
    val latestY by rememberUpdatedState(trueY)
    val filters = remember(anchors, noisy, reset) { anchors.associate { it.id to TrilaterationRssiFilter() } }
    val random = remember(anchors, noisy, reset) { kotlin.random.Random(83) }
    LaunchedEffect(anchors, noisy, reset, running) {
        if (!running) return@LaunchedEffect
        while (true) {
            try {
                readings = anchors.map { beacon ->
                    val exact = kotlin.math.hypot(latestX - beacon.x, latestY - beacon.y)
                    if (!noisy) TrilaterationReading(beacon, exact, exact)
                    else {
                        // Synthetic n=2 path loss model; near-field distances clamp at 0.25 m.
                        val expectedRssi = beacon.txPower - 20.0 * kotlin.math.log10(exact.coerceAtLeast(0.25))
                        val rawRssi = expectedRssi + (random.nextDouble() * 2.0 - 1.0) * kotlin.math.sqrt(48.0)
                        val filtered = filters.getValue(beacon.id).update(rawRssi)
                        val rawRange = Math.pow(10.0, (beacon.txPower - rawRssi) / 20.0)
                        val filteredRange = Math.pow(10.0, (beacon.txPower - filtered) / 20.0)
                        TrilaterationReading(beacon, rawRange, filteredRange)
                    }
                }
                estimate = trilaterate2D(readings.map { RangeAnchor(it.beacon.x, it.beacon.y, it.distance) })
                solveError = null
            } catch (e: IllegalArgumentException) { estimate = null; solveError = e.message }
            delay(500)
        }
    }
    anchors.forEach { Text("${it.label}: (${it.x}, ${it.y}) • Minor ${it.minor}", color = Muted) }
    Text("Virtual phone X")
    Slider(horizontal, { horizontal = it }, enabled = running)
    Text("Virtual phone Y")
    Slider(vertical, { vertical = it }, enabled = running)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(noisy, { noisy = it }, enabled = running)
        Spacer(Modifier.width(8.dp)); Text("Add RSSI noise + Kalman filtering")
    }
    Row {
        TextButton(onClick = { running = !running }) { Text(if (running) "Pause" else "Resume") }
        TextButton(onClick = { reset++; running = true }) { Text("Reset filters") }
    }
    Text(String.format(java.util.Locale.US, "Virtual position: (%.2f, %.2f) m", trueX, trueY))
    readings.forEach { r ->
        Text(String.format(java.util.Locale.US, "%s range: raw %.2f m • used %.2f m", r.beacon.label, r.rawDistance, r.distance))
    }
    solveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val position = estimate
    if (position != null) {
        Text(String.format(java.util.Locale.US, "Estimated position: (%.2f, %.2f) m", position.x, position.y), fontWeight = FontWeight.Bold)
        Text(String.format(java.util.Locale.US, "Simulation position error: %.2f m • range residual: %.2f m",
            kotlin.math.hypot(position.x - trueX, position.y - trueY), position.residualMeters), color = Muted)
    }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(7, 16, 39); textAlign = android.graphics.Paint.Align.CENTER
    } }
    Canvas(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFEAF1F9))) {
        val margin = 40.dp.toPx()
        val scale = minOf((size.width - 2 * margin).coerceAtLeast(1f) / (maxX - minX).coerceAtLeast(1.0),
            (size.height - 2 * margin).coerceAtLeast(1f) / (maxY - minY).coerceAtLeast(1.0))
        fun point(x: Double, y: Double) = Offset((size.width / 2 + (x - (minX + maxX) / 2) * scale).toFloat(),
            (size.height / 2 - (y - (minY + maxY) / 2) * scale).toFloat())
        paint.textSize = 11.sp.toPx()
        anchors.forEach { beacon ->
            val p = point(beacon.x, beacon.y)
            drawCircle(Color(0xFFB85B00), 8.dp.toPx(), p)
            drawContext.canvas.nativeCanvas.drawText(beacon.label, p.x, p.y - 13.dp.toPx(), paint)
        }
        drawCircle(Color(0xFF697386), 12.dp.toPx(), point(trueX, trueY), style = Stroke(2.dp.toPx()))
        position?.let {
            val p = point(it.x, it.y)
            if (p.x in 0f..size.width && p.y in 0f..size.height) drawCircle(Blue, 7.dp.toPx(), p)
        }
    }
    Text("Orange: beacons • Grey ring: virtual phone • Blue: trilateration estimate. A noisy estimate may fall outside the view.", color = Muted)
    if (position != null) {
        val nearest = graph.nodes.filter { it.floorId == anchors.first().floorId }.minByOrNull {
            kotlin.math.hypot(it.x - position.x, it.y - position.y)
        }
        if (nearest != null) {
            val outcome = remember(graph, nearest.id, destination, accessibleOnly) {
                runCatching { shortestRoute(graph, nearest.id, destination, accessibleOnly) }
            }
            Text("Demo map matching: nearest node ${routingNodeLabel(nearest)}")
            val path = outcome.getOrNull()
            if (outcome.isFailure) Text("Invalid graph for routing.")
            else if (path == null) Text("No route to the selected destination.")
            else {
                Text(String.format(java.util.Locale.US, "Dijkstra distance from snapped node: %.1f m", path.meters))
                Text(path.nodeIds.joinToString(" → ") { id -> routingNodeLabel(graph.nodes.first { it.id == id }) })
            }
        }
    }
    Text("Coordinates and calibration are demo values. Noisy mode assumes n=2 and example Kalman settings. Nearest-node routing does not include distance from the estimate to that node.", color = Muted)
}
private data class LiveBeaconIdentity(val uuid: String, val major: Int, val minor: Int)
private data class LiveBeaconFrame(val identity: LiveBeaconIdentity, val advertisedCalibration: Int)

// Android manufacturer-specific data excludes the little-endian company ID.
private fun parseLiveIBeacon(data: ByteArray): LiveBeaconFrame? {
    if (data.size < 23 || (data[0].toInt() and 255) != 2 || (data[1].toInt() and 255) != 21) return null
    var high = 0L; var low = 0L
    for (i in 2..9) high = (high shl 8) or (data[i].toLong() and 255)
    for (i in 10..17) low = (low shl 8) or (data[i].toLong() and 255)
    fun unsigned16(index: Int) = ((data[index].toInt() and 255) shl 8) or (data[index + 1].toInt() and 255)
    return LiveBeaconFrame(LiveBeaconIdentity(java.util.UUID(high, low).toString(), unsigned16(18), unsigned16(20)), data[22].toInt())
}

private class LiveRssiKalman {
    private var estimate: Double? = null
    private var variance = 16.0
    fun update(rssi: Int): Double {
        require(rssi in -127..-1)
        val prior = estimate
        if (prior == null) { estimate = rssi.toDouble(); return rssi.toDouble() }
        val predicted = variance + 0.5
        val gain = predicted / (predicted + 16.0)
        val next = prior + gain * (rssi - prior)
        variance = (1.0 - gain) * predicted
        estimate = next
        return next
    }
}

private const val LIVE_BEACON_TIMEOUT_MS = 8_000L
private fun isLiveReadingFresh(now: Long, seen: Long): Boolean = seen <= now && now - seen <= LIVE_BEACON_TIMEOUT_MS
private data class LiveSignalReading(
    val identity: LiveBeaconIdentity, val raw: Int, val filtered: Double,
    val seenAt: Long, val samples: Int, val advertisedCalibration: Int
)
private class ForegroundBeaconScanner(context: android.content.Context, beacons: List<RegisteredBeacon>) {
    private val context = context.applicationContext
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val registered = beacons.map { LiveBeaconIdentity(it.uuid.lowercase(java.util.Locale.ROOT), it.major, it.minor) }.toSet()
    private val readings = mutableMapOf<LiveBeaconIdentity, LiveSignalReading>()
    private val filters = mutableMapOf<LiveBeaconIdentity, LiveRssiKalman>()
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var callback: android.bluetooth.le.ScanCallback? = null
    private var generation = 0
    var scanning by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun neededPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(
        android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION
    ) else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasPermissions(): Boolean = neededPermissions().filter { it != android.Manifest.permission.ACCESS_COARSE_LOCATION }
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    fun snapshot(): Map<LiveBeaconIdentity, LiveSignalReading> = readings.toMap()
    fun permissionDenied() { error = "Allow precise Location and, on Android 12+, Nearby devices. If blocked, enable them in the phone's app settings." }
    fun stop() {
        generation++
        scanning = false
        val oldScanner = scanner; val oldCallback = callback
        scanner = null; callback = null
        if (oldCallback != null) try { oldScanner?.stopScan(oldCallback) } catch (_: Exception) { }
    }
    private fun settingsProblem(): String? {
        if (!hasPermissions()) return "Location/Nearby devices permission is missing."
        val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            ?: return "Bluetooth is not supported on this device."
        if (manager.adapter?.isEnabled != true) return "Turn on Bluetooth in the phone settings."
        val locations = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val locationOn = if (Build.VERSION.SDK_INT >= 28) locations.isLocationEnabled else
            locations.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || locations.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        if (!locationOn) return "Turn on the phone's Location setting for this positioning test."
        return null
    }
    fun checkWhileScanning() {
        if (!scanning) return
        try { settingsProblem()?.let { stop(); error = it } }
        catch (_: SecurityException) { stop(); error = "Permissions changed. Grant access and start scanning again." }
    }
    fun start() {
        if (scanning) return
        error = null
        try {
            settingsProblem()?.let { error = it; return }
            val manager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val nextScanner = manager.adapter.bluetoothLeScanner ?: run { error = "Bluetooth scanner unavailable. Check Bluetooth is on."; return }
            readings.clear(); filters.clear()
            val currentGeneration = ++generation
            val nextCallback = object : android.bluetooth.le.ScanCallback() {
                private fun consume(result: android.bluetooth.le.ScanResult) {
                    val payload = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return
                    val frame = parseLiveIBeacon(payload) ?: return
                    if (frame.identity !in registered || result.rssi !in -127..-1) return
                    val seen = result.timestampNanos / 1_000_000L
                    handler.post {
                        if (!scanning || generation != currentGeneration) return@post
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!isLiveReadingFresh(now, seen)) return@post
                        val previous = readings[frame.identity]
                        if (previous != null && seen <= previous.seenAt) return@post
                        if (previous == null || !isLiveReadingFresh(seen, previous.seenAt)) filters[frame.identity] = LiveRssiKalman()
                        val filtered = filters.getValue(frame.identity).update(result.rssi)
                        readings[frame.identity] = LiveSignalReading(frame.identity, result.rssi, filtered, seen,
                            if (previous == null || !isLiveReadingFresh(seen, previous.seenAt)) 1 else previous.samples + 1,
                            frame.advertisedCalibration)
                    }
                }
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) { consume(result) }
                override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>) { results.forEach { consume(it) } }
                override fun onScanFailed(errorCode: Int) { handler.post {
                    if (generation == currentGeneration) { stop(); error = "BLE scan failed (code $errorCode). Stop other scanner apps, wait 30 seconds, then retry." }
                } }
            }
            scanner = nextScanner; callback = nextCallback; scanning = true
            val filter = android.bluetooth.le.ScanFilter.Builder().setManufacturerData(0x004C, byteArrayOf(0x02, 0x15)).build()
            val settings = android.bluetooth.le.ScanSettings.Builder().setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0).build()
            nextScanner.startScan(listOf(filter), settings, nextCallback)
        } catch (_: SecurityException) { stop(); error = "Bluetooth/Location permission is missing. Grant it in app settings." }
        catch (_: Exception) { stop(); error = "Could not start Bluetooth scan. Check permissions and Bluetooth, then retry." }
    }
}

@Composable
private fun LiveBeaconScreen(onBack: () -> Unit) {
    var beacons by remember { mutableStateOf<List<RegisteredBeacon>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        loading = true; loadError = null; beacons = emptyList()
        try {
            val loaded = requestRegisteredBeacons()
            require(loaded.map { LiveBeaconIdentity(it.uuid.lowercase(java.util.Locale.ROOT), it.major, it.minor) }.distinct().size == loaded.size)
            beacons = loaded
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = "Could not load beacon configuration. Check the phone can open $BACKEND_URL/beacons?location_id=$DEMO_LOCATION_ID" }
        finally { loading = false }
    }
    val context = LocalContext.current
    val controller = remember(context, beacons) { ForegroundBeaconScanner(context, beacons) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (controller.hasPermissions()) controller.start() else controller.permissionDenied()
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(controller, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) controller.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.stop() }
    }
    var snapshot by remember(controller) { mutableStateOf<Map<LiveBeaconIdentity, LiveSignalReading>>(emptyMap()) }
    var now by remember(controller) { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(controller) {
        while (true) {
            controller.checkWhileScanning()
            now = android.os.SystemClock.elapsedRealtime()
            snapshot = controller.snapshot()
            delay(500)
        }
    }
    val fresh = if (controller.scanning) snapshot.filterValues { isLiveReadingFresh(now, it.seenAt) } else emptyMap()
    val strongest = fresh.values.filter { it.samples >= 3 }.maxByOrNull { it.filtered }?.identity
    fun identity(b: RegisteredBeacon) = LiveBeaconIdentity(b.uuid.lowercase(java.util.Locale.ROOT), b.major, b.minor)
    val strongestBeacon = beacons.firstOrNull { identity(it) == strongest }
    var floor by remember(beacons) { mutableStateOf(beacons.firstOrNull()?.floorId) }
    Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Live ESP32 signals", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Real Bluetooth readings • no simulation", color = Green, fontWeight = FontWeight.Bold)
        Text("Beacons are matched by UUID + Major + Minor to your database. Keep this screen open during the test.", color = Muted)
        if (loading) CircularProgressIndicator()
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (controller.hasPermissions()) controller.start() else permissionLauncher.launch(controller.neededPermissions())
            }, enabled = !loading && beacons.isNotEmpty() && !controller.scanning) { Text("Start scan") }
            OutlinedButton(onClick = { controller.stop() }, enabled = controller.scanning) { Text("Stop") }
        }
        TextButton(onClick = { controller.stop(); reload++ }, enabled = !loading) { Text("Reload database beacons") }
        Text(if (controller.scanning) "Scanning • ${fresh.size} recent registered signal(s)" else "Scan stopped")
        strongestBeacon?.let { Text("Strongest filtered signal: ${it.label} (Minor ${it.minor})", fontWeight = FontWeight.Bold, color = Blue) }
        if (controller.scanning && fresh.size < 3) Text("With ${fresh.size} recent signal(s), a unique 2D phone position cannot be calculated.", color = Muted)
        if (controller.scanning && fresh.size >= 3) Text("Three signals detected. Measure coordinates and calibrate before enabling a physical position estimate.", color = Muted)
        Text("The strongest signal indicates a beacon area, not a measured phone coordinate. Body orientation, walls and reflections affect RSSI.", color = Muted)
        if (!loading && beacons.isEmpty() && loadError == null) Text("No active beacons registered for this location.")
        beacons.forEach { b ->
            val reading = snapshot[identity(b)]
            val live = fresh[identity(b)]
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${b.label} • Minor ${b.minor}", fontWeight = FontWeight.Bold)
                    Text("Major ${b.major} • Floor ID ${b.floorId}", color = Muted)
                    if (live != null) {
                        Text(String.format(java.util.Locale.US, "RSSI %d dBm • Kalman %.1f dBm", live.raw, live.filtered), color = Green)
                        Text("${live.samples} samples • last seen ${(now - live.seenAt) / 1000}s ago", color = Muted)
                        Text("Broadcast calibration ${live.advertisedCalibration} dBm • database ${b.txPower} dBm", color = Muted)
                    } else Text(if (reading == null) "Not seen during this scan" else "No current signal • last seen ${(now - reading.seenAt).coerceAtLeast(0) / 1000}s ago", color = Muted)
                    Text("Stored beacon coordinates: (${b.x}, ${b.y}) m", color = Muted)
                }
            }
        }
        val floors = beacons.map { it.floorId }.distinct()
        if (floors.isNotEmpty()) {
            AdminChoice("Map floor", floor?.toString(), floors.map { it.toString() to "Floor ID $it" }, true) { floor = it.toLong() }
            val visible = beacons.filter { it.floorId == floor }
            Text("Beacon placement — saved map coordinates", fontWeight = FontWeight.Bold)
            Text("Green: recent signal • grey: not currently heard • blue ring: strongest filtered signal. These dots mark ESP32s, not the phone. Your coordinates are still demo values.", color = Muted)
            LiveBeaconPlacementMap(visible, fresh.keys, strongest)
        }
        Text("Powered beacons can be Minors 1 and 4. Unpowered records remain visible but are excluded after 8 seconds without a fresh reading.", color = Muted)
    }
}

@Composable
private fun LiveBeaconPlacementMap(beacons: List<RegisteredBeacon>, fresh: Set<LiveBeaconIdentity>, strongest: LiveBeaconIdentity?) {
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(7,16,39); textAlign = android.graphics.Paint.Align.CENTER
    } }
    Canvas(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFFEAF1F9))) {
        if (beacons.isEmpty()) return@Canvas
        val minX = beacons.minOf { it.x }; val maxX = beacons.maxOf { it.x }
        val minY = beacons.minOf { it.y }; val maxY = beacons.maxOf { it.y }
        val margin = 45.dp.toPx()
        val scale = minOf((size.width - margin*2).coerceAtLeast(1f)/(maxX-minX).coerceAtLeast(1.0),
            (size.height - margin*2).coerceAtLeast(1f)/(maxY-minY).coerceAtLeast(1.0))
        paint.textSize = 11.sp.toPx()
        beacons.forEach { b ->
            val id = LiveBeaconIdentity(b.uuid.lowercase(java.util.Locale.ROOT), b.major, b.minor)
            val p = Offset((size.width/2 + (b.x-(minX+maxX)/2)*scale).toFloat(),
                (size.height/2 - (b.y-(minY+maxY)/2)*scale).toFloat())
            drawCircle(if (id in fresh) Green else Color(0xFF8591AF), 9.dp.toPx(), p)
            if (id == strongest) drawCircle(Blue, 16.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            val title = "${b.label} (${b.minor})"
            val halfWidth = paint.measureText(title)/2
            val labelX = if (halfWidth*2 < size.width-8.dp.toPx()) p.x.coerceIn(halfWidth+4.dp.toPx(),size.width-halfWidth-4.dp.toPx()) else size.width/2
            drawContext.canvas.nativeCanvas.drawText(title,labelX,p.y-21.dp.toPx(),paint)
        }
    }
}

private enum class BeaconMovementTrend { COLLECTING, STRONGER, WEAKER, STEADY }
private class BeaconTrendTracker {
    private data class History(val points: java.util.ArrayDeque<Pair<Long, Double>> = java.util.ArrayDeque(), var samples: Int = 0)
    private val histories = mutableMapOf<LiveBeaconIdentity, History>()
    fun clear() { histories.clear() }
    fun update(reading: LiveSignalReading): BeaconMovementTrend {
        var history = histories.getOrPut(reading.identity) { History() }
        val previous = history.points.peekLast()
        if (previous != null && (reading.seenAt - previous.first > LIVE_BEACON_TIMEOUT_MS || reading.samples < history.samples)) {
            history = History(); histories[reading.identity] = history
        }
        val last = history.points.peekLast()
        if (last == null || reading.seenAt > last.first) {
            history.points.addLast(reading.seenAt to reading.filtered)
            history.samples = reading.samples
        }
        while (history.points.size > 1 && reading.seenAt - history.points.peekFirst().first > 6_000L) history.points.removeFirst()
        val baseline = history.points.peekFirst() ?: return BeaconMovementTrend.COLLECTING
        if (reading.seenAt - baseline.first < 3_000L) return BeaconMovementTrend.COLLECTING
        val difference = reading.filtered - baseline.second
        return when {
            difference >= 3.0 -> BeaconMovementTrend.STRONGER
            difference <= -3.0 -> BeaconMovementTrend.WEAKER
            else -> BeaconMovementTrend.STEADY
        }
    }
}
private fun movementTrendLabel(trend: BeaconMovementTrend?): String = when (trend) {
    BeaconMovementTrend.STRONGER -> "Signal strengthening — you may be getting closer"
    BeaconMovementTrend.WEAKER -> "Signal weakening — you may be moving farther away"
    BeaconMovementTrend.STEADY -> "Signal roughly steady"
    else -> "Hold or move slowly for a few seconds to compare"
}
private fun beaconMatchesSearch(beacon: RegisteredBeacon, query: String): Boolean {
    val q = query.lowercase(java.util.Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    if (q.isEmpty()) return true
    val compact = q.replace(" ", "")
    val name = beacon.label.lowercase(java.util.Locale.ROOT)
    return name.contains(q) || name.replace(" ", "").contains(compact) ||
            compact == "beacon${beacon.minor}" || compact == "minor${beacon.minor}" || q == beacon.minor.toString()
}
@Composable
private fun UserBeaconHomeScreen(onSignOut: () -> Unit, onDestinations: () -> Unit, onRoutes: () -> Unit, onDiagnostics: () -> Unit,
                                 initialBeaconId: Long? = null, onBack: (() -> Unit)? = null) {
    var beacons by remember { mutableStateOf<List<RegisteredBeacon>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var selectedID by remember(initialBeaconId) { mutableStateOf(initialBeaconId) }
    var selectedFloor by remember { mutableStateOf<Long?>(null) }
    var details by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(reload) {
        loading = true; loadError = null; beacons = emptyList()
        try {
            val loaded = requestRegisteredBeacons()
            require(loaded.map { LiveBeaconIdentity(it.uuid.lowercase(java.util.Locale.ROOT), it.major, it.minor) }.distinct().size == loaded.size)
            beacons = loaded
            if (selectedID != null && loaded.none { it.id == selectedID }) selectedID = null
            if (loaded.none { it.floorId == selectedFloor }) selectedFloor = loaded.firstOrNull()?.floorId
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { loadError = "Could not load beacons. Check $BACKEND_URL and the phone's Wi-Fi connection." }
        finally { loading = false }
    }
    fun identity(b: RegisteredBeacon) = LiveBeaconIdentity(b.uuid.lowercase(java.util.Locale.ROOT), b.major, b.minor)
    val controller = remember(context, beacons) { ForegroundBeaconScanner(context, beacons) }
    val tracker = remember(controller) { BeaconTrendTracker() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (controller.hasPermissions()) controller.start() else controller.permissionDenied()
    }
    LaunchedEffect(controller, loading) {
        if (!loading && beacons.isNotEmpty() && controller.hasPermissions()) controller.start()
    }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(controller, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) controller.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.stop() }
    }
    var now by remember(controller) { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    var signals by remember(controller) { mutableStateOf<Map<LiveBeaconIdentity, LiveSignalReading>>(emptyMap()) }
    var trends by remember(controller) { mutableStateOf<Map<LiveBeaconIdentity, BeaconMovementTrend>>(emptyMap()) }
    LaunchedEffect(controller) {
        while (true) {
            controller.checkWhileScanning()
            now = android.os.SystemClock.elapsedRealtime()
            signals = controller.snapshot()
            if (controller.scanning) {
                trends = signals.filterValues { isLiveReadingFresh(now, it.seenAt) }.mapValues { tracker.update(it.value) }
            } else { tracker.clear(); trends = emptyMap() }
            delay(500)
        }
    }
    val fresh = if (controller.scanning) signals.filterValues { isLiveReadingFresh(now, it.seenAt) } else emptyMap()
    val strongest = fresh.values.filter { it.samples >= 3 }.maxByOrNull { it.filtered }?.identity
    val target = beacons.firstOrNull { it.id == selectedID }
    LaunchedEffect(target?.floorId) { if (target != null) selectedFloor = target.floorId }
    val strongestBeacon = beacons.firstOrNull { identity(it) == strongest }
    fun select(b: RegisteredBeacon) {
        selectedID = b.id; selectedFloor = b.floorId; query = ""
        keyboard?.hide(); focus.clearFocus()
    }
    Column(Modifier.fillMaxSize().background(Color(0xFFF6F8FC)).safeDrawingPadding().imePadding()
        .verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back to search") }
            else Icon(Icons.Default.LocationOn, null, tint = Blue)
            Spacer(Modifier.width(8.dp)); Text(if (onBack != null) "Beacon test" else "Your airport map", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onBack == null) TextButton(onClick = onSignOut) { Text("Sign out") }
        }
        if (onBack == null) OutlinedTextField(query, { query = it }, placeholder = { Text("Search beacons, e.g. Beacon 4") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        controller.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (query.isNotBlank()) {
            val matches = beacons.filter { beaconMatchesSearch(it, query) }
            if (!loading && matches.isEmpty()) Text("No matching registered beacon.")
            matches.take(20).forEach { b ->
                OutlinedButton(onClick = { select(b) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("${b.label} • Beacon ${b.minor}", fontWeight = FontWeight.Bold)
                        Text(if (identity(b) in fresh) "Live signal detected" else "Registered • no current signal", fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ArrowForward, null)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (controller.scanning) "Live • ${fresh.size} beacon signal(s)" else "Live scan paused", color = if (controller.scanning) Green else Muted,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                if (controller.scanning) controller.stop()
                else if (controller.hasPermissions()) controller.start()
                else permissionLauncher.launch(controller.neededPermissions())
            }, enabled = !loading && beacons.isNotEmpty()) { Text(if (controller.scanning) "Pause" else "Start scan") }
        }
        val floors = beacons.map { it.floorId }.distinct()
        if (floors.size > 1) AdminChoice("Map floor", selectedFloor?.toString(), floors.map { it.toString() to "Floor ID $it" }, true) { selectedFloor = it.toLong() }
        Text("${target?.label ?: "Select a beacon"}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        MeasuredLivePositionPanel(beacons, fresh, controller.scanning, target, now) { select(it) }
        strongestBeacon?.let { b ->
            Surface(color = Color(0xFFE9F2FF), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your signal area: ${b.label}", color = Blue, fontWeight = FontWeight.Bold)
                    Text("This beacon currently has the strongest filtered signal. It is an area hint, not a measured phone coordinate.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        if (target != null) {
            val reading = fresh[identity(target)]
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Towards ${target.label}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    if (reading == null) {
                        Text(if (controller.scanning) "No recent signal from this beacon. Keep it powered and stay within reception range." else "Start scanning to compare your movement.")
                    } else {
                        Text(movementTrendLabel(trends[identity(target)]), fontWeight = FontWeight.Bold, color = Blue)
                        LinearProgressIndicator(progress = { ((reading.filtered + 100.0) / 65.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text(String.format(java.util.Locale.US, "Signal strength: %.1f dBm • updated %d s ago", reading.filtered, (now-reading.seenAt)/1000), color = Muted)
                        Text("The trend compares the last few seconds. Walk slowly and keep the phone facing the same direction.", fontSize = 12.sp, color = Muted)
                    }
                    Text("Signal trend is a proximity hint. Use the calibrated position test above to measure actual positioning error.", color = Muted, fontSize = 12.sp)
                }
            }
        } else Text("Search Beacon 4 above or tap marker 4 on the map to track its signal trend.", color = Muted)
        if (onBack == null) Text("Beacons on this floor", fontWeight = FontWeight.Bold)
        if (onBack == null) beacons.filter { it.floorId == selectedFloor }.forEach { b ->
            OutlinedButton(onClick = { select(b) }, modifier = Modifier.fillMaxWidth()) {
                Text("${b.label} (${b.minor})", modifier = Modifier.weight(1f))
                Text(if (identity(b) in fresh) "Live" else "Not heard", color = if (identity(b) in fresh) Green else Muted)
            }
        }
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide signal details" else "Show signal details") }
        if (details) {
            beacons.forEach { b ->
                val r = fresh[identity(b)]
                Text(if (r == null) "${b.label}: no current reading" else String.format(java.util.Locale.US,
                    "%s • raw %d / Kalman %.1f dBm • %d samples", b.label, r.raw, r.filtered, r.samples), color = Muted)
            }
        }
        if (onBack == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDestinations) { Text("Airport destinations") }
            OutlinedButton(onClick = onRoutes) { Text("Route tools") }
        }
        if (onBack == null) TextButton(onClick = onDiagnostics) { Text("Detailed BLE scanner") }
        TextButton(onClick = { controller.stop(); reload++ }, enabled = !loading) { Text("Reload saved beacons") }
    }
}

@Composable
private fun UserLiveBeaconMap(beacons: List<RegisteredBeacon>, fresh: Set<LiveBeaconIdentity>, strongest: LiveBeaconIdentity?,
                              selectedID: Long?, onSelect: (RegisteredBeacon) -> Unit) {
    // One uniform transform is shared by drawing and hit-testing.
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val margin = with(density) { 42.dp.toPx() }
    val minX = beacons.minOfOrNull { it.x } ?: 0.0; val maxX = beacons.maxOfOrNull { it.x } ?: 1.0
    val minY = beacons.minOfOrNull { it.y } ?: 0.0; val maxY = beacons.maxOfOrNull { it.y } ?: 1.0
    fun point(b: RegisteredBeacon): Offset {
        val scale = minOf((canvasSize.width-2*margin).coerceAtLeast(1f)/(maxX-minX).coerceAtLeast(1.0),
            (canvasSize.height-2*margin).coerceAtLeast(1f)/(maxY-minY).coerceAtLeast(1.0))
        return Offset((canvasSize.width/2f+(b.x-(minX+maxX)/2)*scale).toFloat(),
            (canvasSize.height/2f-(b.y-(minY+maxY)/2)*scale).toFloat())
    }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    } }
    Canvas(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFEAF1F9))
        .onSizeChanged { canvasSize = it }
        .pointerInput(beacons, canvasSize, selectedID, fresh, strongest) {
            detectTapGestures { tap ->
                val nearest = beacons.minByOrNull { val p = point(it); kotlin.math.hypot((p.x-tap.x).toDouble(), (p.y-tap.y).toDouble()) }
                if (nearest != null) {
                    val p = point(nearest)
                    if (kotlin.math.hypot((p.x-tap.x).toDouble(), (p.y-tap.y).toDouble()) <= with(density) { 32.dp.toPx() }) onSelect(nearest)
                }
            }
        }) {
        val step = 28.dp.toPx()
        var gridX = 0f
        while (gridX < size.width) { drawLine(Color(0xFFDDE7F3), Offset(gridX,0f), Offset(gridX,size.height),1f); gridX += step }
        var gridY = 0f
        while (gridY < size.height) { drawLine(Color(0xFFDDE7F3), Offset(0f,gridY), Offset(size.width,gridY),1f); gridY += step }
        paint.textSize = 12.sp.toPx()
        beacons.forEach { b ->
            val p = point(b)
            val id = LiveBeaconIdentity(b.uuid.lowercase(java.util.Locale.ROOT),b.major,b.minor)
            if (id == strongest) drawCircle(Blue.copy(alpha=0.13f),25.dp.toPx(),p)
            drawCircle(if (id in fresh) Green else Color(0xFF8591AF),15.dp.toPx(),p)
            if (b.id == selectedID) drawCircle(Color(0xFFC96A00),21.dp.toPx(),p,style=Stroke(3.dp.toPx()))
            paint.color = android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(b.minor.toString(),p.x,p.y+4.dp.toPx(),paint)
            paint.color = android.graphics.Color.rgb(7,16,39)
            val name = b.label.take(22)
            val half = paint.measureText(name)/2
            val labelX = if (half*2 < size.width-8.dp.toPx()) p.x.coerceIn(half+4.dp.toPx(),size.width-half-4.dp.toPx()) else size.width/2
            drawContext.canvas.nativeCanvas.drawText(name,labelX,p.y-28.dp.toPx(),paint)
        }
    }
}
