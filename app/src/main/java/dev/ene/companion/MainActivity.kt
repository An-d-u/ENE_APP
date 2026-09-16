package dev.ene.companion

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import dev.ene.companion.ui.ConnectionScreen

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        (application as EneApplication).connection.activityResumed(true)
    }

    override fun onPause() {
        (application as EneApplication).connection.activityResumed(false, isChangingConfigurations)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 최근 앱 미리보기·시스템 캡처에 대화와 QR이 남지 않도록 한다.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                ConnectionScreen((application as EneApplication).connection)
            }
        }
    }
}
