/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.adapter

import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.ThrowingHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ExcludedAppListAdapter(
    private val context: Context,
    private val appInfoRepository: AppInfoRepository,
    private val categoryInfoRepository: CategoryInfoRepository
) : PagedListAdapter<AppInfo, ExcludedAppListAdapter.ExcludedAppInfoViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object :
            DiffUtil.ItemCallback<AppInfo>() {

            override fun areItemsTheSame(oldConnection: AppInfo, newConnection: AppInfo) = oldConnection.packageInfo == newConnection.packageInfo

            override fun areContentsTheSame(oldConnection: AppInfo, newConnection: AppInfo) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExcludedAppInfoViewHolder {
        val v: View = LayoutInflater.from(parent.context).inflate(
            R.layout.excluded_app_list_item,
            parent, false
        )

        return ExcludedAppInfoViewHolder(v)
    }

    override fun onBindViewHolder(holder: ExcludedAppInfoViewHolder, position: Int) {
        val appInfo: AppInfo? = getItem(position)
        holder.update(appInfo)
    }


    inner class ExcludedAppInfoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // Overall view
        private var rowView: View? = null

        private var parentView: RelativeLayout? = null

        // Contents of the condensed view
        private var appName: TextView
        private var appIcon: ImageView
        private var checkBox: AppCompatCheckBox


        init {
            rowView = itemView
            parentView = itemView.findViewById(R.id.excluded_app_list_container)
            appName = itemView.findViewById(R.id.excluded_app_list_apk_label_tv)
            appIcon = itemView.findViewById(R.id.excluded_app_list_apk_icon_iv)
            checkBox = itemView.findViewById(R.id.excluded_app_list_checkbox)
        }

        fun update(appInfo: AppInfo?) {
            if (appInfo != null) {
                appName.text = appInfo.appName
                checkBox.isChecked = appInfo.isExcluded
                try {
                    if(!appInfo.packageInfo.contains(Constants.APP_NON_APP) || appInfo.appName != Constants.UNKNOWN_APP){
                        val icon = context.packageManager.getApplicationIcon(appInfo.packageInfo)
                        Glide.with(context)
                            .load(icon)
                            .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .into(appIcon)
                    }else{
                        Glide.with(context)
                            .load(ContextCompat.getDrawable(context, R.drawable.default_app_icon))
                            .error(AppCompatResources.getDrawable(context, R.drawable.default_app_icon))
                            .into(appIcon)
                    }

                } catch (e: Exception) {
                    appIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.default_app_icon))
                    Log.i(LOG_TAG, "Application Icon not available for package: ${appInfo.packageInfo}" + e.message)
                }

                parentView?.setOnClickListener {
                    if (DEBUG) Log.d(LOG_TAG, "parentView- whitelist - ${appInfo.appName},${appInfo.isExcluded}")
                    appInfo.isExcluded = !appInfo.isExcluded
                    excludeAppsFromVPN(appInfo, appInfo.isExcluded)
                }

                checkBox.setOnCheckedChangeListener(null)
                checkBox.setOnClickListener {
                    if (DEBUG) Log.d(LOG_TAG, "CheckBox- whitelist - ${appInfo.appName},${appInfo.isExcluded}")
                    appInfo.isExcluded = !appInfo.isExcluded
                    excludeAppsFromVPN(appInfo, appInfo.isExcluded)
                }
            }
        }

        private fun excludeAppsFromVPN(appInfo: AppInfo, status: Boolean) {
            val appUIDList = appInfoRepository.getAppListForUID(appInfo.uid)
            var blockAllApps = false
            if (appUIDList.size > 1) {
                blockAllApps = showDialog(appUIDList, appInfo.appName, status)
            } else {
                blockAllApps = true
            }
            if (blockAllApps) {
                checkBox.isChecked = status
                GlobalScope.launch(Dispatchers.IO) {
                    appInfoRepository.updateExcludedList(appInfo.uid, status)
                    val count = appInfoRepository.getBlockedCountForCategory(appInfo.appCategory)
                    val excludedCount = appInfoRepository.getExcludedAppCountForCategory(appInfo.appCategory)
                    val whitelistCount = appInfoRepository.getBlockedCountForCategory(appInfo.appCategory)
                    categoryInfoRepository.updateBlockedCount(appInfo.appCategory, count)
                    categoryInfoRepository.updateExcludedCount(appInfo.appCategory, excludedCount)
                    categoryInfoRepository.updateWhitelistCount(appInfo.appCategory,whitelistCount)
                }
                if(DEBUG) Log.d(LOG_TAG,"Apps excluded - ${appInfo.appName}, $status")
            } else {
                checkBox.isChecked = !status
                appInfo.isExcluded = !status
                if(DEBUG) Log.d(LOG_TAG,"App not excluded - ${appInfo.appName}, $status")
            }
        }

        private fun showDialog(packageList: List<AppInfo>, appName: String, isInternet: Boolean): Boolean {
            //Change the handler logic into some other
            val handler: Handler = ThrowingHandler()
            var positiveTxt = ""
            val packageNameList: List<String> = packageList.map { it.appName }
            var proceedBlocking = false

            val builderSingle: AlertDialog.Builder = AlertDialog.Builder(context)

            builderSingle.setIcon(R.drawable.ic_exclude_app)
            if (isInternet) {
                builderSingle.setTitle(context.getString(R.string.exclude_app_desc, appName, packageList.size.toString()))
                positiveTxt = context.getString(R.string.exclude_app_dialog_positive, packageList.size.toString())
            } else {
                builderSingle.setTitle(context.getString(R.string.unexclude_app_desc, appName, packageList.size.toString()))
                positiveTxt = context.getString(R.string.unexclude_app_dialog_positive, packageList.size.toString())
            }
            val arrayAdapter = ArrayAdapter<String>(context,
                android.R.layout.simple_list_item_activated_1)
            arrayAdapter.addAll(packageNameList)
            builderSingle.setCancelable(false)

            builderSingle.setItems(packageNameList.toTypedArray(), null)

            builderSingle.setPositiveButton(positiveTxt) { _: DialogInterface, _: Int ->
                proceedBlocking = true
                handler.sendMessage(handler.obtainMessage())
            }.setNeutralButton(context.getString(R.string.ctbs_dialog_negative_btn)) { _: DialogInterface, _: Int ->
                handler.sendMessage(handler.obtainMessage())
                proceedBlocking = false
            }

            val alertDialog: AlertDialog = builderSingle.show()
            alertDialog.listView.setOnItemClickListener { _, _, _, _ -> }
            alertDialog.setCancelable(false)
            try {
                Looper.loop()
            } catch (e2: java.lang.RuntimeException) {
            }

            return proceedBlocking
        }

    }

}