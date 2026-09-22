/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

// The sources play no role in the SwiftPM metadata publication, but a library without any of them has nothing to
// publish for its platforms, which makes it unresolvable for consumers.
fun greeting(): String = "Hello from a library with SwiftPM dependencies"
