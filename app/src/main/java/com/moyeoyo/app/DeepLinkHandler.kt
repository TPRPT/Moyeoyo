import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import com.moyeoyo.app.R

class DeepLinkHandler(private val activity: AppCompatActivity) {

    fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val fromWidget = intent.getBooleanExtra("from_widget", false)
        if (!fromWidget) return

        val groupId = intent.getStringExtra("widget_group_id") ?: return
        val groupName = intent.getStringExtra("widget_group_name") ?: "모임"

        val navController = activity.findNavController(R.id.nav_host_fragment)

        val args = Bundle().apply {
            putString("groupId", groupId)
            putString("groupName", groupName)
        }

        navController.navigate(R.id.groupDetailFragment, args)
    }
}
