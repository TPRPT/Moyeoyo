import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.moyeoyo.app.R

class NotificationAdapter(private val items: List<NotificationUi>) :
    RecyclerView.Adapter<NotificationAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title = view.findViewById<TextView>(R.id.tvTitle)
        val message = view.findViewById<TextView>(R.id.tvMessage)
        val time = view.findViewById<TextView>(R.id.tvTime)
        val card = view.findViewById<CardView>(R.id.cardNotification)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.message.text = item.message
        holder.time.text = item.time

        val context = holder.itemView.context
        when (item.type) {
            "confirmed" -> holder.card.setBackgroundResource(R.drawable.bg_card_confirmed)
            "d1" -> holder.card.setBackgroundResource(R.drawable.bg_card_d1)
            "changed" -> holder.card.setBackgroundResource(R.drawable.bg_card_changed)
            else -> holder.card.setBackgroundResource(R.drawable.bg_card_gray)
        }
    }
}
