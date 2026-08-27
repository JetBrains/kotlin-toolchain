/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun googleMapsCameraPosition(): swiftPMImport.lib.GMSCameraPosition {
    swiftPMImport.lib.GMSServices.provideAPIKey("API_KEY")
    val cameraPosition = swiftPMImport.lib.GMSCameraPosition(latitude = 47.6089945, longitude = -122.3410462, zoom = 14F)
    return cameraPosition
}