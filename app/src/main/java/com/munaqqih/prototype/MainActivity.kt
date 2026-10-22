package com.munaqqih.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        setContent {
            MaterialTheme {
                // الواجهة عربية من اليمين لليسار حتى لو لغة الجوال إنجليزي
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MainScreen()
                }
            }
        }
    }
}
