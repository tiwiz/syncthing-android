package com.nutomic.syncthingandroid.views

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Device
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.util.ConfigRouter

/** Generates item views for device items; [DeviceRowState] decides what they show. */
class DevicesAdapter(private val context: Context) : ArrayAdapter<Device>(context, 0) {

    private var configRouter: ConfigRouter? = null
    private var restApi: RestApi? = null

    fun setRestApi(configRouter: ConfigRouter, restApi: RestApi?) {
        this.configRouter = configRouter
        this.restApi = restApi
    }

    private class ViewHolder(view: View) {
        val name: TextView = view.findViewById(R.id.name)
        val lastSeen: TextView = view.findViewById(R.id.lastSeen)
        val sharedFoldersTitle: TextView = view.findViewById(R.id.sharedFoldersTitle)
        val sharedFolders: TextView = view.findViewById(R.id.sharedFolders)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val status: TextView = view.findViewById(R.id.status)
        val bandwidthUpDown: TextView = view.findViewById(R.id.bandwidthUpDown)
        val rateInOutView: View = view.findViewById(R.id.rateInOutContainer)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view =
            convertView
                ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_device_list, parent, false)
                    .also { it.tag = ViewHolder(it) }
        val holder = view.tag as ViewHolder
        val device = checkNotNull(getItem(position))
        val api = restApi?.takeIf { it.isConfigLoaded }
        val row =
            DeviceRowState.of(
                context,
                device,
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(Constants.PREF_CACHE_DEVICE_LASTSEEN_PREFIX + device.deviceID, "")
                    .orEmpty(),
                checkNotNull(configRouter).getSharedFolders(device.deviceID),
                api?.let {
                    DeviceRowState.Status(
                        it.getRemoteDeviceStatus(device.deviceID),
                        it.getRemoteDeviceCompletion(device.deviceID),
                        it.getRemoteDeviceNeedBytes(device.deviceID),
                    )
                },
            )

        holder.name.text = row.name
        holder.lastSeen.text = row.lastSeen
        holder.sharedFoldersTitle.text = row.sharedFoldersTitle
        holder.sharedFolders.setTextOrHide(row.sharedFolders)
        holder.status.visibility = View.VISIBLE
        holder.status.text = row.status
        holder.status.setTextColor(ContextCompat.getColor(context, row.statusColor))
        holder.progressBar.visibility = if (row.progress != null) View.VISIBLE else View.GONE
        row.progress?.let { holder.progressBar.progress = it }
        holder.rateInOutView.visibility = if (row.bandwidth != null) View.VISIBLE else View.GONE
        row.bandwidth?.let { holder.bandwidthUpDown.text = it }
        return view
    }
}
