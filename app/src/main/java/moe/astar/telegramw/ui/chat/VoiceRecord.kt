package moe.astar.telegramw.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.wear.compose.material.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.astar.telegramw.R
import moe.astar.telegramw.Screen
import moe.astar.telegramw.client.VoiceRecorder
import org.drinkless.tdlib.TdApi

@Composable
fun VoiceRecordScreen(
    chatId: Long,
    threadId: Long,
    viewModel: ChatViewModel,
    navController: NavController,
    voiceRecorder: VoiceRecorder
) {
    var isRecording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (isRecording) {
                recordingTime = System.currentTimeMillis() - startTime
                delay(100)
            }
        } else {
            recordingTime = 0L
        }
    }

    Scaffold(
        timeText = { TimeText() }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!hasPermission) {
                Text(
                    "Need microphone permission",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption1
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Grant")
                }
            } else {
                Text(
                    text = if (isRecording) {
                        val seconds = (recordingTime / 1000) % 60
                        val minutes = (recordingTime / (1000 * 60)) % 60
                        String.format("%02d:%02d", minutes, seconds)
                    } else "Voice Message",
                    style = MaterialTheme.typography.title2
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isRecording) {
                        Button(
                            onClick = {
                                isRecording = false
                                voiceRecorder.cancelRecording()
                                navController.popBackStack()
                            },
                            colors = ButtonDefaults.secondaryButtonColors()
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.outline_close_24),
                                contentDescription = "Cancel"
                            )
                        }
                        Button(
                            onClick = {
                                isRecording = false
                                viewModel.stopAndSendVoiceNote(chatId, threadId, recordingTime)
                                navController.popBackStack(Screen.ChatMenu.route, inclusive = true)
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.baseline_play_arrow_24), // Use a "send" or "stop" icon
                                contentDescription = "Send"
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                if (voiceRecorder.startRecording()) {
                                    isRecording = true
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.baseline_mic_24),
                                contentDescription = "Start Recording"
                            )
                        }
                    }
                }
            }
        }
    }
}
