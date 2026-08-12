/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import com.example.shared.gen.Res
import com.example.shared.gen.shared_hello
import org.jetbrains.compose.resources.stringResource

@Composable
fun SharedText() {
    BasicText(stringResource(Res.string.shared_hello))
}
