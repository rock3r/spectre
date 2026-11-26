package dev.sebastiano.spectre.sample.validation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** Headed, release-verification-only target for attached multi-window CLI/MCP smoke. */
fun main() =
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Spectre attached fixture A",
            state = rememberWindowState(
                position = WindowPosition.Absolute(40.dp, 80.dp),
                size = DpSize(760.dp, 760.dp),
            ),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF174EA6)).testTag("attached.window.a"),
                contentAlignment = Alignment.Center,
            ) {
                Text("STABLE WINDOW A", color = Color.White, fontSize = 32.sp)
            }
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "Spectre attached fixture B",
            state = rememberWindowState(
                position = WindowPosition.Absolute(920.dp, 80.dp),
                size = DpSize(760.dp, 760.dp),
            ),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFFE8710A)).testTag("attached.window.b"),
                contentAlignment = Alignment.Center,
            ) {
                Text("STABLE WINDOW B", color = Color.Black, fontSize = 32.sp)
            }
        }
    }
