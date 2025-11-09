package com.moyeoyo.app.ui.group

import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.moyeoyo.app.ui.group.MemberListFragment



class GroupTabAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 2 // 탭 2개 (장소, 멤버)

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> PlaceListFragment()  // 장소 탭
            else -> MemberListFragment() // 멤버 탭
        }
    }
}
