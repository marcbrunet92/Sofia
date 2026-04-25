package com.lemarc.sofiaproduction.ui.dashboard

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.lemarc.sofiaproduction.R
import com.lemarc.sofiaproduction.data.FarmSnapshot
import com.lemarc.sofiaproduction.data.GenerationPoint
import com.lemarc.sofiaproduction.data.INSTALLED_MW
import com.lemarc.sofiaproduction.databinding.FragmentDashboardBinding
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val vm: DashboardViewModel by viewModels()

    private val hourFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
    private val dateFmt = DateTimeFormatter.ofPattern("dd/MM").withZone(ZoneOffset.UTC)

    private lateinit var chartMarker: ChartMarkerView

    /** Full MAX_CHART_DAYS history cached from the last successful API response. */
    private var fullHistory: List<GenerationPoint> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChart()
        setupPeriodSlider()
        setupSwipeRefresh()
        observeState()
    }

    // ── Period slider ────────────────────────────

    private fun setupPeriodSlider() {
        binding.sliderChartDays.value = vm.chartDays.value.toFloat()

        // Immediately update chart days; no API call is needed because the full history
        // is already loaded — the chart just slices it in-memory.
        binding.sliderChartDays.addOnChangeListener { _, value, fromUser ->
            if (fromUser) vm.setChartDays(value.toInt())
        }

        // Observe chartDays: update the label and re-render the chart slice in-memory.
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.chartDays.collect { days ->
                    updatePeriodLabel(days)
                    if (binding.sliderChartDays.value != days.toFloat()) {
                        binding.sliderChartDays.value = days.toFloat()
                    }
                    if (fullHistory.isNotEmpty()) {
                        renderChart(sliceHistory(fullHistory, days))
                    }
                }
            }
        }
    }

    private fun updatePeriodLabel(days: Int) {
        binding.tvChartPeriodLabel.text = if (days == 1) {
            getString(R.string.chart_period_one_day)
        } else {
            getString(R.string.chart_period_days, days)
        }
    }

    // ── SwipeRefreshLayout ───────────────────────

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener { vm.refresh() }
        binding.swipeRefresh.setColorSchemeResources(R.color.accent_cyan)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.refreshing.collect { binding.swipeRefresh.isRefreshing = it }
            }
        }
    }

    // ── State observation ────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { state ->
                    when (state) {
                        is UiState.Loading -> showLoading()
                        is UiState.Success -> showSnapshot(state.snapshot)
                        is UiState.Error   -> showError(state.message)
                    }
                }
            }
        }
    }

    // ── Loading / Error ──────────────────────────

    private fun showLoading() {
        binding.contentGroup.visibility = View.GONE
        binding.errorGroup.visibility   = View.GONE
        binding.loadingGroup.visibility = View.VISIBLE
    }

    private fun showError(msg: String) {
        binding.loadingGroup.visibility = View.GONE
        binding.contentGroup.visibility = View.GONE
        binding.errorGroup.visibility   = View.VISIBLE
        binding.tvError.text = getString(R.string.error_template, msg)
        binding.btnRetry.setOnClickListener { vm.refresh() }
    }

    // ── Success / Snapshot ───────────────────────

    private fun showSnapshot(snap: FarmSnapshot) {
        binding.loadingGroup.visibility = View.GONE
        binding.errorGroup.visibility   = View.GONE
        binding.contentGroup.visibility = View.VISIBLE

        // Headline numbers
        binding.tvCurrentMw.text = getString(R.string.mw_template, snap.latestMW.toInt())
        val pct = (snap.capacityFactor * 100).toInt()
        binding.tvCapacityFactor.text = getString(R.string.cf_template, pct)
        binding.progressCapacity.progress = pct

        // Source badge
        binding.tvSource.text = if (snap.source == "b1610") "Metered ✓" else "Forecast ~"
        binding.tvSource.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (snap.source == "b1610") R.color.accent_cyan else R.color.accent_amber
            )
        )

        // Last updated
        val updatedLabel = runCatching {
            val inst = Instant.parse(snap.lastUpdated.replace(' ', 'T'))
            hourFmt.format(inst) + " UTC"
        }.getOrDefault("—")
        binding.tvLastUpdated.text = getString(R.string.updated_template, updatedLabel)

        // Status banner
        renderStatusBanner(snap)

        // Chart – cache full history then render the current window slice
        fullHistory = snap.history
        renderChart(sliceHistory(snap.history, vm.chartDays.value))

        // Notifications list
        renderNotices(snap.activeNotices)
    }

    // ── Status banner ────────────────────────────

    private fun renderStatusBanner(snap: FarmSnapshot) {
        val bannerColor = when {
            snap.activeNotices.any { it.unavailabilityType == "Unplanned" } ->
                ContextCompat.getColor(requireContext(), R.color.status_red)
            snap.activeNotices.isNotEmpty() ->
                ContextCompat.getColor(requireContext(), R.color.accent_amber)
            snap.latestMW > 0 ->
                ContextCompat.getColor(requireContext(), R.color.status_green)
            else ->
                ContextCompat.getColor(requireContext(), R.color.status_grey)
        }
        binding.viewStatusDot.setBackgroundColor(bannerColor)
        binding.tvStatus.text = snap.statusLabel
        binding.tvStatus.setTextColor(bannerColor)
    }

    // ── Line chart ───────────────────────────────

    /**
     * Returns the subset of [history] whose timestamps fall within the last [days] days,
     * measured back from the most recent point in the list.
     */
    private fun sliceHistory(history: List<GenerationPoint>, days: Int): List<GenerationPoint> {
        if (history.isEmpty()) return history
        val cutoff = runCatching {
            Instant.parse(history.last().timeFrom.replace(' ', 'T'))
                .minusSeconds(days * 24L * 3600)
        }.getOrNull() ?: return history
        return history.filter { pt ->
            runCatching {
                !Instant.parse(pt.timeFrom.replace(' ', 'T')).isBefore(cutoff)
            }.getOrDefault(true)
        }
    }

    private fun setupChart() {
        chartMarker = ChartMarkerView(requireContext())

        binding.chart.apply {
            description.isEnabled = false
            legend.isEnabled      = false
            setTouchEnabled(true)
            isDragEnabled         = true
            setScaleEnabled(false)
            setDrawGridBackground(false)
            setBackgroundColor(Color.TRANSPARENT)
            setHighlightPerTapEnabled(true)
            setHighlightPerDragEnabled(true)
            setDrawMarkers(true)
            marker = chartMarker

            xAxis.apply {
                position         = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor        = "#80FFFFFF".toColorInt()
                textSize         = 9f
                granularity      = 1f
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor        = "#1AFFFFFF".toColorInt()
                textColor        = "#80FFFFFF".toColorInt()
                textSize         = 9f
                axisMinimum      = 0f
                axisMaximum      = INSTALLED_MW.toFloat()
            }
            axisRight.isEnabled = false
        }
    }

    private fun renderChart(history: List<GenerationPoint>) {
        if (history.isEmpty()) return

        // Update marker data source
        chartMarker.updateHistory(history)

        val entries = history.mapIndexed { idx, pt ->
            Entry(idx.toFloat(), pt.totalMW.toFloat())
        }

        // X-axis labels: show time for 1-day view, date+time for multi-day
        val isMultiDay = vm.chartDays.value > 1
        val labels = history.map { pt ->
            runCatching {
                val inst = Instant.parse(pt.timeFrom.replace(' ', 'T'))
                if (isMultiDay) dateFmt.format(inst) else hourFmt.format(inst)
            }.getOrDefault("")
        }

        val dataSet = LineDataSet(entries, "Generation MW").apply {
            color               = "#00D4FF".toColorInt()
            setDrawCircles(false)
            lineWidth           = 2f
            mode                = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillAlpha           = 40
            fillColor           = "#00D4FF".toColorInt()
            setDrawValues(false)
            isHighlightEnabled  = true
            highlightLineWidth  = 1f
            setDrawVerticalHighlightIndicator(true)
            setDrawHorizontalHighlightIndicator(false)
        }

        // Show one label every 4 points (≈ 2h for 30-min intervals)
        val labelStep = if (isMultiDay) 8 else 4
        binding.chart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val i = value.toInt()
                return if (i % labelStep == 0 && i < labels.size) labels[i] else ""
            }
        }

        binding.chart.data = LineData(dataSet)
        binding.chart.invalidate()
    }

    // ── Notices list ─────────────────────────────

    private fun renderNotices(notices: List<ActiveNotice>) {
        binding.noticesContainer.removeAllViews()
        if (notices.isEmpty()) {
            binding.tvNoticesHeader.visibility = View.GONE
            return
        }
        binding.tvNoticesHeader.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(requireContext())
        for (notice in notices.distinctBy { it.documentId }) {
            val row = inflater.inflate(R.layout.item_notice, binding.noticesContainer, false)
            val tvTitle  = row.findViewById<TextView>(R.id.tv_notice_title)
            val tvDesc   = row.findViewById<TextView>(R.id.tv_notice_desc)
            val tvPeriod = row.findViewById<TextView>(R.id.tv_notice_period)
            val dot      = row.findViewById<View>(R.id.dot_severity)

            tvTitle.text  = "${notice.unavailabilityType} — ${notice.reasonCode} (${notice.bmuId})"
            tvDesc.text   = notice.reasonDescription
            tvPeriod.text = "Until ${notice.timeTo.take(10)}"
            dot.setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (notice.unavailabilityType == "Unplanned") R.color.status_red
                    else R.color.accent_amber
                )
            )
            binding.noticesContainer.addView(row)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

