package com.github.jayteealao.playster.screens.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jayteealao.playster.SettingsManager
import com.github.jayteealao.playster.data.auth.FirebaseAuthBridge
import com.github.jayteealao.playster.data.firestore.FirestoreRepository
import com.github.jayteealao.playster.data.firestore.HighlightDoc
import com.github.jayteealao.playster.data.firestore.HighlightsRepository
import com.github.jayteealao.playster.data.firestore.PlaylistDoc
import com.github.jayteealao.playster.data.firestore.ProgressDoc
import com.github.jayteealao.playster.data.firestore.ProgressRepository
import com.github.jayteealao.playster.data.firestore.VideoWithContext
import com.github.jayteealao.playster.data.search.RecentSearchesRepository
import com.github.jayteealao.playster.ui.editorial.EditorialFace
import com.github.jayteealao.playster.ui.editorial.EditorialFaces
import com.github.jayteealao.playster.ui.editorial.EditorialPalettes
import com.github.jayteealao.playster.ui.editorial.LineHeightStep
import com.github.jayteealao.playster.ui.editorial.PaperPalette
import com.github.jayteealao.playster.ui.editorial.ReadingPreferencesStore
import com.github.jayteealao.playster.ui.editorial.SizeStep
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Assembles the Settings screen from the live reading preferences
 * ([ReadingPreferencesStore]), the signed-in account, and the progress/highlight
 * docs the reading loop writes — through the pure [SettingsStatsAssembler]. Its
 * intents are the smallest possible: axis selections and default-speed write
 * straight through the store (which persists + re-themes live), and sign-out is a
 * single [FirebaseAuthBridge.signOut] — the graph's session gate does the routing.
 */
@HiltViewModel
@Suppress("LongParameterList") // Each source/store is an injected collaborator; none bundleable without a wrapper.
class SettingsViewModel
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val store: ReadingPreferencesStore,
        private val settingsManager: SettingsManager,
        private val authBridge: FirebaseAuthBridge,
        private val firestoreRepository: FirestoreRepository,
        private val progressRepository: ProgressRepository,
        private val highlightsRepository: HighlightsRepository,
        private val recentSearchesRepository: RecentSearchesRepository,
    ) : ViewModel() {
        // A plain systemDefaultZone clock rather than a Hilt binding (this slice
        // adds no AppModule provider, matching Home/Playlist); the stat
        // derivations' determinism is proven against a fixed clock at the pure
        // SettingsStatsAssembler layer (AC3).
        private val clock: Clock = Clock.systemDefaultZone()
        private val version = versionString(context)
        private val licenses = loadLicenses(context)

        private data class Prefs(
            val palette: PaperPalette,
            val face: EditorialFace,
            val size: SizeStep,
            val lineHeight: LineHeightStep,
            val defaultSpeed: Float,
        )

        private data class Corpus(
            val accountName: String,
            val videoProgress: List<ProgressDoc>,
            val highlights: List<HighlightDoc>,
            val playlists: List<PlaylistDoc>,
            val videos: List<VideoWithContext>,
        )

        private val prefsFlow =
            combine(
                store.palette,
                store.face,
                store.sizeStep,
                store.lineHeightStep,
                store.defaultSpeed,
            ) { palette, face, size, lineHeight, speed -> Prefs(palette, face, size, lineHeight, speed) }

        private val corpusFlow =
            combine(
                settingsManager.accountName,
                progressRepository.videoProgressFlow(),
                highlightsRepository.allHighlightsFlow(),
                firestoreRepository.playlistsFlow(),
                firestoreRepository.allVideosWithContextFlow(),
            ) { name, progress, highlights, playlists, videos -> Corpus(name, progress, highlights, playlists, videos) }

        val uiState: StateFlow<SettingsUiState> =
            combine(prefsFlow, corpusFlow) { prefs, corpus -> assemble(prefs, corpus) }
                .catch { emit(SettingsUiState.Empty) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    initialValue = SettingsUiState.Empty,
                )

        private fun assemble(
            prefs: Prefs,
            corpus: Corpus,
        ): SettingsUiState {
            val stats =
                SettingsStatsAssembler.assemble(
                    videoProgress = corpus.videoProgress,
                    highlights = corpus.highlights,
                    defaultSpeed = prefs.defaultSpeed,
                    clock = clock,
                )
            val deck =
                SettingsStatsAssembler.shelfDeck(
                    playlistCount = corpus.playlists.size,
                    transcriptCount = corpus.videos.size,
                    highlightsThisWeek = stats.highlightsThisWeek,
                )
            return SettingsUiState(
                profile =
                    SettingsUiState.Profile(
                        sinceLabel = sinceLabel(),
                        name = displayName(corpus.accountName),
                        deck = deck,
                    ),
                stats =
                    listOf(
                        SettingsUiState.Stat(SettingsStatsAssembler.streakLabel(stats.streak), "day streak"),
                        SettingsUiState.Stat(SettingsStatsAssembler.hoursLabel(stats.hoursThisWeek), "this week"),
                        SettingsUiState.Stat(SettingsStatsAssembler.speedLabel(stats.avgSpeed), "avg speed"),
                    ),
                readingAxes = readingAxes(prefs),
                defaultSpeed = speedRow(prefs.defaultSpeed),
                version = version,
                licenses = licenses,
            )
        }

        private fun readingAxes(prefs: Prefs): List<SettingsUiState.AxisRow> =
            listOf(
                axisRow(
                    SettingsAxis.FACE,
                    "Type face",
                    prefs.face.key,
                    EditorialFaces.All.map { it.key to it.displayName },
                ),
                axisRow(SettingsAxis.SIZE, "Type size", prefs.size.key, SizeStep.entries.map { it.key to it.label }),
                axisRow(
                    SettingsAxis.LINE_HEIGHT,
                    "Line height",
                    prefs.lineHeight.key,
                    LineHeightStep.entries.map { it.key to it.label },
                ),
                axisRow(
                    SettingsAxis.PAPER,
                    "Paper",
                    prefs.palette.key,
                    EditorialPalettes.All.map { it.key to it.displayName },
                ),
            )

        private fun axisRow(
            axis: SettingsAxis,
            title: String,
            selectedKey: String,
            options: List<Pair<String, String>>,
        ): SettingsUiState.AxisRow {
            val current = options.firstOrNull { it.first == selectedKey }?.second ?: options.first().second
            return SettingsUiState.AxisRow(
                axis = axis,
                title = title,
                currentLabel = current,
                options = options.map { (key, label) -> SettingsUiState.AxisOption(key, label, key == selectedKey) },
            )
        }

        private fun speedRow(current: Float): SettingsUiState.SpeedRow =
            SettingsUiState.SpeedRow(
                currentLabel = compactSpeed(current),
                options = SPEEDS.map { SettingsUiState.SpeedOption(it, compactSpeed(it), it == current) },
            )

        private fun displayName(accountName: String): String =
            authBridge.currentDisplayName?.takeIf { it.isNotBlank() }
                ?: accountName.takeIf { it.isNotBlank() }
                ?: "Reader"

        private fun sinceLabel(): String {
            val millis = authBridge.accountCreatedAtMillis ?: return "Subscriber"
            val month =
                Instant.ofEpochMilli(millis).atZone(clock.zone).format(SINCE_FORMAT)
            return "Subscriber since $month"
        }

        // Intents — each is the smallest possible move; the store persists + re-themes.
        fun onSelectAxis(
            axis: SettingsAxis,
            key: String,
        ) = when (axis) {
            SettingsAxis.FACE -> store.selectFace(key)
            SettingsAxis.SIZE -> store.selectSizeStep(key)
            SettingsAxis.LINE_HEIGHT -> store.selectLineHeightStep(key)
            SettingsAxis.PAPER -> store.selectPalette(key)
        }

        fun onSetDefaultSpeed(rate: Float) = store.setDefaultSpeed(rate)

        /**
         * Sign out — the graph's `LaunchedEffect(loggedIn)` redirects to Auth
         * over a cleared stack once [FirebaseAuthBridge.signOut] flips the
         * auth state. PRV-2: recent searches are local behavioral data with
         * no server-side owner scoping, so they're wiped here — *before* the
         * auth flip — so the clear is guaranteed to run before nav can tear
         * this ViewModel down; a DataStore failure is caught and logged
         * rather than blocking the sign-out itself.
         *
         * The Firestore offline cache (notes/highlights/progress) is *not*
         * cleared here: `FirebaseFirestore` is a single app-wide `@Singleton`
         * (`AppModule.provideFirebaseFirestore`) shared by every repository.
         * Its documented `clearPersistence()` contract requires `terminate()`
         * first (verified against the installed firebase-firestore 26.4.1
         * sources) — but `terminate()` permanently retires that one cached
         * instance for the rest of the process; every repository still holds
         * the same now-dead reference, so a later sign-*in* in the same
         * session would break every Firestore read/write app-wide until the
         * process restarts. Safely recovering from that needs the Firestore
         * singleton to become re-creatable (e.g. an app-wide DI change to a
         * `Provider<FirebaseFirestore>` the repositories re-resolve after
         * sign-in) — out of scope for this minimal fix.
         */
        fun onSignOut() {
            viewModelScope.launch {
                runCatching { recentSearchesRepository.clear() }
                    .onFailure { e -> Log.w(TAG, "Failed to clear recent searches on sign-out", e) }
                authBridge.signOut()
            }
        }

        private companion object {
            const val TAG = "playster.settings"
            const val STOP_TIMEOUT_MS = 5_000L
            val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
            val SINCE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

            /** "1×" / "1.25×" — trailing-zero-free speed label for the row + pills. */
            fun compactSpeed(rate: Float): String {
                val label = if (rate % 1f == 0f) rate.toInt().toString() else rate.toString().trimEnd('0').trimEnd('.')
                return "$label×"
            }

            fun versionString(context: Context): String {
                val name =
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull().orEmpty()
                return "Playster · v$name · a YouTube reader"
            }

            fun loadLicenses(context: Context): List<SettingsUiState.LicenseEntry> {
                val files =
                    listOf(
                        "Source Serif 4" to "source_serif4_ofl.txt",
                        "Inter Tight" to "inter_tight_ofl.txt",
                        "EB Garamond" to "eb_garamond_ofl.txt",
                        "Cormorant Garamond" to "cormorant_garamond_ofl.txt",
                        "Fraunces" to "fraunces_ofl.txt",
                    )
                return files.mapNotNull { (name, file) ->
                    runCatching {
                        context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
                    }.getOrNull()?.let { SettingsUiState.LicenseEntry(name, it) }
                }
            }
        }
    }
