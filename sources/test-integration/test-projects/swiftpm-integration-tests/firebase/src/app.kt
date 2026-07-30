/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

class CallFirebase() {
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun createFirestore(): swiftPMImport.firebase.FIRFirestore {
        println("Calling Firebase from Kotlin")
        return swiftPMImport.firebase.FIRFirestore.Companion.firestore()
    }
}