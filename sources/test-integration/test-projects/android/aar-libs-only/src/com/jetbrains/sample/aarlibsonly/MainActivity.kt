package com.jetbrains.sample.aarlibsonly

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.netease.nrtc.engine.rawapi.RtcConfig

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rtcConfig = RtcConfig()
        Log.d("aar-libs-only", "RTC type: ${rtcConfig.rtc_type}")
    }
}
