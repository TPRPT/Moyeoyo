package com.example.moyeoyo.ui.map.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.moyeoyo.data.model.PlaceSuggestion
import com.example.moyeoyo.databinding.ViewSearchBarBinding

/**
 * 주소 검색 UI용 커스텀 뷰 (컴파운드 뷰)
 * - 내부 레이아웃: view_search_bar.xml
 * - 외부에서 콜백만 연결하면 동작 (onQueryChange / onSuggestionClick)
 * - 선택 라벨 반영: setSelectedLabel()
 */
class SearchBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ViewBinding: 2-인자 inflate(현재 환경에서 사용 가능한 시그니처)
    private val binding: ViewSearchBarBinding =
        ViewSearchBarBinding.inflate(LayoutInflater.from(context), this)

    // 추천 리스트 어댑터 (클릭 콜백 외부 전달)
    private val suggestionAdapter = SuggestionAdapter { clicked ->
        onSuggestionClick?.invoke(clicked)
    }

    /** 검색어 변경 콜백 (외부에서 주입) */
    var onQueryChange: ((String) -> Unit)? = null

    /** 추천 항목 클릭 콜백 (외부에서 주입) */
    var onSuggestionClick: ((PlaceSuggestion) -> Unit)? = null

    init {
        // 검색어 변경 → 콜백 호출
        binding.edtSearch.addTextChangedListener {
            val q = it?.toString().orEmpty()
            onQueryChange?.invoke(q)
        }

        // 추천 목록 RecyclerView 세팅
        binding.rvSuggestions.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = suggestionAdapter
            visibility = View.GONE // 초기에는 숨김
        }
    }

    /** ViewModel에서 내려주는 자동완성/추천 리스트 반영 */
    fun submitSuggestions(list: List<PlaceSuggestion>) {
        suggestionAdapter.submitList(list)
        binding.rvSuggestions.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 선택된 후보 라벨 텍스트 반영 */
    fun setSelectedLabel(label: String?) {
        binding.chipSelected.text = label?.let { "선택한 위치: $it" } ?: "선택한 위치 없음"
    }

    /** (옵션) 외부에서 검색어를 강제로 세팅해야 할 때 사용 */
    fun setQueryText(text: String) {
        val cur = binding.edtSearch.text?.toString() ?: ""
        if (cur != text) {
            binding.edtSearch.setText(text)
            binding.edtSearch.setSelection(binding.edtSearch.text?.length ?: 0)
        }
    }

    /** (옵션) 추천 목록 감추기 */
    fun clearSuggestions() {
        submitSuggestions(emptyList())
    }
}