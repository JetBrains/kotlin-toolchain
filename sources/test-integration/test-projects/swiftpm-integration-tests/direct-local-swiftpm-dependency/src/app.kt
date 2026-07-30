/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

class CallLocalPackage() {

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun call() {
        swiftPMImport.direct.local.swiftpm.dependency.ObjCCompatibleSwift().doSomething()
        swiftPMImport.direct.local.swiftpm.dependency.ObjCTarget().doSomethingObjC()
    }
}