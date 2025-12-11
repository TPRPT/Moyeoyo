package com.moyeoyo.app.ui.groups.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.moyeoyo.app.R
import com.moyeoyo.app.ui.groups.PhotoTabFragment

class GroupPhotoAdapter(
    private val onPhotoClick: (PhotoTabFragment.GroupPhoto) -> Unit,
    private val onAddPhotoClick: () -> Unit,
    private val onDeletePhoto: (PhotoTabFragment.GroupPhoto) -> Unit,
    private val currentUserId: String
) : ListAdapter<PhotoTabFragment.GroupPhoto, RecyclerView.ViewHolder>(PhotoDiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return if (position == 0) VIEW_TYPE_ADD else VIEW_TYPE_PHOTO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_ADD -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_group_photo_add, parent, false)
                AddPhotoViewHolder(view, onAddPhotoClick)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_group_photo, parent, false)
                PhotoViewHolder(view, onPhotoClick, onDeletePhoto, currentUserId)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is AddPhotoViewHolder -> holder.bind()
            is PhotoViewHolder -> {
                if (position > 0) {
                    val photo = getItem(position - 1) // 첫 번째 항목이 추가 버튼이므로 -1
                    holder.bind(photo)
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return super.getItemCount() + 1 // 추가 버튼 포함
    }

    class AddPhotoViewHolder(
        itemView: View,
        private val onClick: () -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            itemView.setOnClickListener { onClick() }
        }
    }

    class PhotoViewHolder(
        itemView: View,
        private val onClick: (PhotoTabFragment.GroupPhoto) -> Unit,
        private val onDelete: (PhotoTabFragment.GroupPhoto) -> Unit,
        private val currentUserId: String
    ) : RecyclerView.ViewHolder(itemView) {
        private val imageView: ImageView = itemView.findViewById(R.id.imageView)
        private val btnDelete: android.widget.ImageButton = itemView.findViewById(R.id.btnDeletePhoto)

        fun bind(photo: PhotoTabFragment.GroupPhoto) {
            Glide.with(itemView.context)
                .load(photo.url)
                .centerCrop()
                .placeholder(R.drawable.ic_user_placeholder)
                .into(imageView)

            // 삭제 버튼은 본인이 업로드한 사진만 표시
            if (photo.uploadedBy == currentUserId) {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener {
                    onDelete(photo)
                }
            } else {
                btnDelete.visibility = View.GONE
            }

            // 이미지 클릭 시 크게 보기
            imageView.setOnClickListener {
                onClick(photo)
            }
        }
    }

    class PhotoDiffCallback : DiffUtil.ItemCallback<PhotoTabFragment.GroupPhoto>() {
        override fun areItemsTheSame(
            oldItem: PhotoTabFragment.GroupPhoto,
            newItem: PhotoTabFragment.GroupPhoto
        ): Boolean = oldItem.photoId == newItem.photoId

        override fun areContentsTheSame(
            oldItem: PhotoTabFragment.GroupPhoto,
            newItem: PhotoTabFragment.GroupPhoto
        ): Boolean = oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_ADD = 0
        private const val VIEW_TYPE_PHOTO = 1
    }
}

