package com.exciteddev.flironepro.ui

import android.content.Context
import android.widget.ScrollView

/** A ScrollView that never grows taller than [maxHeightPx]. */
class MaxHeightScrollView(context: Context) : ScrollView(context) {
    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
    }
}
