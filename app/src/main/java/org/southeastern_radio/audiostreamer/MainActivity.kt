package org.southeastern_radio.audiostreamer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import org.southeastern_radio.audiostreamer.R.drawable.logo
import org.southeastern_radio.audiostreamer.ui.theme.AudioStreamerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// StreamItem data class (can remain the same)
data class StreamItem(val name: String, val url: String)

class MainActivity : ComponentActivity() {

    private var audioPlayerService: AudioPlayerService? = null
    private var isBound = false
    private var servicePlayerDataFlow: StateFlow<PlayerServiceData>? = null

    // For Android 13+ Notification Permission
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Notification permission granted.")
                // You might want to re-initiate play if it was pending permission
            } else {
                Log.w("MainActivity", "Notification permission denied.")
                // Inform user that background playback might not work as expected
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AudioPlayerService.LocalBinder
            audioPlayerService = binder.getService()
            isBound = true
            servicePlayerDataFlow = audioPlayerService?.playerServiceDataFlow
            Log.d("MainActivity", "AudioPlayerService Connected")
            // Force recomposition to pass the flow to the Composable
            // This is a simplified way; a ViewModel would be cleaner
            setContent { // Re-set content to pass the new flow
                AppContent()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            audioPlayerService = null
            servicePlayerDataFlow = null
            Log.d("MainActivity", "AudioPlayerService Disconnected")
            setContent { // Re-set content as flow is now null
                AppContent()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            AppContent()
        }
    }

    @Composable
    fun AppContent() {
        AudioStreamerTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AudioStreamerScreen(
                    playerDataFlow = servicePlayerDataFlow, // Pass the flow from the bound service
                    onPlayRequest = { streamUrl, streamName ->
                        val intent = Intent(this, AudioPlayerService::class.java).apply {
                            action = AudioPlayerService.ACTION_PLAY
                            putExtra(AudioPlayerService.EXTRA_STREAM_URL, streamUrl)
                            putExtra(AudioPlayerService.EXTRA_STREAM_NAME, streamName)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(intent)
                        } else {
                            startService(intent)
                        }
                        // Bind if not already bound, service will start itself as foreground
                        if (!isBound) {
                            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
                        }
                    },
                    onStopRequest = {
                        if (isBound) { // Send stop command only if bound and service exists
                            val intent = Intent(this, AudioPlayerService::class.java).apply {
                                action = AudioPlayerService.ACTION_STOP
                            }
                            startService(intent) // Service will handle stopping player and itself
                        }
                        // Unbinding is handled in onStop or when service stops itself
                    }
                )
            }
        }
    }


    override fun onStart() {
        super.onStart()
        // Bind to service if it might be running (e.g., started by a previous session)
        // or if we want to immediately connect upon app start.
        // For simplicity, we bind when play is requested or if service is already running.
        // Let's ensure we bind if the service is expected to be up.
        // A better approach would be to check if service is running.
        // For now, we'll try to bind, and if play is hit, it will start/bind.
        Intent(this, AudioPlayerService::class.java).also { intent ->
            // Check if service is already running to decide if BIND_AUTO_CREATE is enough
            // or if we should also start it to get onServiceConnected if it's alive.
            // For this setup, BIND_AUTO_CREATE is fine. The play action will startForegroundService.
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            // Unbind from the service.
            // If the service is in a state where it should stop (e.g., not playing),
            // it should call stopSelf(). If it's playing, it should remain a foreground service.
            // The service manages its own lifecycle regarding stopSelf() when playback ends or is stopped.
            // We unbind because the Activity is no longer visible.
            unbindService(serviceConnection)
            isBound = false
            servicePlayerDataFlow = null // Clear the flow
            // Recompose to reflect that service is no longer directly bound for UI updates.
            // The service itself will continue running if it's in foreground mode.
            setContent { AppContent() }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioStreamerScreen(
    playerDataFlow: StateFlow<PlayerServiceData>?,
    onPlayRequest: (streamUrl: String, streamName: String) -> Unit,
    onStopRequest: () -> Unit
) {
    val imageUrl = "https://southeastern-radio.org/wp-content/uploads/appImage/appImage.jpg"
    val context = LocalContext.current

    // Observe data from the service
    val playerData by playerDataFlow?.collectAsState() ?: remember { mutableStateOf(PlayerServiceData()) }

    // UI State derived from service's playerData
    val currentServiceState = playerData.state
    val statusMessage = when (playerData.state) {
        PlayerServiceState.IDLE -> "Select or enter a stream and press Play."
        PlayerServiceState.BUFFERING -> "Buffering: ${playerData.currentStreamName ?: "Stream"}..."
        PlayerServiceState.PLAYING -> "Playing Stream: ${playerData.currentStreamName ?: "Stream"}"
        PlayerServiceState.STOPPED -> "Stopped."
        PlayerServiceState.ERROR -> "Error: ${playerData.errorMessage ?: "Could not play stream."}"
    }
    val currentArtist = playerData.currentArtist
    val currentTitle = playerData.currentTitle
    val currentStation = playerData.currentStreamName // This now primarily comes from service

    val predefinedStreams = remember {
        listOf(
            StreamItem("Southeastern Mix Genre Stream", "https://stream.southeastern-radio.org/radio-mix.mp3"),
            StreamItem("Southeastern Country Stream", "https://stream.southeastern-radio.org/country.mp3"),
            StreamItem("Southeastern Blues Stream", "https://stream.southeastern-radio.org/blues.mp3"),
            StreamItem("Southeastern Gospel Stream", "https://stream.southeastern-radio.org/gospel.mp3"),
            StreamItem("Southeastern Classical/Easy Stream", "https://stream.southeastern-radio.org/easy.mp3"),
            StreamItem("Custom URL", "")
        )
    }

    var expanded by remember { mutableStateOf(false) }
    // Initialize selectedStreamItem, try to match if a stream is already playing from service
    var selectedStreamItem by remember(playerData.currentStreamName, playerData.state) {
        mutableStateOf(
            if (playerData.state == PlayerServiceState.PLAYING || playerData.state == PlayerServiceState.BUFFERING) {
                predefinedStreams.find { it.name == playerData.currentStreamName }
                    ?: predefinedStreams.find { it.url == (playerDataFlow?.value?.currentStreamName) } // Assuming URL might be stored as name temporarily
                    ?: StreamItem("Custom URL", playerDataFlow?.value?.currentStreamName ?: "") // Fallback for custom
            } else {
                predefinedStreams.first()
            }
        )
    }

    var customStreamUrlInput by remember(selectedStreamItem.url) { mutableStateOf(selectedStreamItem.url) }

    // If "Custom URL" is selected, customStreamUrlInput should be editable and reflect the service's current URL if it's custom
    if (selectedStreamItem.name == "Custom URL") {
        LaunchedEffect(playerData.currentStreamName, playerData.state) {
            if (playerData.state == PlayerServiceState.PLAYING || playerData.state == PlayerServiceState.BUFFERING) {
                // Check if the service URL matches any predefined stream. If not, it's custom.
                val isPredefined = predefinedStreams.any { it.name == playerData.currentStreamName && it.name != "Custom URL" }
                if (!isPredefined) {
                    customStreamUrlInput = playerDataFlow?.value?.currentStreamName ?: "" // This assumes URL is in currentStreamName for custom
                }
            }
        }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Image(
            painter = painterResource(id = logo), // (A)
            contentDescription = "App Logo",      // (B)
            modifier = Modifier
                .height(100.dp)           // (C)
                .padding(bottom = 16.dp)          // (D)
        )
        Text(
            text = "Southeastern Radio Player",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = if (selectedStreamItem.name == "Custom URL") customStreamUrlInput else selectedStreamItem.name,
                onValueChange = {
                    if (selectedStreamItem.name == "Custom URL") {
                        customStreamUrlInput = it
                    }
                },
                label = { Text("Stream Source") },
                readOnly = selectedStreamItem.name != "Custom URL" || (currentServiceState == PlayerServiceState.PLAYING || currentServiceState == PlayerServiceState.BUFFERING),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.textFieldColors(),
                modifier = Modifier
                    //.menuAnchor()
                    .menuAnchor(type = MenuAnchorType.PrimaryEditable) // <-- UPDATED
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                predefinedStreams.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            selectedStreamItem = item
                            customStreamUrlInput = if (item.name == "Custom URL") "" else item.url
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)) {
            (currentStation)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 4.dp)
                )
            }
            if (!currentTitle.isNullOrBlank()) {
                Text(
                    text = "Title: \n$currentTitle",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!currentArtist.isNullOrBlank()) {
                Text(
                    text = "Artist: $currentArtist",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (currentTitle.isNullOrBlank() && currentArtist.isNullOrBlank() && (currentServiceState == PlayerServiceState.PLAYING || currentServiceState == PlayerServiceState.BUFFERING)) {
                Text(
                    text = "Fetching metadata...",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))


        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    val urlToPlay = if (selectedStreamItem.name == "Custom URL") customStreamUrlInput else selectedStreamItem.url
                    val nameToPlay = if (selectedStreamItem.name == "Custom URL") "Custom Stream" else selectedStreamItem.name
                    if (urlToPlay.isNotBlank()) {
                        onPlayRequest(urlToPlay, nameToPlay)
                    }
                },
                enabled = currentServiceState == PlayerServiceState.IDLE ||
                        currentServiceState == PlayerServiceState.STOPPED ||
                        currentServiceState == PlayerServiceState.ERROR
            ) {
                Text("Play")
            }

            Button(
                onClick = { onStopRequest() },
                enabled = currentServiceState == PlayerServiceState.PLAYING ||
                        currentServiceState == PlayerServiceState.BUFFERING
            ) {
                Text("Stop")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = stringResource(R.string.app_version),

            )

        // --- DISPLAY IMAGE FROM THE SPECIFIED URL ---
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .diskCachePolicy(CachePolicy.DISABLED) // Disable disk cache for this request
                .memoryCachePolicy(CachePolicy.DISABLED) // Disable memory cache for this request
                .crossfade(true) // Optional: for a smoother transition
                // .placeholder(painterResource(id = R.drawable.placeholder_image)) // Optional
                // .error(painterResource(id = R.drawable.error_image)) // Optional
                .build(),
            contentDescription = "Featured Artist Image", // Provide a relevant description
            modifier = Modifier
                .size(
                    400
                        .dp
                ) // You can adjust the size as needed
                .padding(bottom = 5.dp),

            contentScale = ContentScale.Fit, // Or ContentScale.Crop, etc.
            // Optional: Add placeholder and error drawables for better UX
            // placeholder = painterResource(id = R.drawable.placeholder_image),
            // error = painterResource(id = R.drawable.error_image)
        )
        // --- END OF IMAGE DISPLAY ---


    }
}

// --- ADDED PREVIEW FUNCTIONS ---

@Preview(showBackground = true, name = "AudioStreamerScreen Idle")
@Composable
fun AudioStreamerScreenIdlePreview() {
    AudioStreamerTheme { // Use your app's theme
        val mockIdleDataFlow = MutableStateFlow(
            PlayerServiceData(
                state = PlayerServiceState.IDLE,
                currentStreamName = null,
                currentTitle = null,
                currentArtist = null,
                errorMessage = null
            )
        )
        AudioStreamerScreen(
            playerDataFlow = mockIdleDataFlow,
            onPlayRequest = { url, name -> Log.d("Preview", "Play: $name - $url") },
            onStopRequest = { Log.d("Preview", "Stop") }
        )
    }
}

@Preview(showBackground = true, name = "AudioStreamerScreen Playing")
@Composable
fun AudioStreamerScreenPlayingPreview() {
    AudioStreamerTheme { // Use your app's theme
        val mockPlayingDataFlow = MutableStateFlow(
            PlayerServiceData(
                state = PlayerServiceState.PLAYING,
                currentStreamName = "Southeastern Mix Genre Stream",
                currentTitle = "The Best Song Ever",
                currentArtist = "Awesome Artist",
                errorMessage = null
            )
        )
        AudioStreamerScreen(
            playerDataFlow = mockPlayingDataFlow,
            onPlayRequest = { url, name -> Log.d("Preview", "Play: $name - $url") },
            onStopRequest = { Log.d("Preview", "Stop") }
        )
    }
}

// --- END OF ADDED PREVIEW FUNCTIONS ---