/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.tasks.ios

import com.jetbrains.cidr.xcode.plist.InvalidSpecException
import com.jetbrains.cidr.xcode.plist.Plist
import com.jetbrains.cidr.xcode.plist.XMLPlistDriver
import com.jetbrains.cidr.xcode.plist.impl.PlistDriver
import org.jetbrains.amper.cli.userReadableError
import java.io.IOException
import java.nio.file.Path

/**
 * Reads the plist at the given [path] in any of the supported formats (XML, binary, or old-style NeXTSTEP).
 */
internal fun readPlist(path: Path): Plist = try {
    PlistDriver.readAnyFormat(path.toFile())
} catch (e: IOException) {
    userReadableError("Unable to read the property list at '$path': ${e.message}", cause = e)
} catch (e: InvalidSpecException) {
    userReadableError("Unable to parse the property list at '$path': ${e.message}", cause = e)
}

/**
 * Writes the given [plist] to the given [path] in the XML format.
 */
internal fun writePlist(plist: Plist, path: Path) {
    try {
        XMLPlistDriver().write(plist, path.toFile())
    } catch (e: IOException) {
        userReadableError("Unable to write the property list at '$path': ${e.message}", cause = e)
    }
}
