package com.veer.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VeerScreen()
        }
    }
}

@Composable
fun VeerScreen() {
    var status by remember { mutableStateOf("SYSTEM ONLINE") }

    val cyan = Color(0xFF35E8FF)
    val background = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF061B2B),
            Color(0xFF020812),
            Color.Black
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "V E E R",
            color = cyan,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "PERSONAL AI ASSISTANT",
            color = Color.LightGray,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(45.dp))

        Box(
            modifier = Modifier
                .size(230.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF126C91),
                            Color(0xFF07304A),
                            Color.Transparent
                        )
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "◉",
                color = cyan,
                fontSize = 115.sp
            )
        }

        Spacer(modifier = Modifier.height(35.dp))

        Text(
            text = status,
            color = cyan,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { status = "VEER IS READY, BOSS" },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF087E9B)
            )
        ) {
            Text("INITIALIZE VEER")
        }

        Spacer(modifier = Modifier.height(15.dp))

        Text(
            text = "YOUR AI. YOUR COMMAND.",
            color = Color.Gray,
            fontSize = 11.sp
        )
    }
}
