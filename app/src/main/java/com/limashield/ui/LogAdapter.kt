package com.limashield.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.limashield.databinding.ItemLogBinding
import com.limashield.log.EventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var items: List<EventLog.Entry> = emptyList()

    class VH(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root)

    fun submit(list: List<EventLog.Entry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.b.textTime.text = fmt.format(Date(e.ts))
        holder.b.textMsg.text = e.msg
        holder.b.textMsg.setTextColor(
            when (e.level) {
                EventLog.Level.ERROR -> Color.parseColor("#D32F2F")
                EventLog.Level.WARN -> Color.parseColor("#EF6C00")
                EventLog.Level.STATE -> Color.parseColor("#1565C0")
                EventLog.Level.INFO -> holder.b.textTime.currentTextColor
            }
        )
    }
}
