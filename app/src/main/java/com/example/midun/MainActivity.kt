package com.example.midun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import com.example.midun.data.UsbState
import com.example.midun.navigation.NavGraph
import com.example.midun.screen.UsbDisconnectedOverlay
import com.example.midun.screen.UsbToggleButton
import com.example.midun.ui.theme.MiDunDarkTheme
import com.example.midun.ui.theme.MiDunTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // APP启动时重置USB状态为已插入
        UsbState.connect()
        enableEdgeToEdge()
        setContent {
            var isDarkTheme by remember { mutableStateOf(false) }

            val themedContent: @Composable () -> Unit = {
                val navController = rememberNavController()

                Box(modifier = Modifier.fillMaxSize()) {
                    NavGraph(navController = navController)

                    if (!UsbState.isConnected) {
                        UsbDisconnectedOverlay(onCountdownFinished = {
                            finishAndRemoveTask()
                        })
                    }

                    UsbToggleButton(
                        isConnected = UsbState.isConnected,
                        onToggle = { UsbState.toggle() }
                    )

                    ThemeToggleButton(
                        isDark = isDarkTheme,
                        onToggle = { isDarkTheme = !isDarkTheme }
                    )
                }
            }

            if (isDarkTheme) {
                MiDunDarkTheme(content = themedContent)
            } else {
                MiDunTheme(content = themedContent)
            }
        }
    }
}

@Composable
private fun BoxScope.ThemeToggleButton(
    isDark: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 40.dp, end = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF111827) else Color(0xFF1A3A5C)
        ),
        elevation = CardDefaults.cardElevation(8.dp),
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isDark) "切到亮色" else "切到暗色",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}