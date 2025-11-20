package com.moyeoyo.app.ui.groups

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class GroupDetailPagerAdapter(
    private val context: Context,
    private val views: List<Int>,
    private val onBind: (view: View, position: Int) -> Unit
) : RecyclerView.Adapter<GroupDetailPagerAdapter.ViewHolder>() {

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(viewType, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = views.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        onBind(holder.view, position)
    }

    override fun getItemViewType(position: Int): Int = views[position]
}
