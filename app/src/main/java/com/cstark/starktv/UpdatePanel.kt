package com.cstark.starktv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Full-screen progress for the top-bar Update (Updater.startUpdateNow). Steps: Contacting server -> Waiting for Tower to
 * install (or, when Tower can't, Downloading on this TV) -> Installing, the app will restart. Focus stays on the
 * Cancel/Close button; Back (MainScreen's BackHandler) or that button cancels the download / closes the panel.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun UpdatePanel() {
    val u = Updater
    val accent = LocalAccent.current
    val btn = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(btn) }
    val step = u.nowStep
    val order = when (step) {
        UpStep.Contacting -> 0
        UpStep.Waiting, UpStep.Downloading -> 1
        UpStep.Confirm, UpStep.Installing -> 2
        UpStep.Failed -> -1
    }
    Box(Modifier.fillMaxSize().background(Color(0xF20A0B0E)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(720.dp)
                .background(Pal.Card, RoundedCornerShape(16.dp))
                .padding(36.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (u.nowTarget > 0) "Updating to build ${u.nowTarget}" else "Updating Starsky TV",
                fontSize = 30.sp, fontWeight = FontWeight.Light, color = accent,
            )
            val second = if (u.nowViaTower) "Waiting for Tower to install…" else {
                val p = u.nowProgress
                if (p >= 0f) "Downloading  ${(p * 100).roundToInt()}%" else "Downloading"
            }
            val third = if (u.nowViaTower) "Installing, the app will restart" else "Installing (Android asks you to confirm)"
            listOf("Contacting server", second, third).forEachIndexed { i, label ->
                val mark = when {
                    step == UpStep.Failed -> "•"
                    i < order -> "✓"
                    i == order -> "▶"
                    else -> "○"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mark, fontSize = 20.sp, color = if (i == order) accent else Pal.Dim, modifier = Modifier.width(36.dp))
                    Text(label, fontSize = 20.sp, color = if (i <= order) Color.White else Pal.Dim)
                }
            }
            if (step == UpStep.Downloading && u.nowProgress >= 0f) {
                LinearProgressIndicator(
                    progress = { u.nowProgress.coerceIn(0f, 1f) },
                    color = accent,
                    trackColor = Color(0x33FFFFFF),
                    modifier = Modifier.width(648.dp).padding(top = 4.dp),
                )
            }
            if (u.nowMsg.isNotBlank()) {
                Text(
                    u.nowMsg, fontSize = 17.sp, lineHeight = 24.sp,
                    color = if (step == UpStep.Failed) Color(0xFFFFB4A8) else Color.White,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            val done = step == UpStep.Failed || step == UpStep.Confirm
            Box(Modifier.padding(top = 10.dp)) {
                Chip(if (done) "Close" else "Cancel", fr = btn, compact = true) { u.cancelUpdateNow() }
            }
            Text("Back also cancels", fontSize = 13.sp, color = Pal.Dim)
        }
    }
}
