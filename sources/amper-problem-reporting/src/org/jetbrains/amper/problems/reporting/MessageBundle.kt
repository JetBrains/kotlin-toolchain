/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.problems.reporting

import org.jetbrains.annotations.Nls
import java.text.MessageFormat
import java.util.*

open class MessageBundle(bundleName: String) {
    private val resourceBundle = ResourceBundle.getBundle(
        bundleName,
        Locale.getDefault(),
        // The default uses the caller's class loader, which doesn't work nicely if the bundle itself was loaded in
        // a different class loader (e.g., in content modules of the IntelliJ plugin).
        this::class.java.classLoader,
    )

    fun message(messageKey: String, vararg arguments: Any?): @Nls String {
        if (!resourceBundle.containsKey(messageKey)) {
            return messageKey
        }
        return MessageFormat(resourceBundle.getString(messageKey)).format(arguments)
    }
}
