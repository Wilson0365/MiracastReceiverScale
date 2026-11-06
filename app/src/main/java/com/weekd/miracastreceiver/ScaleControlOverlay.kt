package com.weekd.miracastreceiver

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.weekd.miracastreceiver.databinding.OverlayScaleControlBinding

class ScaleControlOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val binding: OverlayScaleControlBinding

    var onModeSelected: ((ScaleMode) -> Unit)? = null

    private val buttonMap: Map<ScaleMode, TextView>

    init {
        binding = OverlayScaleControlBinding.inflate(LayoutInflater.from(context), this, true)

        buttonMap = mapOf(
            ScaleMode.ORIGINAL to binding.btnOriginal,
            ScaleMode.ASPECT_FILL to binding.btnAspectFill,
            ScaleMode.SCALE_125 to binding.btnScale125,
            ScaleMode.SCALE_150 to binding.btnScale150,
            ScaleMode.SCALE_175 to binding.btnScale175,
            ScaleMode.SCALE_200 to binding.btnScale200,
            ScaleMode.SCALE_250 to binding.btnScale250,
        )

        buttonMap.forEach { (mode, btn) ->
            btn.setOnClickListener { selectMode(mode) }
            btn.setOnFocusChangeListener { _, _ -> }
        }

        setCurrentMode(ScaleMode.ORIGINAL)
    }

    fun setCurrentMode(mode: ScaleMode) {
        buttonMap.forEach { (m, btn) ->
            btn.isSelected = (m == mode)
        }
    }

    private fun selectMode(mode: ScaleMode) {
        setCurrentMode(mode)
        onModeSelected?.invoke(mode)
    }

    /** Move D-pad focus to the button corresponding to the given mode. */
    fun focusMode(mode: ScaleMode) {
        buttonMap[mode]?.requestFocus()
    }
}
