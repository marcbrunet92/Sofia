package com.lemarc.sofiaproduction.ui.dashboard

import android.content.Context
import android.widget.TextView
import com.github.mikephil.charting.components.MarkerView
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.utils.MPPointF
import com.lemarc.sofiaproduction.R
import com.lemarc.sofiaproduction.data.GenerationPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ChartMarkerView(
    context: Context,
    private var history: List<GenerationPoint> = emptyList()
) : MarkerView(context, R.layout.view_chart_marker) {

    private val tvTime: TextView = findViewById(R.id.marker_time)
    private val tvValue: TextView = findViewById(R.id.marker_value)

    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneOffset.UTC)

    fun updateHistory(newHistory: List<GenerationPoint>) {
        history = newHistory
    }

    override fun refreshContent(e: Entry?, highlight: Highlight?) {
        if (e == null) return
        val idx = e.x.toInt()
        val timeStr = if (idx in history.indices) {
            runCatching {
                val raw = history[idx].timeFrom.replace(' ', 'T')
                dateFmt.format(Instant.parse(raw)) + " UTC"
            }.getOrDefault("")
        } else ""
        tvTime.text = timeStr
        tvValue.text = "${e.y.toInt()} MW"
        super.refreshContent(e, highlight)
    }

    override fun getOffset(): MPPointF = MPPointF(-(width / 2f), -height.toFloat() - 12f)
}
