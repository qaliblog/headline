package com.google.mediapipe.examples.facelandmarker.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.mediapipe.examples.facelandmarker.R

class ModelPickerBottomSheet(
    private val models: List<String>,
    private val onModelSelected: (String) -> Unit,
    private val onFilePickerClicked: () -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.model_picker_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerView = view.findViewById<RecyclerView>(R.id.model_list)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = ModelAdapter(models) {
            onModelSelected(it)
            dismiss()
        }

        view.findViewById<View>(R.id.btn_file_picker).setOnClickListener {
            onFilePickerClicked()
            dismiss()
        }
    }

    private class ModelAdapter(
        private val models: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<ModelAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val model = models[position]
            holder.textView.text = model
            holder.itemView.setOnClickListener { onClick(model) }
        }

        override fun getItemCount() = models.size
    }
}
