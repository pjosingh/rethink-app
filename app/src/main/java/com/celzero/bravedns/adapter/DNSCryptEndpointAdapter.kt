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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DNSCryptEndpoint
import com.celzero.bravedns.database.DNSCryptEndpointRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.QueryTracker
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.UIUpdateInterface
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import settings.Settings


class DNSCryptEndpointAdapter(private val context: Context,
                              private val dnsCryptEndpointRepository:DNSCryptEndpointRepository,
                              private val persistentState: PersistentState,
                              private val queryTracker: QueryTracker,
                              var listener : UIUpdateInterface) : PagedListAdapter<DNSCryptEndpoint, DNSCryptEndpointAdapter.DNSCryptEndpointViewHolder>(DIFF_CALLBACK) {
    //private var serverList : MutableList<DNSCryptEndpoint> = ArrayList()

    companion object {
        private val DIFF_CALLBACK = object :
            DiffUtil.ItemCallback<DNSCryptEndpoint>() {

            override fun areItemsTheSame(oldConnection: DNSCryptEndpoint, newConnection: DNSCryptEndpoint) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSCryptEndpoint, newConnection: DNSCryptEndpoint) : Boolean{
                if(oldConnection.isSelected != newConnection.isSelected){
                    return false
                }
                return oldConnection == newConnection
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DNSCryptEndpointViewHolder {
        val v: View = LayoutInflater.from(parent.context).inflate(
            R.layout.dns_crypt_endpoint_list_item,
            parent, false
        )
        return DNSCryptEndpointViewHolder(v)
    }

    override fun onBindViewHolder(holder: DNSCryptEndpointViewHolder, position: Int) {
        val dnsCryptEndpoint: DNSCryptEndpoint? = getItem(position)
        holder.update(dnsCryptEndpoint)
    }

    inner class DNSCryptEndpointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // Overall view
        private var rowView: View? = null

        // Contents of the condensed view
        private var urlNameTxt : TextView
        private var urlExplanationTxt : TextView
        private var imageAction: CheckBox
        private var infoImg : ImageView


        init {
            rowView = itemView
            urlNameTxt = itemView.findViewById(R.id.dns_crypt_endpoint_list_url_name)
            urlExplanationTxt = itemView.findViewById(R.id.dns_crypt_endpoint_list_url_explanation)
            imageAction = itemView.findViewById(R.id.dns_crypt_endpoint_list_action_image)
            infoImg = itemView.findViewById(R.id.dns_crypt_endpoint_list_info_image)
        }

        fun update(dnsCryptEndpoint: DNSCryptEndpoint?) {
            if(DEBUG) Log.d(LOG_TAG,"dnsCryptEndpoint adapter -- ${dnsCryptEndpoint?.dnsCryptName}")
            urlNameTxt.text = dnsCryptEndpoint!!.dnsCryptName
            if (dnsCryptEndpoint.isSelected) {
                urlExplanationTxt.text = context.getString(R.string.dns_connected)
            } else {
                urlExplanationTxt.text = ""
            }

            if (dnsCryptEndpoint.isCustom && !dnsCryptEndpoint.isSelected) {
                infoImg.setImageDrawable(context.getDrawable(R.drawable.ic_fab_uninstall))
            } else {
                infoImg.setImageDrawable(context.getDrawable(R.drawable.ic_fab_appinfo))
            }
            imageAction.isChecked = dnsCryptEndpoint.isSelected
            rowView?.setOnClickListener {
                imageAction.isChecked = !imageAction.isChecked
                dnsCryptEndpoint.isSelected = imageAction.isChecked
                val state = updateDNSCryptDetails(dnsCryptEndpoint)
                if (!state) {
                    imageAction.isChecked = !state
                }
            }
            imageAction.setOnClickListener {
                dnsCryptEndpoint.isSelected = imageAction.isChecked
                val state = updateDNSCryptDetails(dnsCryptEndpoint)
                if(!state){
                    imageAction.isChecked = !state
                }
            }
            infoImg.setOnClickListener {
                showExplanationOnImageClick(dnsCryptEndpoint)
            }
        }

        private fun showExplanationOnImageClick(dnsCryptEndpoint: DNSCryptEndpoint) {
            if (dnsCryptEndpoint.isCustom && !dnsCryptEndpoint.isSelected)
                showDialogForDelete(dnsCryptEndpoint)
            else {
                if (dnsCryptEndpoint.dnsCryptExplanation.isNullOrEmpty()) {
                    showDialogExplanation(dnsCryptEndpoint.dnsCryptName, dnsCryptEndpoint.dnsCryptURL, "")
                } else {
                    showDialogExplanation(dnsCryptEndpoint.dnsCryptName, dnsCryptEndpoint.dnsCryptURL, dnsCryptEndpoint.dnsCryptExplanation!!)
                }
            }
        }

        private fun showDialogForDelete(dnsCryptEndpoint: DNSCryptEndpoint) {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(R.string.dns_crypt_custom_url_remove_dialog_title)
            //set message for alert dialog
            builder.setMessage(R.string.dns_crypt_url_remove_dialog_message)
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { dialogInterface, which ->
                GlobalScope.launch(Dispatchers.IO) {
                    if (dnsCryptEndpoint != null) {
                        dnsCryptEndpointRepository.deleteDNSCryptEndpoint(dnsCryptEndpoint.dnsCryptURL)
                    }
                }
                Toast.makeText(context, R.string.dns_crypt_url_remove_success, Toast.LENGTH_SHORT).show()
            }

            //performing negative action
            builder.setNegativeButton(context.getString(R.string.dns_delete_negative)) { dialogInterface, which ->
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showDialogExplanation(title: String,url :String, message: String) {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(title)
            //set message for alert dialog
            builder.setMessage(url + "\n\n"+message)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton(context.getString(R.string.dns_info_positive)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }

            builder.setNeutralButton(context.getString(R.string.dns_info_neutral)) { _: DialogInterface, _: Int ->
                val clipboard: ClipboardManager? = context.getSystemService()
                val clip = ClipData.newPlainText("URL", url)
                clipboard?.setPrimaryClip(clip)
                Utilities.showToastInMidLayout(context, context.getString(R.string.info_dialog_copy_toast_msg), Toast.LENGTH_SHORT)
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }


        private fun updateDNSCryptDetails(dnsCryptEndpoint : DNSCryptEndpoint) : Boolean{
            val list = dnsCryptEndpointRepository.getConnectedDNSCrypt()
            if(list.size == 1){
                if(!dnsCryptEndpoint.isSelected && list[0].dnsCryptURL == dnsCryptEndpoint.dnsCryptURL){
                    Toast.makeText(context,context.getString(R.string.dns_select_toast),Toast.LENGTH_SHORT).show()
                    return false
                }
            }
            dnsCryptEndpointRepository.updateAsync(dnsCryptEndpoint)

            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    notifyDataSetChanged()
                    persistentState.dnsType = 2
                    val connectedDNS = dnsCryptEndpointRepository.getConnectedCount()
                    persistentState.setConnectedDNS("DNSCrypt: $connectedDNS resolvers")
                    queryTracker.reinitializeQuantileEstimator()
                }
            }.start()

            persistentState.connectionModeChange = dnsCryptEndpoint.dnsCryptURL
            listener.updateUIFromAdapter(2)
            appMode?.setDNSMode(Settings.DNSModeCryptPort)

            return true
        }
    }
}