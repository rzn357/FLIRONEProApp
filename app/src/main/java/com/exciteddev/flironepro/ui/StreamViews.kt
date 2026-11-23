package com.exciteddev.flironepro.ui

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.exciteddev.flironepro.R

/** The views of the live screen, found once. */
class StreamViews(activity: Activity) {
    val rootView: FrameLayout = activity.findViewById(R.id.root)
    val thermalView: ThermalView = activity.findViewById(R.id.thermalView)
    val topBar: View = activity.findViewById(R.id.topBar)
    val statusPill: View = activity.findViewById(R.id.statusPill)
    val batteryIcon: ImageView = activity.findViewById(R.id.batteryIcon)
    val batteryText: TextView = activity.findViewById(R.id.batteryText)
    val fpsText: TextView = activity.findViewById(R.id.fpsText)
    val bottomBar: View = activity.findViewById(R.id.bottomBar)
    val btnVideo: View = activity.findViewById(R.id.btnVideo)
    val videoIcon: ImageView = activity.findViewById(R.id.videoIcon)
    val videoTimer: TextView = activity.findViewById(R.id.videoTimer)
    val cardHost: View = activity.findViewById(R.id.cardHost)
    val calCard: View = activity.findViewById(R.id.calCard)
    val calText: TextView = activity.findViewById(R.id.calText)
    val alignCard: View = activity.findViewById(R.id.alignCard)
    val alignInfo: TextView = activity.findViewById(R.id.alignInfo)
    val settingsHost: FrameLayout = activity.findViewById(R.id.settingsHost)
}

/** How this run uses the screen: normally, or with the demo player / the self-test (debuggable builds only). */
class RunMode(@Volatile var demo: Boolean = false, @Volatile var selfTest: Boolean = false)
