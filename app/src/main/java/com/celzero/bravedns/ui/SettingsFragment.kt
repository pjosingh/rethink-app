/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui

import android.app.Activity
import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ExcludedAppListAdapter
import com.celzero.bravedns.database.*
import com.celzero.bravedns.databinding.ActivitySettingsScreenBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.filesDownloaded
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.localDownloadComplete
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_OTHERS
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Constants.Companion.REFRESH_BLOCKLIST_URL
import com.celzero.bravedns.util.HttpRequestHelper.Companion.checkStatus
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ExcludedAppViewModel
import com.google.android.material.textfield.TextInputEditText
import dnsx.Dnsx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import settings.Settings
import java.io.File
import java.io.IOException


class SettingsFragment : Fragment(R.layout.activity_settings_screen) {
    private val b by viewBinding(ActivitySettingsScreenBinding::bind)

    private var timeStamp: Long = 0L

    //For exclude apps dialog
    private var excludeAppAdapter: ExcludedAppListAdapter? = null
    private val excludeAppViewModel: ExcludedAppViewModel by viewModel()

    private val refreshDatabase by inject<RefreshDatabase>()
    private lateinit var animation: Animation

    private lateinit var downloadManager: DownloadManager

    private val appInfoRepository by inject<AppInfoRepository>()
    private val proxyEndpointRepository by inject<ProxyEndpointRepository>()
    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val persistentState by inject<PersistentState>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    companion object {
        var enqueue: Long = 0
        var downloadInProgress = -1
        private const val FILE_LOG_TAG = Constants.SETTINGS
    }

    private fun initView() {

        animation = RotateAnimation(0.0f, 360.0f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
        animation.repeatCount = -1
        animation.duration = 1000

        b.settingsActivityAllowBypassProgress.visibility = View.GONE

        b.settingsActivityHttpProxyProgress.visibility = View.GONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            b.settingsActivityHttpProxyContainer.visibility = View.VISIBLE
            b.settingsActivityHttpProxySwitch.isChecked = persistentState.httpProxyEnabled
            if (b.settingsActivityHttpProxySwitch.isChecked) {
                b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_http_proxy_desc, persistentState.httpProxyHostAddress, persistentState.httpProxyPort.toString())
            }
        } else {
            b.settingsActivityHttpProxyContainer.visibility = View.GONE
        }
        b.settingsActivityAllowBypassSwitch.isChecked = persistentState.allowByPass
        timeStamp = persistentState.localBlockListDownloadTime

        if (persistentState.downloadSource == DOWNLOAD_SOURCE_OTHERS) {
            b.settingsActivityOnDeviceBlockRl.visibility = View.VISIBLE
            b.settingsHeadingDns.visibility = View.VISIBLE
        } else {
            b.settingsActivityOnDeviceBlockRl.visibility = View.GONE
            b.settingsHeadingDns.visibility = View.GONE
        }

        localDownloadComplete.observe(viewLifecycleOwner, {
            if (it == 1) {
                downloadInProgress = 1
                b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.VISIBLE
                b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
                b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.VISIBLE
                b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
                b.settingsActivityOnDeviceBlockSwitch.isChecked = true
                b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc3)
                b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(R.string.settings_local_blocklist_version, Utilities.convertLongToDate(timeStamp))
                localDownloadComplete.postValue(0)
            }
        })

        HomeScreenActivity.GlobalVariable.braveModeToggler.observe(viewLifecycleOwner, {
            if (HomeScreenActivity.GlobalVariable.braveMode == HomeScreenFragment.DNS_MODE) {
                //disableDNSRelatedUI()
            } else if (HomeScreenActivity.GlobalVariable.braveMode == HomeScreenFragment.FIREWALL_MODE) {
                disableDNSRelatedUI()
            } else if (HomeScreenActivity.GlobalVariable.braveMode == HomeScreenFragment.DNS_FIREWALL_MODE) {
                enableDNSFirewallUI()
            }
        })

        b.settingsActivityEnableLogsSwitch.isChecked = persistentState.logsEnabled
        b.settingsActivityAutoStartSwitch.isChecked = persistentState.prefAutoStartBootUp
        b.settingsActivityKillAppSwitch.isChecked = persistentState.killAppOnFirewall
        b.settingsActivityCheckUpdateSwitch.isChecked = persistentState.checkForAppUpdate

        b.settingsActivitySocks5Switch.isChecked = persistentState.socks5Enabled
        if (b.settingsActivitySocks5Switch.isChecked) {
            val sock5Proxy = proxyEndpointRepository.getConnectedProxy()
            if (sock5Proxy?.proxyAppName != getString(R.string.settings_app_list_default_app)) {
                val appName = appList[sock5Proxy?.proxyAppName]?.appName
                b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_desc, sock5Proxy!!.proxyIP, sock5Proxy.proxyPort.toString(), appName)
            } else {
                b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_desc, sock5Proxy.proxyIP, sock5Proxy.proxyPort.toString(), getString(R.string.settings_app_list_default_app))
            }
        }
        b.settingsActivitySocks5Progress.visibility = View.GONE

        //blockUnknownConnSwitch.isChecked = persistentState.getBlockUnknownConnections(requireContext())
        if (persistentState.localBlocklistEnabled) {
            b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.VISIBLE
            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.VISIBLE
            b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
            b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(R.string.settings_local_blocklist_version, Utilities.convertLongToDate(timeStamp))
            b.settingsActivityOnDeviceBlockSwitch.isChecked = true
        } else {
            b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
            b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
            b.settingsActivityOnDeviceBlockSwitch.isChecked = false
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blockList_desc1)
        }

        //For exclude apps
        excludeAppAdapter = ExcludedAppListAdapter(requireContext(), appInfoRepository, categoryInfoRepository)
        excludeAppViewModel.excludedAppList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(excludeAppAdapter!!::submitList))


        val appCount = appList.size
        appInfoRepository.getExcludedAppListCountLiveData().observe(viewLifecycleOwner, {
            b.settingsActivityExcludeAppsCountText.text = getString(R.string.ex_dialog_count, it.toString(), appCount.toString())
        })

    }

    /**
     * Enabled all the layouts and change the labels
     * for the heading.
     */
    private fun enableDNSFirewallUI() {
        b.settingsHeadingDns.text = getString(R.string.app_mode_dns)
        b.settingsActivityOnDeviceBlockRl.isEnabled = true
        b.settingsActivityOnDeviceBlockSwitch.isEnabled = true
        b.settingsActivityOnDeviceBlockRefreshBtn.isEnabled = true
        b.settingsActivityOnDeviceBlockConfigureBtn.isEnabled = true
    }

    /**
     * Disable all the layouts related with DNS
     */
    private fun disableDNSRelatedUI() {
        b.settingsHeadingDns.text  = getString(R.string.dns_mode_disabled)
        b.settingsActivityOnDeviceBlockRl.isEnabled = false
        b.settingsActivityOnDeviceBlockSwitch.isEnabled = false
        b.settingsActivityOnDeviceBlockRefreshBtn.isEnabled = false
        b.settingsActivityOnDeviceBlockConfigureBtn.isEnabled = false
    }

    /**
     * Disable all the layouts related to lockdown mode. like exclude apps and allow bypass
     */
    private fun disableForLockdownModeUI() {
        b.settingsActivityVpnHeadingText.text = getString(R.string.settings_vpn_heading_disabled)
        b.settingsActivityOnDeviceBlockRl.isEnabled = false
        b.settingsActivityExcludeAppsRl.isEnabled = false
        b.settingsActivityAllowBypassSwitch.isEnabled = false
        b.settingsActivityExcludeAppsImg.isEnabled = false
        b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
    }

    private fun enableForLockdownModeUI() {
        b.settingsActivityVpnHeadingText.text = getString(R.string.settings_vpn_heading)
        b.settingsActivityOnDeviceBlockRl.isEnabled = true
        b.settingsActivityExcludeAppsRl.isEnabled = true
        b.settingsActivityAllowBypassSwitch.isEnabled = true
        b.settingsActivityExcludeAppsImg.isEnabled = true
        b.settingsActivityVpnLockdownDesc.visibility = View.GONE
    }

    private fun detectLockDownMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val alwaysOn = android.provider.Settings.Secure.getString(requireContext().contentResolver, "always_on_vpn_app")
            val lockDown = android.provider.Settings.Secure.getInt(requireContext().contentResolver, "always_on_vpn_lockdown", 0)
            if (DEBUG) Log.d(LOG_TAG, "$FILE_LOG_TAG isLockDownEnabled - $lockDown , $alwaysOn")
            if (lockDown != 0 && context?.packageName == alwaysOn) {
                disableForLockdownModeUI()
            } else {
                enableForLockdownModeUI()
            }
        } else {
            enableForLockdownModeUI()
        }

    }

    private fun initClickListeners() {
        b.settingsActivityRefreshDataRl.setOnClickListener {
            refreshDatabase()
        }

        b.settingsActivityRefreshDataImg.setOnClickListener {
            refreshDatabase()
        }

        b.settingsActivityEnableLogsSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.logsEnabled = b
        }

        b.settingsActivityAutoStartSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.prefAutoStartBootUp = b
        }

        b.settingsActivityKillAppSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.killAppOnFirewall = b
        }


        b.settingsActivityCheckUpdateSwitch.setOnCheckedChangeListener{ _: CompoundButton, b: Boolean ->
            persistentState.checkForAppUpdate = b
        }

        b.settingsActivityAllowBypassSwitch.setOnCheckedChangeListener { _: CompoundButton, bool: Boolean ->
            persistentState.allowByPass = bool
            object : CountDownTimer(100, 500) {
                override fun onTick(millisUntilFinished: Long) {
                    b.settingsActivityAllowBypassSwitch.isEnabled = false
                    b.settingsActivityAllowBypassSwitch.visibility = View.INVISIBLE
                    b.settingsActivityAllowBypassProgress.visibility = View.VISIBLE
                }

                override fun onFinish() {
                    b.settingsActivityAllowBypassSwitch.isEnabled = true
                    b.settingsActivityAllowBypassProgress.visibility = View.GONE
                    b.settingsActivityAllowBypassSwitch.visibility = View.VISIBLE
                }
            }.start()

        }

        b.settingsActivityOnDeviceBlockSwitch.setOnCheckedChangeListener(null)
        b.settingsActivityOnDeviceBlockSwitch.setOnClickListener {
            val isSelected = b.settingsActivityOnDeviceBlockSwitch.isChecked
            b.settingsActivityOnDeviceBlockSwitch.isEnabled = false
            if (isSelected) {
                b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                if (!persistentState.blockListFilesDownloaded) {
                    showDownloadDialog()
                } else {
                    if (isSelected) {
                        setBraveDNSLocal()
                        val count = persistentState.numberOfLocalBlocklists
                        b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_in_use, count.toString())
                    }
                }
            } else {
                removeBraveDNSLocal()
                b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
                b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
                b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
                b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blockList_desc1)
            }
            Handler().postDelayed({ b.settingsActivityOnDeviceBlockSwitch.isEnabled = true }, 1000)
        }

        b.settingsActivitySocks5Switch.setOnCheckedChangeListener { compoundButton: CompoundButton, bool: Boolean ->
            persistentState.socks5Enabled = bool
            if (bool) {
                showDialogForSocks5Proxy()
            } else {
                b.settingsActivitySocks5Switch.visibility = View.GONE
                b.settingsActivitySocks5Progress.visibility = View.VISIBLE
                appMode?.setProxyMode(Settings.ProxyModeNone)
                b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_default_desc)
                b.settingsActivitySocks5Switch.visibility = View.VISIBLE
                b.settingsActivitySocks5Progress.visibility = View.GONE
            }
        }


        b.settingsActivityHttpProxySwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            showDialogForHTTPProxy(b)
        }

        b.settingsActivityExcludeAppsImg.setOnClickListener {
            b.settingsActivityExcludeAppsImg.isEnabled = false
            showExcludeAppDialog(requireContext(), excludeAppAdapter!!, excludeAppViewModel)
            Handler().postDelayed({ b.settingsActivityExcludeAppsImg.isEnabled = true }, 100)
        }

        b.settingsActivityExcludeAppsRl.setOnClickListener {
            b.settingsActivityExcludeAppsRl.isEnabled = false
            showExcludeAppDialog(requireContext(), excludeAppAdapter!!, excludeAppViewModel)
            Handler().postDelayed({
                b.settingsActivityExcludeAppsRl.isEnabled = true
            }, 100)
        }

        b.settingsAppFaqIcon.setOnClickListener {
            startWebViewIntent()
        }

        b.settingsActivityOnDeviceBlockConfigureBtn.setOnClickListener {
            val intent = Intent(requireContext(), DNSConfigureWebViewActivity::class.java)
            val stamp = persistentState.getLocalBlockListStamp()
            if (DEBUG) Log.d(LOG_TAG, "Stamp value in settings screen - $stamp")
            intent.putExtra(Constants.LOCATION_INTENT_EXTRA, DNSConfigureWebViewActivity.LOCAL)
            intent.putExtra(Constants.STAMP_INTENT_EXTRA, stamp)
            (requireContext() as Activity).startActivityForResult(intent, Activity.RESULT_OK)
        }

        b.settingsActivityOnDeviceBlockRefreshBtn.setOnClickListener {
            checkForDownload(true)
        }

    }

    override fun onResume() {
        super.onResume()
        detectLockDownMode()
        if (persistentState.localBlocklistEnabled && persistentState.blockListFilesDownloaded && persistentState.getLocalBlockListStamp().isNullOrEmpty()) {
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc5)
            b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
        } else if (downloadInProgress == 0) {
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc2)
            b.settingsActivityOnDeviceBlockSwitch.visibility = View.GONE
            b.settingsActivityOnDeviceBlockProgress.visibility = View.VISIBLE
        } else {
            b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
            val count = persistentState.numberOfLocalBlocklists
            if (count != 0) {
                b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_in_use, count.toString())
            }
        }
        val count = persistentState.numberOfLocalBlocklists
        if (count != 0 && persistentState.localBlocklistEnabled) {
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_in_use, count.toString())
        } else if (persistentState.localBlocklistEnabled) {
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc6)
        }
        if (!b.settingsActivityOnDeviceBlockSwitch.isChecked && downloadInProgress != 0) {
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blockList_desc1)
        }
    }

    private fun checkForDownload(isUserInitiated: Boolean): Boolean {
        if (timeStamp == 0L) {
            timeStamp = persistentState.localBlockListDownloadTime
        }
        val appVersionCode = persistentState.appVersion
        val url = "$REFRESH_BLOCKLIST_URL$timeStamp&${Constants.APPEND_VCODE}$appVersionCode"
        if (DEBUG) Log.d(LOG_TAG, "Check for local download, url - $url")
        run(url, isUserInitiated)
        return false
    }

    private fun run(url: String, isUserInitiated: Boolean) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LOG_TAG, "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
                activity?.runOnUiThread {
                    b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
                    b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
                    b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
                    b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                    b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
                    b.settingsActivityOnDeviceBlockSwitch.isChecked = false
                    b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc4)
                    downloadInProgress = -1
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val stringResponse = response.body!!.string()
                    //creating json object
                    val jsonObject = JSONObject(stringResponse)
                    val version = jsonObject.getInt(Constants.JSON_VERSION)
                    if (DEBUG) Log.d(LOG_TAG, "client onResponse for refresh blocklist files-  $version")
                    if (version == 1) {
                        val updateValue = jsonObject.getBoolean(Constants.JSON_UPDATE)
                        timeStamp = jsonObject.getLong(Constants.JSON_LATEST)
                        if (DEBUG) Log.d(LOG_TAG, "onResponse -  $updateValue")
                        if (updateValue) {
                            persistentState.localBlockListDownloadTime = timeStamp
                            activity?.runOnUiThread {
                                registerReceiverForDownloadManager()
                                handleDownloadFiles()
                            }
                        } else {
                            activity?.runOnUiThread {
                                if (isUserInitiated) {
                                    Utilities.showToastInMidLayout(activity as Context, getString(R.string.settings_local_blocklist_toast_default), Toast.LENGTH_SHORT)
                                } else {
                                    b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
                                    b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
                                    b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
                                    b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                                    b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
                                    b.settingsActivityOnDeviceBlockSwitch.isChecked = false
                                    b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc4)
                                    downloadInProgress = -1
                                    timeStamp = 0
                                    persistentState.localBlockListDownloadTime = 0
                                    Utilities.showToastInMidLayout(activity as Context, getString(R.string.settings_local_blocklist_desc4), Toast.LENGTH_SHORT)
                                }
                            }
                        }
                    }
                    response.body!!.close()
                    client.connectionPool.evictAll()
                } catch (e: java.lang.Exception) {
                    Log.w(LOG_TAG, "Exception while downloading: ${e.message}", e)
                    b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
                    b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
                    b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
                    b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                    b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
                    b.settingsActivityOnDeviceBlockSwitch.isChecked = false
                    downloadInProgress = -1
                    timeStamp = 0
                    persistentState.localBlockListDownloadTime = 0
                    b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc4)
                }
            }
        })
    }


    private fun showDialogForHTTPProxy(isEnabled: Boolean) {
        if (isEnabled) {
            var isValid = true
            var host: String = ""
            var port: Int = 0
            val dialog = Dialog(requireContext())
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setTitle(getString(R.string.settings_http_proxy_dialog_title))
            dialog.setContentView(R.layout.dialog_set_http_proxy)

            val lp = WindowManager.LayoutParams()
            lp.copyFrom(dialog.window!!.attributes)
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            dialog.show()
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            dialog.window!!.attributes = lp

            val applyURLBtn = dialog.findViewById(R.id.dialog_http_proxy_ok_btn) as AppCompatButton
            val cancelURLBtn = dialog.findViewById(R.id.dialog_http_proxy_cancel_btn) as AppCompatButton
            val hostAddressEditText = dialog.findViewById(R.id.dialog_http_proxy_edit_text) as TextInputEditText
            val portEditText: EditText = dialog.findViewById(R.id.dialog_http_proxy_edit_text_port) as TextInputEditText
            val errorTxt: TextView = dialog.findViewById(R.id.dialog_http_proxy_failure_text) as TextView


            val hostName = persistentState.httpProxyHostAddress
            val portAddr = persistentState.httpProxyPort
            if (!hostName.isNullOrEmpty()) {
                hostAddressEditText.setText(hostName, TextView.BufferType.EDITABLE)
            }
            if (portAddr != 0) {
                portEditText.setText(portAddr.toString(), TextView.BufferType.EDITABLE)
            } else {
                portEditText.setText(Constants.HTTP_PROXY_PORT, TextView.BufferType.EDITABLE)
            }
            applyURLBtn.setOnClickListener {
                host = hostAddressEditText.text.toString()
                var isHostValid = true
                try {
                    port = portEditText.text.toString().toInt()
                    if (port in 65535 downTo 1024) {
                        isValid = true
                    } else {
                        errorTxt.text = getString(R.string.settings_http_proxy_error_text1)
                        errorTxt.visibility = View.VISIBLE
                        isValid = false
                    }
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Error: ${e.message}", e)
                    errorTxt.text = getString(R.string.settings_http_proxy_error_text2)
                    errorTxt.visibility = View.VISIBLE
                    isValid = false
                }

                if (host.isEmpty() || host.isBlank()) {
                    isHostValid = false
                    errorTxt.text = getString(R.string.settings_http_proxy_error_text3)
                    errorTxt.visibility = View.VISIBLE
                }

                if (isValid && isHostValid) {
                    b.settingsActivityHttpProxyProgress.visibility = View.VISIBLE
                    b.settingsActivityHttpProxySwitch.visibility = View.GONE
                    errorTxt.visibility = View.INVISIBLE
                    persistentState.httpProxyHostAddress = host
                    persistentState.httpProxyPort = port
                    persistentState.httpProxyEnabled = true
                    dialog.dismiss()
                    Toast.makeText(requireContext(), getString(R.string.settings_http_proxy_toast_success), Toast.LENGTH_SHORT).show()
                    if (b.settingsActivityHttpProxySwitch.isChecked) {
                        b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_http_proxy_desc, host, port.toString())
                    }
                    b.settingsActivityHttpProxyProgress.visibility = View.GONE
                    b.settingsActivityHttpProxySwitch.visibility = View.VISIBLE
                }
            }

            cancelURLBtn.setOnClickListener {
                dialog.dismiss()
                if (DEBUG) Log.d(LOG_TAG, "HTTP IsSelected is false")
                persistentState.httpProxyEnabled = false
                b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_http_proxy_desc_default)
                b.settingsActivityHttpProxySwitch.isChecked = false
            }
        } else {
            if (DEBUG) Log.d(LOG_TAG, "HTTP IsSelected is false")
            persistentState.httpProxyEnabled = false
            b.settingsActivityHttpProxySwitch.isChecked = false
            b.settingsActivityHttpProxyDesc.text =  getString(R.string.settings_http_proxy_desc_default)
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Activity.RESULT_OK) {
            val stamp = data?.getStringExtra(Constants.STAMP_INTENT_EXTRA)
            Log.i(LOG_TAG, "onActivityResult - stamp from webview - $stamp")
            setBraveDNSLocal()
        }
    }

    private fun setBraveDNSLocal() {
        b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.VISIBLE
        b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.VISIBLE
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
        b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(R.string.settings_local_blocklist_version, Utilities.convertLongToDate(timeStamp))
        val path: String = requireContext().filesDir.canonicalPath
        if (appMode?.getBraveDNS() == null) {
            GlobalScope.launch(Dispatchers.IO) {
                if (DEBUG) Log.d(LOG_TAG, "Local brave dns set call from settings fragment")
                val braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE, path + Constants.FILE_RD_FILE, path + Constants.FILE_BASIC_CONFIG, path + Constants.FILE_TAG_NAME)
                appMode?.setBraveDNSMode(braveDNS)
                persistentState.localBlocklistEnabled = true
            }
        }
    }

    private fun removeBraveDNSLocal() {
        appMode?.setBraveDNSMode(null)
        persistentState.localBlocklistEnabled = false
    }


    private fun showDownloadDialog() {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.local_blocklist_download)
        //set message for alert dialog
        builder.setMessage(R.string.local_blocklist_download_desc)
        builder.setCancelable(false)
        //performing positive action
        builder.setPositiveButton(getString(R.string.settings_local_blocklist_dialog_positive)) { dialogInterface, which ->
            downloadInProgress = 0
            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc2)
            b.settingsActivityOnDeviceBlockSwitch.visibility = View.GONE
            b.settingsActivityOnDeviceBlockProgress.visibility = View.VISIBLE

            checkForDownload(false)
        }

        //performing negative action
        builder.setNegativeButton(getString(R.string.settings_local_blocklist_dialog_negative)) { dialogInterface, which ->
            b.settingsActivityOnDeviceBlockSwitch.isChecked = false
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.show()

    }

    private fun refreshDatabase() {
        b.settingsActivityRefreshDataImg.animation = animation
        b.settingsActivityRefreshDataImg.startAnimation(animation)
        object : CountDownTimer(5000, 500) {
            override fun onTick(millisUntilFinished: Long) {
                b.settingsActivityRefreshDesc.text = getString(R.string.settings_sync_app_details_desc)
            }

            override fun onFinish() {
                b.settingsActivityRefreshDataImg.clearAnimation()
                b.settingsActivityRefreshDesc.text = getString(R.string.settings_sync_app_details_desc_completed)
            }
        }.start()

        refreshDatabase.refreshAppInfoDatabase()
    }

    private fun registerReceiverForDownloadManager() {
        requireContext().registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun handleDownloadFiles() {
        downloadManager = requireContext().getSystemService(AppCompatActivity.DOWNLOAD_SERVICE) as DownloadManager
        if (timeStamp == 0L) {
            timeStamp = persistentState.localBlockListDownloadTime
        }
        val url = Constants.JSON_DOWNLOAD_BLOCKLIST_LINK + "/" + timeStamp
        downloadBlockListFiles(url, Constants.FILE_TAG_NAME, requireContext())
    }

    private fun downloadBlockListFiles(url: String, fileName: String, context: Context) {
        try {
            if (DEBUG) Log.d(LOG_TAG, "downloadBlockListFiles - url: $url")
            val uri: Uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)
            request.setTitle(getString(R.string.hs_download_blocklist_heading))
            request.setDescription(getString(R.string.hs_download_blocklist_desc, fileName))
            request.setDestinationInExternalFilesDir(context, Constants.DOWNLOAD_PATH, fileName)
            Log.d(LOG_TAG, "Path - ${context.filesDir.canonicalPath}${Constants.DOWNLOAD_PATH}${fileName}")
            enqueue = downloadManager.enqueue(request)
        } catch (e: java.lang.Exception) {
            Log.e(LOG_TAG, "Download unsuccessful - ${e.message}", e)
            downloadInProgress = -1
        }
    }


    private var onComplete: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context, intent: Intent) {
            try {
                val action = intent.action
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                    val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0)
                    val query = DownloadManager.Query()
                    query.setFilterById(enqueue)
                    val c: Cursor = downloadManager.query(query)
                    if (c.moveToFirst()) {
                        val status = checkStatus(c)
                        if (DEBUG) Log.d(LOG_TAG, "Download status: $status,$filesDownloaded, $downloadId")
                        if (status == Constants.DOWNLOAD_STATUS_SUCCESSFUL) {
                            filesDownloaded += 1
                            if (filesDownloaded == 1) {
                                val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_TAG_NAME)
                                val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_TAG_NAME)
                                from.copyTo(to, true)
                                if (timeStamp == 0L) {
                                    timeStamp = persistentState.localBlockListDownloadTime
                                }
                                val url = Constants.JSON_DOWNLOAD_BASIC_CONFIG_LINK + "/" + timeStamp
                                if (DEBUG) Log.d(LOG_TAG, "Check for local download, url - $url")
                                downloadBlockListFiles(url, Constants.FILE_BASIC_CONFIG, ctxt)
                            } else if (filesDownloaded == 2) {
                                val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_BASIC_CONFIG)
                                val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_BASIC_CONFIG)
                                from.copyTo(to, true)
                                if (timeStamp == 0L) {
                                    timeStamp = persistentState.localBlockListDownloadTime
                                }
                                val url = Constants.JSON_DOWNLOAD_BASIC_RANK_LINK + "/" + timeStamp
                                if (DEBUG) Log.d(LOG_TAG, "Check for local download, url - $url")
                                downloadBlockListFiles(url, Constants.FILE_RD_FILE, ctxt)
                            } else if (filesDownloaded == 3) {
                                val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_RD_FILE)
                                val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_RD_FILE)
                                from.copyTo(to, true)
                                if (timeStamp == 0L) {
                                    timeStamp = persistentState.localBlockListDownloadTime
                                }
                                val url = Constants.JSON_DOWNLOAD_BASIC_TRIE_LINK + "/" + timeStamp
                                if (DEBUG) Log.d(LOG_TAG, "Check for local download, url - $url")
                                downloadBlockListFiles(url, Constants.FILE_TD_FILE, ctxt)
                            } else if (filesDownloaded == 4) {
                                val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_TD_FILE)
                                val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_TD_FILE)
                                val downloadedFile = from.copyTo(to, true)
                                if (downloadedFile.exists()) {
                                    Utilities.deleteOldFiles(ctxt)
                                }
                                persistentState.blockListFilesDownloaded = true
                                persistentState.localBlocklistEnabled = true
                                localDownloadComplete.postValue(1)
                                downloadInProgress = 1
                                b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.VISIBLE
                                b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.VISIBLE
                                b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                                b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
                                b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
                                b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(R.string.settings_local_blocklist_version, Utilities.convertLongToDate(timeStamp))
                                b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc3)
                                if (DEBUG) Log.d(LOG_TAG, "Download status : Download completed: $status")
                                Toast.makeText(ctxt, getString(R.string.settings_local_blocklist_download_success), Toast.LENGTH_LONG).show()
                            } else {
                                b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.VISIBLE
                                b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.VISIBLE
                                b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.VISIBLE
                                b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                                b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
                                b.settingsActivityOnDeviceLastUpdatedTimeTxt.text = getString(R.string.settings_local_blocklist_version, Utilities.convertLongToDate(timeStamp))
                            }
                        } else {
                            if (DEBUG) Log.d(LOG_TAG, "Download failed: $enqueue, $action, $downloadId")
                            b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
                            b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
                            b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
                            b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                            b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
                            b.settingsActivityOnDeviceBlockSwitch.isChecked = false
                            b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc4)
                            downloadInProgress = -1
                            timeStamp = 0
                            downloadManager.remove(downloadId)
                            persistentState.localBlockListDownloadTime = 0L
                            persistentState.blockListFilesDownloaded = false
                            persistentState.localBlocklistEnabled = false
                        }
                    } else {
                        if (DEBUG) Log.d(LOG_TAG, "Download failed: $enqueue, $action")
                        b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
                        b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
                        b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
                        b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                        b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
                        b.settingsActivityOnDeviceBlockSwitch.isChecked = false
                        b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc4)
                        downloadInProgress = -1
                        filesDownloaded = 0
                        timeStamp = 0
                        persistentState.localBlockListDownloadTime = 0L
                        persistentState.blockListFilesDownloaded = false
                        persistentState.localBlocklistEnabled = false
                    }
                    c.close()
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Exception while downloading: ${e.message}", e)
                b.settingsActivityOnDeviceBlockDesc.text = getString(R.string.settings_local_blocklist_desc4)
                b.settingsActivityOnDeviceBlockConfigureBtn.visibility = View.GONE
                b.settingsActivityOnDeviceLastUpdatedTimeTxt.visibility = View.GONE
                b.settingsActivityOnDeviceBlockRefreshBtn.visibility = View.GONE
                b.settingsActivityOnDeviceBlockProgress.visibility = View.GONE
                b.settingsActivityOnDeviceBlockSwitch.visibility = View.VISIBLE
                b.settingsActivityOnDeviceBlockSwitch.isChecked = false
                downloadInProgress = -1
                timeStamp = 0
                filesDownloaded = 0
                persistentState.localBlockListDownloadTime = 0L
                persistentState.blockListFilesDownloaded = false
                persistentState.localBlocklistEnabled = false
            }
        }
    }

    private fun startWebViewIntent() {
        val intent = Intent(requireContext(), FaqWebViewActivity::class.java)
        startActivity(intent)
    }


    private fun showExcludeAppDialog(context: Context, recyclerAdapter: ExcludedAppListAdapter, excludeAppViewModel: ExcludedAppViewModel) {
        val excludeAppDialog = ExcludeAppDialog(context, get(), get(), persistentState, recyclerAdapter, excludeAppViewModel)
        //if we know that the particular variable not null any time ,we can assign !! (not null operator ),
        // then  it won't check for null, if it becomes null, it will throw exception
        excludeAppDialog.show()
        excludeAppDialog.setCanceledOnTouchOutside(false)
    }

    private fun showDialogForSocks5Proxy() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_set_proxy)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window!!.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window!!.attributes = lp

        val applyURLBtn = dialog.findViewById(R.id.dialog_proxy_apply_btn) as AppCompatButton
        val cancelURLBtn = dialog.findViewById(R.id.dialog_proxy_cancel_btn) as AppCompatButton
        val ipAddressEditText: EditText = dialog.findViewById(R.id.dialog_proxy_edit_ip)
        val portEditText: EditText = dialog.findViewById(R.id.dialog_proxy_edit_port)
        val appNameSpinner: Spinner = dialog.findViewById(R.id.dialog_proxy_spinner_appname)
        val errorTxt: TextView = dialog.findViewById(R.id.dialog_proxy_error_text) as TextView
        val userNameEditText: EditText = dialog.findViewById(R.id.dialog_proxy_edit_username)
        val passwordEditText: EditText = dialog.findViewById(R.id.dialog_proxy_edit_password)
        val udpBlockCheckBox: CheckBox = dialog.findViewById(R.id.dialog_proxy_udp_check)

        val sock5Proxy = proxyEndpointRepository.getConnectedProxy()

        udpBlockCheckBox.isChecked = persistentState.udpBlockedSettings

        val appNames: MutableList<String> = ArrayList()
        appNames.add(getString(R.string.settings_app_list_default_app))
        appNames.addAll(getAppName())
        val proxySpinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, appNames)
        appNameSpinner.adapter = proxySpinnerAdapter
        if (sock5Proxy != null && !sock5Proxy.proxyIP.isNullOrEmpty()) {
            ipAddressEditText.setText(sock5Proxy.proxyIP, TextView.BufferType.EDITABLE)
            portEditText.setText(sock5Proxy.proxyPort.toString(), TextView.BufferType.EDITABLE)
            userNameEditText.setText(sock5Proxy.userName.toString(), TextView.BufferType.EDITABLE)
            if (sock5Proxy.proxyAppName?.isNotEmpty()!! && sock5Proxy.proxyAppName != getString(R.string.settings_app_list_default_app)) {
                val packageName = sock5Proxy.proxyAppName
                val app = appList[packageName]
                var position = 0
                for ((i, item) in appNames.withIndex()) {
                    if (item == app?.appName) {
                        position = i
                    }
                }
                appNameSpinner.setSelection(position)
            }
        } else {
            ipAddressEditText.setText(Constants.SOCKS_DEFAULT_IP, TextView.BufferType.EDITABLE)
            portEditText.setText(Constants.SOCKS_DEFAULT_PORT, TextView.BufferType.EDITABLE)
        }

        applyURLBtn.setOnClickListener {
            var ip: String = ""
            var appName: String = ""
            var port: Int = 0
            var mode: String = ""
            var userName: String = ""
            var password: String = ""
            var isValid = true
            var isIPValid = true
            var isUDPBlock = false
            var appPackageName = ""
            mode = getString(R.string.cd_dns_proxy_mode_external)
            appName = appNames[appNameSpinner.selectedItemPosition]
            if (appName.isEmpty() || appName == getString(R.string.settings_app_list_default_app)) {
                appPackageName = appNames[0]
            } else {
                appPackageName = appInfoRepository.getPackageNameForAppName(appName)
            }
            ip = ipAddressEditText.text.toString()

            if (ip.isEmpty() || ip.isBlank()) {
                isIPValid = false
                errorTxt.text = getString(R.string.settings_http_proxy_error_text3)
                errorTxt.visibility = View.VISIBLE
            }


            try {
                port = portEditText.text.toString().toInt()
                if (Utilities.isIPLocal(ip)) {
                    if (port in 65535 downTo 1024) {
                        isValid = true
                    } else {
                        errorTxt.text = getString(R.string.settings_http_proxy_error_text1)
                        isValid = false
                    }
                } else {
                    isValid = true
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error: ${e.message}", e)
                errorTxt.text = getString(R.string.settings_http_proxy_error_text2)
                isValid = false
            }
            if (udpBlockCheckBox.isChecked) {
                isUDPBlock = true
            }

            userName = userNameEditText.text.toString()
            password = passwordEditText.text.toString()
            if (isValid && isIPValid) {
                //Do the Socks5 Proxy setting there
                persistentState.udpBlockedSettings = udpBlockCheckBox.isChecked
                insertProxyEndpointDB(mode, Constants.SOCKS, appPackageName, ip, port, userName, password, isUDPBlock)
                b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_desc, ip, port.toString(), appName)
                dialog.dismiss()
            }
        }

        cancelURLBtn.setOnClickListener {
            b.settingsActivitySocks5Switch.isChecked = false
            appMode?.setProxyMode(Settings.ProxyModeNone)
            b.settingsActivitySocks5Desc.text = getString(R.string.settings_socks_forwarding_default_desc)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun getAppName(): MutableList<String> {
        return appInfoRepository.getAppNameList()
    }

    private fun insertProxyEndpointDB(mode: String, name: String, appName: String, ip: String, port: Int, userName: String, password: String, isUDPBlock: Boolean) {
        var proxyName = name
        if (proxyName.isEmpty() || proxyName.isBlank()) {
            if (mode == getString(R.string.cd_dns_proxy_mode_internal)) {
                proxyName = appName
            } else proxyName = ip
        }
        val proxyEndpoint = ProxyEndpoint(-1, proxyName, 1, mode, appName, ip, port, userName, password, true, true, isUDPBlock, 0L, 0)
        proxyEndpointRepository.clearAllData()
        proxyEndpointRepository.insertAsync(proxyEndpoint)
        object : CountDownTimer(1000, 500) {
            override fun onTick(millisUntilFinished: Long) {
                b.settingsActivitySocks5Switch.isEnabled = false
                b.settingsActivitySocks5Switch.visibility = View.GONE
                b.settingsActivitySocks5Progress.visibility = View.VISIBLE
            }

            override fun onFinish() {
                appMode?.setProxyMode(Settings.ProxyModeSOCKS5)
                b.settingsActivitySocks5Switch.isEnabled = true
                b.settingsActivitySocks5Progress.visibility = View.GONE
                b.settingsActivitySocks5Switch.visibility = View.VISIBLE
            }
        }.start()

    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            downloadInProgress = -1
            requireContext().unregisterReceiver(onComplete)
        }catch (e: Exception){
            if(DEBUG) Log.i(LOG_TAG,"Unregister receiver exception for download manager: ${e.message}")
        }
    }

}
