package com.github.jayteealao.playster.ui.editorial

import android.content.Context
import com.github.jayteealao.playster.SettingsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live re-theme bridge. Turns the two persistence stores — the synchronous
 * palette gate ([EditorialThemeGate]) and the async reading-preferences DataStore
 * ([SettingsManager]) — into observable [StateFlow]s the activity collects into
 * [EditorialTheme]. Because the theme re-derives its tokens on any axis change, a
 * setter here re-themes the whole running tree the instant a value is picked; no
 * restart, no per-screen prop-drill.
 *
 * A setter persists *first*, then updates the in-memory flow, so a live switch and
 * cold-start durability are one code path:
 *  - the palette persists synchronously through [EditorialThemeGate.writePalette]
 *    (`commit()`), so a force-stop racing the switch can never lose the choice and
 *    the pre-first-frame window paints the saved paper — the flash-free guarantee.
 *    MainActivity's palette collector re-syncs the OS splash theme on every change
 *    (write-time, so the very next cold start splashes in the new paper); the
 *    `onCreate` sync remains as self-heal for writes made while no activity runs.
 *  - face / size / line-height / default-speed persist through the DataStore.
 *
 * The DataStore axes are also *collected back* so an external write (the debug
 * pref receiver, or a future second surface) re-themes the running app too; the
 * palette is seeded synchronously so the initial value matches the cold-start
 * window exactly.
 */
@Singleton
class ReadingPreferencesStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val settingsManager: SettingsManager,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val _palette = MutableStateFlow(EditorialThemeGate.savedPalette(context))
        val palette: StateFlow<PaperPalette> = _palette.asStateFlow()

        private val _face = MutableStateFlow(EditorialFaces.Source)
        val face: StateFlow<EditorialFace> = _face.asStateFlow()

        private val _sizeStep = MutableStateFlow(SizeStep.M)
        val sizeStep: StateFlow<SizeStep> = _sizeStep.asStateFlow()

        private val _lineHeightStep = MutableStateFlow(LineHeightStep.COMFORTABLE)
        val lineHeightStep: StateFlow<LineHeightStep> = _lineHeightStep.asStateFlow()

        private val _defaultSpeed = MutableStateFlow(DEFAULT_SPEED)
        val defaultSpeed: StateFlow<Float> = _defaultSpeed.asStateFlow()

        init {
            scope.launch { settingsManager.editorialFace.collect { _face.value = EditorialFaces.fromKey(it) } }
            scope.launch { settingsManager.editorialSizeStep.collect { _sizeStep.value = SizeStep.fromKey(it) } }
            scope.launch {
                settingsManager.editorialLineHeightStep.collect { _lineHeightStep.value = LineHeightStep.fromKey(it) }
            }
            scope.launch { settingsManager.defaultSpeed.collect { _defaultSpeed.value = it } }
        }

        /** Pick a paper. Persists synchronously (force-stop safe), then re-themes live. */
        fun selectPalette(paletteKey: String) {
            val palette = EditorialPalettes.fromKey(paletteKey)
            EditorialThemeGate.writePalette(context, palette.key)
            _palette.value = palette
        }

        fun selectFace(faceKey: String) {
            _face.value = EditorialFaces.fromKey(faceKey)
            scope.launch { settingsManager.setEditorialFace(faceKey) }
        }

        fun selectSizeStep(sizeStepKey: String) {
            _sizeStep.value = SizeStep.fromKey(sizeStepKey)
            scope.launch { settingsManager.setEditorialSizeStep(sizeStepKey) }
        }

        fun selectLineHeightStep(lineHeightStepKey: String) {
            _lineHeightStep.value = LineHeightStep.fromKey(lineHeightStepKey)
            scope.launch { settingsManager.setEditorialLineHeightStep(lineHeightStepKey) }
        }

        fun setDefaultSpeed(rate: Float) {
            _defaultSpeed.value = rate
            scope.launch { settingsManager.setDefaultSpeed(rate) }
        }

        private companion object {
            const val DEFAULT_SPEED = 1.0f
        }
    }
