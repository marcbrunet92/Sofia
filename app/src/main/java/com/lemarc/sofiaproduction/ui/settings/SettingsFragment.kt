package com.lemarc.sofiaproduction.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.lemarc.sofiaproduction.MainActivity
import com.lemarc.sofiaproduction.R
import com.lemarc.sofiaproduction.data.AppSettings
import com.lemarc.sofiaproduction.data.SofiaRepository
import com.lemarc.sofiaproduction.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val repo = SofiaRepository()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Pre-fill with current BMU IDs
        binding.etBmuIds.setText(AppSettings.getBmuIds().joinToString(", "))

        binding.btnSave.setOnClickListener {
            val input = binding.etBmuIds.text.toString()
            val ids = input.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (ids.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_bmu_empty_error),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            AppSettings.setBmuIds(ids)
            (activity as? MainActivity)?.updateTestBanner()
            Toast.makeText(
                requireContext(),
                getString(R.string.settings_saved),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.chipTestBmu.setOnClickListener {
            binding.etBmuIds.setText("HEYM11")
        }

        binding.btnReset.setOnClickListener {
            AppSettings.resetBmuIds()
            binding.etBmuIds.setText(AppSettings.DEFAULT_BMU_IDS.joinToString(", "))
            (activity as? MainActivity)?.updateTestBanner()
            Toast.makeText(
                requireContext(),
                getString(R.string.settings_reset),
                Toast.LENGTH_SHORT
            ).show()
        }

        // ── API mode selection ────────────────────
        when (AppSettings.getApiMode()) {
            AppSettings.API_MODE_SOFIA -> binding.rbApiSofia.isChecked = true
            else -> binding.rbApiLegacy.isChecked = true
        }

        binding.rgApiMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rb_api_sofia) AppSettings.API_MODE_SOFIA
                       else AppSettings.API_MODE_LEGACY
            AppSettings.setApiMode(mode)
        }

        // Fetch the Sofia API data range and update the subtitle asynchronously
        viewLifecycleOwner.lifecycleScope.launch {
            repo.fetchDataRange()
                .onSuccess { info ->
                    if (info.totalDays > 0) {
                        binding.tvSofiaApiDesc.text = getString(
                            R.string.settings_api_sofia_desc,
                            info.totalDays
                        )
                    }
                }
                .onFailure {
                    binding.tvSofiaApiDesc.text = getString(R.string.settings_api_sofia_desc_unavailable)
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

