package space.gexemy.tasteroute.data.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.gexemy.tasteroute.data.model.*
import space.gexemy.tasteroute.data.local.Prefs
import space.gexemy.tasteroute.data.Voice

object PreferenceState {
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
    var fontChoice by mutableStateOf(FontChoice.SYSTEM)
    var fontScale by mutableStateOf(1f)
    var welcomed by mutableStateOf(false)
    var onboarded by mutableStateOf(false)
    var preciseLocation by mutableStateOf(true)
    var saveHistory by mutableStateOf(true)
    var navVoice by mutableStateOf(true)
    var voiceName by mutableStateOf("")
    var voiceSpeed by mutableStateOf(1f)
    var units by mutableStateOf(Units.AUTO)

    fun setTheme(mode: ThemeMode) {
        themeMode = mode
        Prefs.put(Prefs.THEME, mode.name)
    }

    fun setFont(choice: FontChoice) {
        fontChoice = choice
        Prefs.put(Prefs.FONT, choice.name)
    }

    fun updateFontScale(scale: Float) {
        fontScale = scale.coerceIn(0.85f, 1.35f)
        Prefs.put(Prefs.FONT_SCALE, (fontScale * 100).toInt())
    }

    fun setWelcomed() {
        welcomed = true
        Prefs.put(Prefs.WELCOMED, true)
    }

    fun updateNavVoice(on: Boolean) {
        navVoice = on
        Prefs.put(Prefs.NAV_VOICE, on)
    }

    fun updateVoiceName(name: String) {
        voiceName = name
        Prefs.put(Prefs.VOICE_NAME, name)
        Voice.applyVoice(name)
    }

    fun updateVoiceSpeed(rate: Float) {
        voiceSpeed = rate.coerceIn(0.7f, 1.4f)
        Prefs.put(Prefs.VOICE_SPEED, (voiceSpeed * 100).toInt())
        Voice.setSpeed(voiceSpeed)
    }

    fun updateUnits(value: Units) {
        units = value
        Prefs.put(Prefs.UNITS, value.name)
    }
}
