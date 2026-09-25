/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.aottraining.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.jetbrains.aottraining.core.Catalog
import org.jetbrains.aottraining.core.Discount
import org.jetbrains.aottraining.core.Item
import org.jetbrains.aottraining.core.Price
import org.jetbrains.aottraining.core.describe

private val catalog = Catalog(
    listOf(
        Item(1, "Pen", Price(150), setOf("office")),
        Item(2, "Notebook", Price(499), setOf("office", "paper")),
        Item(3, "Mug", Price(899)),
    )
)

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AOT training") {
        MaterialTheme {
            CatalogScreen()
        }
    }
}

@Composable
fun CatalogScreen() {
    var discountPercent by remember { mutableStateOf(0) }
    Column(modifier = Modifier.padding(16.dp)) {
        for (item in catalog.items) {
            Text(describe(item))
        }
        Row {
            Button(onClick = { discountPercent = (discountPercent + 10) % 60 }) {
                Text("Discount: $discountPercent%")
            }
            Text(
                text = "Total: ${catalog.total(Discount.Percentage(discountPercent))}",
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}
