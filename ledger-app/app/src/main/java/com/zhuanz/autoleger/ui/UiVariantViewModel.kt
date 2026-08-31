package com.zhuanz.autoleger.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI 方案状态：以 ViewModel + StateFlow 替代原全局 object，生命周期随 Activity。
 * 通过 [ androidx.lifecycle.viewmodel.compose.viewModel ] 在 Compose 层共享同一实例。
 */
class UiVariantViewModel : ViewModel() {

    private val PREFS = "settings"
    private val KEY = "ui_variant"

    data class UiVariantState(
        val previewing: Boolean = false,
        val previewVariant: UiVariant = UiVariant.A,
        val current: UiVariant = UiVariant.A,
    ) {
        /** 预览模式下的生效方案 */
        val effective: UiVariant get() = if (previewing) previewVariant else current
    }

    private val _uiState = MutableStateFlow(UiVariantState())
    val uiState: StateFlow<UiVariantState> = _uiState.asStateFlow()

    fun load(context: Context) {
        val i = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY + "_v2", UiVariant.F.ordinal)
        _uiState.update { it.copy(current = UiVariant.fromIndex(i)) }
    }

    fun apply(context: Context, variant: UiVariant) {
        _uiState.update { it.copy(previewing = false, current = variant) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY + "_v2", variant.ordinal).apply()
    }

    /** 进入/退出预览；进入时以当前方案为起点 */
    fun setPreview(on: Boolean) {
        _uiState.update {
            it.copy(
                previewing = on,
                previewVariant = if (on) it.current else it.previewVariant,
            )
        }
    }

    /** 预览模式的目标方案 */
    fun setPreviewVariant(variant: UiVariant) {
        _uiState.update { it.copy(previewVariant = variant) }
    }

    /** 预览中确认采用当前方案 */
    fun confirmPreview(context: Context) {
        _uiState.update { state ->
            state.copy(previewing = false)
        }
        apply(context, _uiState.value.previewVariant)
    }

    /** 预览中直接退出预览（不采用） */
    fun cancelPreview() {
        _uiState.update { it.copy(previewing = false) }
    }
}
