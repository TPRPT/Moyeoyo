import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.moyeoyo.app.ui.group.MemberListFragment
import com.moyeoyo.app.ui.group.PlaceListFragment

class GroupTabAdapter(
    fragment: Fragment,
    private val groupId: String,
    private val groupName: String
) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PlaceListFragment().apply {
                arguments = Bundle().apply {
                    putString("groupId", groupId)
                    putString("groupName", groupName)
                }
            }
            else -> MemberListFragment().apply {
                arguments = Bundle().apply {
                    putString("groupId", groupId)
                    putString("groupName", groupName)
                }
            }
        }
    }
}
