package com.lemarc.sofiaproduction

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.lemarc.sofiaproduction.data.AppSettings
import com.lemarc.sofiaproduction.databinding.ActivityMainBinding
import com.lemarc.sofiaproduction.ui.dashboard.DashboardFragment
import com.lemarc.sofiaproduction.ui.settings.SettingsFragment
import com.lemarc.sofiaproduction.widget.SofiaWidgetProvider

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        supportFragmentManager.addOnBackStackChangedListener {
            val canGoBack = supportFragmentManager.backStackEntryCount > 0
            supportActionBar?.setDisplayHomeAsUpEnabled(canGoBack)
            if (!canGoBack) {
                supportActionBar?.title = getString(R.string.app_name)
            }
        }

        if (savedInstanceState == null) {
            showDashboard()
        }

        SofiaWidgetProvider.forceRefresh(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        updateTestBanner()
    }

    override fun onSupportNavigateUp(): Boolean {
        supportFragmentManager.popBackStack()
        return true
    }

    fun updateTestBanner() {
        binding.tvTestBanner.visibility =
            if (AppSettings.isTestMode()) View.VISIBLE else View.GONE
    }

    private fun showDashboard() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, DashboardFragment())
            .commit()
    }

    private fun showSettings() {
        supportActionBar?.title = getString(R.string.settings_title)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SettingsFragment())
            .addToBackStack(null)
            .commit()
    }
}