package app.vellum.reader.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.vellum.reader.core.model.ReadingTheme
import app.vellum.reader.core.model.TypographySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.readerPrefs by preferencesDataStore(name = "reader_settings")

enum class TurnStyle { CURL, SLIDE, FADE }

/** The source responsible for turning book text into narration. */
enum class NarrationProvider(val id: String) {
    SYSTEM("system"),
    KOKORO("kokoro"),
    ELEVENLABS("elevenlabs");

    companion object {
        fun fromId(id: String?): NarrationProvider = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** ElevenLabs generation models exposed as reader-friendly quality choices. */
enum class ElevenLabsModel(val id: String) {
    PREMIUM("eleven_multilingual_v2"),
    EFFICIENT("eleven_flash_v2_5");

    companion object {
        fun fromId(id: String?): ElevenLabsModel = entries.firstOrNull { it.id == id } ?: PREMIUM
    }
}

data class ReaderSettings(
    val theme: ReadingTheme = ReadingTheme.PaperWhite,
    val typography: TypographySettings = TypographySettings(),
    val eveningMode: Boolean = false,
    val turnStyle: TurnStyle = TurnStyle.CURL,
    val hapticsEnabled: Boolean = true,
    val paperTexture: Boolean = true,
    val pageEdges: Boolean = true,
    val pageRustle: Boolean = false,
    /** 0 = focus timer off. */
    val focusMinutes: Int = 0,
    /** In-reader screen brightness 0..1; negative = follow the system. */
    val readerBrightness: Float = -1f,
    val syncFolderUri: String? = null,
    val lastSyncAt: Long = 0,
    /** System-TTS voice name; null = engine default. */
    val ttsVoice: String? = null,
    val narrationProvider: NarrationProvider = NarrationProvider.SYSTEM,
    /** Kokoro speaker id (0..10). */
    val kokoroVoice: Int = 0,
    val elevenLabsVoiceId: String? = null,
    val elevenLabsVoiceName: String? = null,
    val elevenLabsModel: ElevenLabsModel = ElevenLabsModel.PREMIUM,
)

/** DataStore-backed reader preferences shared by every book. */
class ReaderSettingsStore(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme_id")
    private val fontKey = stringPreferencesKey("font_id")
    private val fontSizeKey = floatPreferencesKey("font_size_sp")
    private val lineHeightKey = floatPreferencesKey("line_height_mult")
    private val marginKey = floatPreferencesKey("page_margin_dp")
    private val paragraphSpacingKey = floatPreferencesKey("paragraph_spacing_dp")
    private val eveningKey = booleanPreferencesKey("evening_mode")
    private val turnStyleKey = stringPreferencesKey("turn_style")
    private val hapticsKey = booleanPreferencesKey("haptics_enabled")
    private val paperTextureKey = booleanPreferencesKey("paper_texture")
    private val pageEdgesKey = booleanPreferencesKey("page_edges")
    private val pageRustleKey = booleanPreferencesKey("page_rustle")
    private val focusMinutesKey = androidx.datastore.preferences.core.intPreferencesKey("focus_minutes")
    private val brightnessKey = floatPreferencesKey("reader_brightness")
    private val syncFolderKey = stringPreferencesKey("sync_folder_uri")
    private val lastSyncKey = androidx.datastore.preferences.core.longPreferencesKey("last_sync_at")
    private val ttsVoiceKey = stringPreferencesKey("tts_voice")
    private val legacyTtsEngineKey = stringPreferencesKey("tts_engine")
    private val narrationProviderKey = stringPreferencesKey("narration_provider")
    private val kokoroVoiceKey = androidx.datastore.preferences.core.intPreferencesKey("kokoro_voice")
    private val elevenLabsVoiceIdKey = stringPreferencesKey("elevenlabs_voice_id")
    private val elevenLabsVoiceNameKey = stringPreferencesKey("elevenlabs_voice_name")
    private val elevenLabsModelKey = stringPreferencesKey("elevenlabs_model")

    val settings: Flow<ReaderSettings> = context.readerPrefs.data.map { prefs ->
        val defaults = TypographySettings()
        ReaderSettings(
            theme = ReadingTheme.byId(prefs[themeKey] ?: ReadingTheme.PaperWhite.id),
            typography = TypographySettings(
                fontId = prefs[fontKey] ?: defaults.fontId,
                fontSizeSp = prefs[fontSizeKey] ?: defaults.fontSizeSp,
                lineHeightMultiplier = prefs[lineHeightKey] ?: defaults.lineHeightMultiplier,
                pageMarginDp = prefs[marginKey] ?: defaults.pageMarginDp,
                paragraphSpacingDp = prefs[paragraphSpacingKey] ?: defaults.paragraphSpacingDp,
            ),
            eveningMode = prefs[eveningKey] ?: false,
            turnStyle = when (prefs[turnStyleKey]) {
                "slide" -> TurnStyle.SLIDE
                "fade" -> TurnStyle.FADE
                else -> TurnStyle.CURL
            },
            hapticsEnabled = prefs[hapticsKey] ?: true,
            paperTexture = prefs[paperTextureKey] ?: true,
            pageEdges = prefs[pageEdgesKey] ?: true,
            pageRustle = prefs[pageRustleKey] ?: false,
            focusMinutes = prefs[focusMinutesKey] ?: 0,
            readerBrightness = prefs[brightnessKey] ?: -1f,
            syncFolderUri = prefs[syncFolderKey],
            lastSyncAt = prefs[lastSyncKey] ?: 0,
            ttsVoice = prefs[ttsVoiceKey],
            narrationProvider = NarrationProvider.fromId(
                prefs[narrationProviderKey] ?: prefs[legacyTtsEngineKey],
            ),
            kokoroVoice = prefs[kokoroVoiceKey] ?: 0,
            elevenLabsVoiceId = prefs[elevenLabsVoiceIdKey],
            elevenLabsVoiceName = prefs[elevenLabsVoiceNameKey],
            elevenLabsModel = ElevenLabsModel.fromId(prefs[elevenLabsModelKey]),
        )
    }

    suspend fun setTheme(themeId: String) = context.readerPrefs.edit { it[themeKey] = themeId }

    suspend fun setFont(fontId: String) = context.readerPrefs.edit { it[fontKey] = fontId }

    suspend fun setFontSize(sp: Float) = context.readerPrefs.edit { it[fontSizeKey] = sp }

    suspend fun setLineHeight(multiplier: Float) = context.readerPrefs.edit { it[lineHeightKey] = multiplier }

    suspend fun setPageMargin(dp: Float) = context.readerPrefs.edit { it[marginKey] = dp }

    suspend fun setParagraphSpacing(dp: Float) = context.readerPrefs.edit { it[paragraphSpacingKey] = dp }

    suspend fun setEveningMode(enabled: Boolean) = context.readerPrefs.edit { it[eveningKey] = enabled }

    suspend fun setTurnStyle(style: TurnStyle) = context.readerPrefs.edit {
        it[turnStyleKey] = when (style) {
            TurnStyle.SLIDE -> "slide"
            TurnStyle.FADE -> "fade"
            TurnStyle.CURL -> "curl"
        }
    }

    suspend fun setReaderBrightness(value: Float) = context.readerPrefs.edit { it[brightnessKey] = value }

    suspend fun setHaptics(enabled: Boolean) = context.readerPrefs.edit { it[hapticsKey] = enabled }

    suspend fun setPaperTexture(enabled: Boolean) = context.readerPrefs.edit { it[paperTextureKey] = enabled }

    suspend fun setPageEdges(enabled: Boolean) = context.readerPrefs.edit { it[pageEdgesKey] = enabled }

    suspend fun setPageRustle(enabled: Boolean) = context.readerPrefs.edit { it[pageRustleKey] = enabled }

    suspend fun setFocusMinutes(minutes: Int) = context.readerPrefs.edit { it[focusMinutesKey] = minutes }

    suspend fun setSyncFolder(uri: String) = context.readerPrefs.edit { it[syncFolderKey] = uri }

    suspend fun setLastSyncAt(at: Long) = context.readerPrefs.edit { it[lastSyncKey] = at }

    suspend fun setTtsVoice(name: String) = context.readerPrefs.edit { it[ttsVoiceKey] = name }

    suspend fun setNarrationProvider(provider: NarrationProvider) = context.readerPrefs.edit {
        it[narrationProviderKey] = provider.id
    }

    suspend fun setKokoroVoice(sid: Int) = context.readerPrefs.edit { it[kokoroVoiceKey] = sid }

    suspend fun setElevenLabsVoice(id: String, name: String) = context.readerPrefs.edit {
        it[elevenLabsVoiceIdKey] = id
        it[elevenLabsVoiceNameKey] = name
    }

    suspend fun setElevenLabsModel(model: ElevenLabsModel) = context.readerPrefs.edit {
        it[elevenLabsModelKey] = model.id
    }
}
