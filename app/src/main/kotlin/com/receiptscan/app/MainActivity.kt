package com.receiptscan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTopBar
import com.receiptscan.design.EmptyState
import com.receiptscan.design.FloatingNavBar
import com.receiptscan.design.NavItem
import com.receiptscan.design.ReceiptScanTheme
import com.receiptscan.design.ScreenSurface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ReceiptScanTheme {
                var tab by remember { mutableIntStateOf(0) }
                ScreenSurface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        AppTopBar("Receipt & Scan", brand = true)
                        EmptyState(AppIcons.receipt, "Scan your first receipt", "Everything stays on this phone.", Modifier.align(Alignment.Center).padding(20.dp))
                        FloatingNavBar(
                            listOf(NavItem("Home", AppIcons.home), NavItem("Library", AppIcons.library), NavItem("Tools", AppIcons.tools), NavItem("Insights", AppIcons.insights)),
                            tab, { tab = it }, Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
    }
}
