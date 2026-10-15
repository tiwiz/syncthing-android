package com.nutomic.syncthingandroid.views

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.nutomic.syncthingandroid.R
import com.nutomic.syncthingandroid.model.Folder
import com.nutomic.syncthingandroid.service.Constants
import com.nutomic.syncthingandroid.service.RestApi
import com.nutomic.syncthingandroid.service.SyncthingService
import com.nutomic.syncthingandroid.util.FileOpener

/** Generates item views for folder items; [FolderRowState] decides what they show. */
class FoldersAdapter(private val context: Context) : ArrayAdapter<Folder>(context, 0) {

    private var restApi: RestApi? = null

    fun setRestApi(restApi: RestApi?) {
        this.restApi = restApi
    }

    private class ViewHolder(view: View) {
        val label: TextView = view.findViewById(R.id.label)
        val directory: TextView = view.findViewById(R.id.directory)
        val items: TextView = view.findViewById(R.id.items)
        val state: TextView = view.findViewById(R.id.state)
        val revert: TextView = view.findViewById(R.id.revert)
        val override: TextView = view.findViewById(R.id.override)
        val invalid: TextView = view.findViewById(R.id.invalid)
        val lastItemFinishedItem: TextView = view.findViewById(R.id.lastItemFinishedItem)
        val lastItemFinishedTime: TextView = view.findViewById(R.id.lastItemFinishedTime)
        val conflicts: TextView = view.findViewById(R.id.conflicts)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
        val openFolder: ImageView = view.findViewById(R.id.openFolder)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view =
            convertView
                ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_folder_list, parent, false)
                    .also { it.tag = ViewHolder(it) }
        val holder = view.tag as ViewHolder
        val folder = checkNotNull(getItem(position))
        val api = restApi?.takeIf { it.isConfigLoaded }
        val row = FolderRowState.of(context, folder, api?.getFolderStatus(folder.id))

        holder.label.text = row.label
        holder.directory.text = row.directory
        holder.override.setOnClickListener { onClickOverride(folder) }
        holder.revert.setOnClickListener { onClickRevert(folder) }
        holder.openFolder.setOnClickListener {
            FileOpener.openFolder(context, checkNotNull(folder.path))
        }
        holder.openFolder.setImageResource(
            when (folder.type) {
                Constants.FOLDER_TYPE_RECEIVE_ENCRYPTED -> R.drawable.outline_lock_24
                Constants.FOLDER_TYPE_RECEIVE_ONLY -> R.drawable.baseline_download_24
                Constants.FOLDER_TYPE_SEND_ONLY -> R.drawable.baseline_upload_24
                else -> R.drawable.baseline_folder_24
            }
        )

        holder.override.visibility = if (row.showOverride) View.VISIBLE else View.GONE
        holder.revert.text = context.getString(row.revertLabel)
        holder.revert.visibility = if (row.showRevert) View.VISIBLE else View.GONE
        holder.progressBar.visibility = if (row.progress != null) View.VISIBLE else View.GONE
        row.progress?.let { holder.progressBar.progress = it }
        holder.state.visibility = if (row.state != null) View.VISIBLE else View.GONE
        row.state?.let {
            holder.state.text = it
            holder.state.setTextColor(ContextCompat.getColor(context, row.stateColor))
        }
        holder.conflicts.setTextOrHide(row.conflicts)
        holder.lastItemFinishedItem.setTextOrHide(row.lastItemFinished)
        holder.lastItemFinishedTime.setTextOrHide(row.lastItemFinishedTime)
        holder.items.setTextOrHide(row.items)
        holder.invalid.setTextOrHide(row.invalid)
        return view
    }

    private fun onClickOverride(folder: Folder) =
        confirmAndSend(
            R.string.override_changes,
            R.string.override_changes_question,
            SyncthingService.ACTION_OVERRIDE_CHANGES,
            folder,
        )

    private fun onClickRevert(folder: Folder) =
        confirmAndSend(
            R.string.revert_local_changes,
            R.string.revert_local_changes_question,
            SyncthingService.ACTION_REVERT_LOCAL_CHANGES,
            folder,
        )

    /** Asks first, then sends [action] for [folder] through our service to the REST API. */
    private fun confirmAndSend(title: Int, message: Int, action: String, folder: Folder) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                context.startService(
                    Intent(context, SyncthingService::class.java)
                        .putExtra(SyncthingService.EXTRA_FOLDER_ID, folder.id)
                        .setAction(action)
                )
            }
            .setNegativeButton(android.R.string.no) { _, _ -> }
            .show()
    }
}

/** Shows [text], or hides the view without any. */
internal fun TextView.setTextOrHide(text: String?) {
    if (text.isNullOrEmpty()) {
        visibility = View.GONE
    } else {
        this.text = text
        visibility = View.VISIBLE
    }
}
