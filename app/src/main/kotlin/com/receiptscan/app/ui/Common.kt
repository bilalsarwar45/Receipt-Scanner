package com.receiptscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AppTopBar
import com.receiptscan.design.AppType
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.skin
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat

/** Standard pushed screen: top bar, scrolling content, optional sticky bottom bar. */
@Composable
fun Page(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ScreenSurface {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AppTopBar(title, onBack = onBack, actions = actions)
            Column(
                Modifier.weight(1f).fillMaxWidth().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
            if (bottom != null) Column(Modifier.padding(16.dp)) { bottom() }
        }
    }
}

@Composable
fun Section(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(title, style = AppType.h2, color = skin.text, modifier = Modifier.weight(1f))
        trailing()
    }
}

fun fmt(m: Money?) = m?.let { MoneyFormat.format(it) } ?: "—"
fun fmtShort(m: Money?) = m?.let { MoneyFormat.formatShort(it) } ?: "—"
