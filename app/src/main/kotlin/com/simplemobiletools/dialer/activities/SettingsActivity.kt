package com.simplemobiletools.dialer.activities

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.Menu
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.simplemobiletools.commons.activities.ManageBlockedNumbersActivity
import com.simplemobiletools.commons.dialogs.ChangeDateTimeFormatDialog
import com.simplemobiletools.commons.dialogs.FeatureLockedDialog
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.RadioItem
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.databinding.ActivitySettingsBinding
import com.simplemobiletools.dialer.dialogs.ExportCallHistoryDialog
import com.simplemobiletools.dialer.dialogs.ManageVisibleTabsDialog
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.extensions.getAvailableSIMCardLabels
import com.simplemobiletools.dialer.helpers.*
import com.simplemobiletools.dialer.models.RecentCall
import com.simplemobiletools.dialer.models.SimAutoAnswerSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.system.exitProcess

class SettingsActivity : SimpleActivity() {
    companion object {
        private const val CALL_HISTORY_FILE_TYPE = "application/json"
    }

    private val binding by viewBinding(ActivitySettingsBinding::inflate)
    private val greetingManager by lazy { GreetingManager(this) }
    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            toast(R.string.importing)
            importCallHistory(uri)
        }
    }

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(CALL_HISTORY_FILE_TYPE)) { uri ->
        if (uri != null) {
            toast(R.string.exporting)
            RecentsHelper(this).getRecentCalls(false, Int.MAX_VALUE) { recents ->
                exportCallHistory(recents, uri)
            }
        }
    }

    private val pickRecordingFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Take persistable permission so we can write to this folder across reboots
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            config.callRecordingPath = uri.toString()
            updateRecordingPathLabel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        isMaterialActivity = true
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.apply {
            updateMaterialActivityViews(settingsCoordinator, settingsHolder, useTransparentNavigation = true, useTopSearchMenu = false)
            setupMaterialScrollListener(settingsNestedScrollview, settingsToolbar)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        greetingManager.shutdown()
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(binding.settingsToolbar, NavigationIcon.Arrow)

        setupPurchaseThankYou()
        setupCustomizeColors()
        setupUseEnglish()
        setupLanguage()
        setupManageBlockedNumbers()
        setupManageSpeedDial()
        setupChangeDateTimeFormat()
        setupFontSize()
        setupManageShownTabs()
        setupDefaultTab()
        setupDialPadOpen()
        setupGroupSubsequentCalls()
        setupStartNameWithSurname()
        setupDialpadVibrations()
        setupDialpadNumbers()
        setupDialpadBeeps()
        setupShowCallConfirmation()
        setupDisableProximitySensor()
        setupDisableSwipeToAnswer()
        setupAlwaysShowFullscreen()
        setupCallsExport()
        setupCallsImport()
        setupCallRecording()
        setupCallRecordingPath()
        setupNotificationActions()
        setupAutoAnswer()
        setupAutoAnswerGreeting()
        setupPreviewGreeting()
        setupListenIn()
        setupTtsEngine()
        setupTtsLanguage()
        setupPerSimSettings()
        setupSimulateCall()
        updateTextColors(binding.settingsHolder)

        binding.apply {
            arrayOf(
                settingsColorCustomizationSectionLabel,
                settingsGeneralSettingsLabel,
                settingsStartupLabel,
                settingsCallsLabel,
                settingsCallRecordingSectionLabel,
                settingsTestingSectionLabel,
                settingsMigrationSectionLabel
            ).forEach {
                it.setTextColor(getProperPrimaryColor())
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        updateMenuItemColors(menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun setupPurchaseThankYou() {
        binding.settingsPurchaseThankYouHolder.beGoneIf(isOrWasThankYouInstalled())
        binding.settingsPurchaseThankYouHolder.setOnClickListener {
            launchPurchaseThankYouIntent()
        }
    }

    private fun setupCustomizeColors() {
        binding.settingsColorCustomizationLabel.text = getCustomizeColorsString()
        binding.settingsColorCustomizationHolder.setOnClickListener {
            handleCustomizeColorsClick()
        }
    }

    private fun setupUseEnglish() {
        binding.apply {
            settingsUseEnglishHolder.beVisibleIf((config.wasUseEnglishToggled || Locale.getDefault().language != "en") && !isTiramisuPlus())
            settingsUseEnglish.isChecked = config.useEnglish
            settingsUseEnglishHolder.setOnClickListener {
                settingsUseEnglish.toggle()
                config.useEnglish = settingsUseEnglish.isChecked
                exitProcess(0)
            }
        }
    }

    private fun setupLanguage() {
        binding.apply {
            settingsLanguage.text = Locale.getDefault().displayLanguage
            settingsLanguageHolder.beVisibleIf(isTiramisuPlus())
            settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        }
    }

    // support for device-wise blocking came on Android 7, rely only on that
    @TargetApi(Build.VERSION_CODES.N)
    private fun setupManageBlockedNumbers() {
        binding.apply {
            settingsManageBlockedNumbersLabel.text = addLockedLabelIfNeeded(R.string.manage_blocked_numbers)
            settingsManageBlockedNumbersHolder.beVisibleIf(isNougatPlus())
            settingsManageBlockedNumbersHolder.setOnClickListener {
                if (isOrWasThankYouInstalled()) {
                    Intent(this@SettingsActivity, ManageBlockedNumbersActivity::class.java).apply {
                        startActivity(this)
                    }
                } else {
                    FeatureLockedDialog(this@SettingsActivity) { }
                }
            }
        }
    }

    private fun setupManageSpeedDial() {
        binding.settingsManageSpeedDialHolder.setOnClickListener {
            Intent(this, ManageSpeedDialActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupChangeDateTimeFormat() {
        binding.settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this) {}
        }
    }

    private fun setupFontSize() {
        binding.settingsFontSize.text = getFontSizeText()
        binding.settingsFontSizeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(FONT_SIZE_SMALL, getString(R.string.small)),
                RadioItem(FONT_SIZE_MEDIUM, getString(R.string.medium)),
                RadioItem(FONT_SIZE_LARGE, getString(R.string.large)),
                RadioItem(FONT_SIZE_EXTRA_LARGE, getString(R.string.extra_large))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.fontSize) {
                config.fontSize = it as Int
                binding.settingsFontSize.text = getFontSizeText()
            }
        }
    }

    private fun setupManageShownTabs() {
        binding.settingsManageTabsHolder.setOnClickListener {
            ManageVisibleTabsDialog(this)
        }
    }

    private fun setupDefaultTab() {
        binding.settingsDefaultTab.text = getDefaultTabText()
        binding.settingsDefaultTabHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(TAB_CONTACTS, getString(R.string.contacts_tab)),
                RadioItem(TAB_FAVORITES, getString(R.string.favorites_tab)),
                RadioItem(TAB_CALL_HISTORY, getString(R.string.call_history_tab)),
                RadioItem(TAB_LAST_USED, getString(R.string.last_used_tab))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.defaultTab) {
                config.defaultTab = it as Int
                binding.settingsDefaultTab.text = getDefaultTabText()
            }
        }
    }

    private fun getDefaultTabText() = getString(
        when (baseConfig.defaultTab) {
            TAB_CONTACTS -> R.string.contacts_tab
            TAB_FAVORITES -> R.string.favorites_tab
            TAB_CALL_HISTORY -> R.string.call_history_tab
            else -> R.string.last_used_tab
        }
    )

    private fun setupDialPadOpen() {
        binding.apply {
            settingsOpenDialpadAtLaunch.isChecked = config.openDialPadAtLaunch
            settingsOpenDialpadAtLaunchHolder.setOnClickListener {
                settingsOpenDialpadAtLaunch.toggle()
                config.openDialPadAtLaunch = settingsOpenDialpadAtLaunch.isChecked
            }
        }
    }

    private fun setupGroupSubsequentCalls() {
        binding.apply {
            settingsGroupSubsequentCalls.isChecked = config.groupSubsequentCalls
            settingsGroupSubsequentCallsHolder.setOnClickListener {
                settingsGroupSubsequentCalls.toggle()
                config.groupSubsequentCalls = settingsGroupSubsequentCalls.isChecked
            }
        }
    }

    private fun setupStartNameWithSurname() {
        binding.apply {
            settingsStartNameWithSurname.isChecked = config.startNameWithSurname
            settingsStartNameWithSurnameHolder.setOnClickListener {
                settingsStartNameWithSurname.toggle()
                config.startNameWithSurname = settingsStartNameWithSurname.isChecked
            }
        }
    }

    private fun setupDialpadVibrations() {
        binding.apply {
            settingsDialpadVibration.isChecked = config.dialpadVibration
            settingsDialpadVibrationHolder.setOnClickListener {
                settingsDialpadVibration.toggle()
                config.dialpadVibration = settingsDialpadVibration.isChecked
            }
        }
    }

    private fun setupDialpadNumbers() {
        binding.apply {
            settingsHideDialpadNumbers.isChecked = config.hideDialpadNumbers
            settingsHideDialpadNumbersHolder.setOnClickListener {
                settingsHideDialpadNumbers.toggle()
                config.hideDialpadNumbers = settingsHideDialpadNumbers.isChecked
            }
        }
    }

    private fun setupDialpadBeeps() {
        binding.apply {
            settingsDialpadBeeps.isChecked = config.dialpadBeeps
            settingsDialpadBeepsHolder.setOnClickListener {
                settingsDialpadBeeps.toggle()
                config.dialpadBeeps = settingsDialpadBeeps.isChecked
            }
        }
    }

    private fun setupShowCallConfirmation() {
        binding.apply {
            settingsShowCallConfirmation.isChecked = config.showCallConfirmation
            settingsShowCallConfirmationHolder.setOnClickListener {
                settingsShowCallConfirmation.toggle()
                config.showCallConfirmation = settingsShowCallConfirmation.isChecked
            }
        }
    }

    private fun setupDisableProximitySensor() {
        binding.apply {
            settingsDisableProximitySensor.isChecked = config.disableProximitySensor
            settingsDisableProximitySensorHolder.setOnClickListener {
                settingsDisableProximitySensor.toggle()
                config.disableProximitySensor = settingsDisableProximitySensor.isChecked
            }
        }
    }

    private fun setupDisableSwipeToAnswer() {
        binding.apply {
            settingsDisableSwipeToAnswer.isChecked = config.disableSwipeToAnswer
            settingsDisableSwipeToAnswerHolder.setOnClickListener {
                settingsDisableSwipeToAnswer.toggle()
                config.disableSwipeToAnswer = settingsDisableSwipeToAnswer.isChecked
            }
        }
    }

    private fun setupAlwaysShowFullscreen() {
        binding.apply {
            settingsAlwaysShowFullscreen.isChecked = config.alwaysShowFullscreen
            settingsAlwaysShowFullscreenHolder.setOnClickListener {
                settingsAlwaysShowFullscreen.toggle()
                config.alwaysShowFullscreen = settingsAlwaysShowFullscreen.isChecked
            }
        }
    }

    private fun setupCallsExport() {
        binding.settingsExportCallsHolder.setOnClickListener {
            ExportCallHistoryDialog(this) { filename ->
                saveDocument.launch(filename)
            }
        }
    }

    private fun setupCallsImport() {
        binding.settingsImportCallsHolder.setOnClickListener {
            getContent.launch(CALL_HISTORY_FILE_TYPE)
        }
    }

    private fun setupCallRecording() {
        binding.apply {
            settingsCallRecording.isChecked = config.callRecordingEnabled
            settingsCallRecordingHolder.setOnClickListener {
                settingsCallRecording.toggle()
                config.callRecordingEnabled = settingsCallRecording.isChecked
            }
        }
    }

    private fun setupAutoAnswer() {
        binding.settingsAutoAnswer.text = getAutoAnswerText()
        binding.settingsAutoAnswerHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(AUTO_ANSWER_NONE, getString(R.string.auto_answer_none)),
                RadioItem(AUTO_ANSWER_ALL, getString(R.string.auto_answer_all)),
                RadioItem(AUTO_ANSWER_UNKNOWN, getString(R.string.auto_answer_unknown))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.autoAnswerMode) {
                config.autoAnswerMode = it as Int
                binding.settingsAutoAnswer.text = getAutoAnswerText()
                updateAutoAnswerSettingsVisibility()
            }
        }
        updateAutoAnswerSettingsVisibility()
    }

    private fun updateAutoAnswerSettingsVisibility() {
        val enabled = config.autoAnswerMode != AUTO_ANSWER_NONE
        binding.settingsAutoAnswerGreetingHolder.beVisibleIf(enabled)
        binding.settingsPreviewGreetingHolder.beVisibleIf(enabled)
        binding.settingsListenInHolder.beVisibleIf(enabled)
        binding.settingsTtsEngineHolder.beVisibleIf(enabled)
        binding.settingsTtsLanguageHolder.beVisibleIf(enabled)
        binding.settingsPerSimContainer.beVisibleIf(enabled)
    }

    private fun setupAutoAnswerGreeting() {
        binding.settingsAutoAnswerGreeting.text = config.autoAnswerGreeting.ifEmpty { getString(R.string.auto_answer_none) }
        binding.settingsAutoAnswerGreetingHolder.setOnClickListener {
            val editText = EditText(this).apply {
                setText(config.autoAnswerGreeting)
                hint = getString(R.string.auto_answer_greeting_hint)
                setPadding(40, 30, 40, 30)
                minLines = 3
            }

            AlertDialog.Builder(this)
                .setTitle(getString(R.string.auto_answer_greeting))
                .setView(editText)
                .setPositiveButton(R.string.ok) { _, _ ->
                    config.autoAnswerGreeting = editText.text.toString().trim()
                    binding.settingsAutoAnswerGreeting.text = config.autoAnswerGreeting.ifEmpty { getString(R.string.auto_answer_none) }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupPreviewGreeting() {
        binding.settingsPreviewGreetingHolder.setOnClickListener {
            val greeting = config.autoAnswerGreeting
            if (greeting.isEmpty()) {
                toast(R.string.greeting_empty)
                return@setOnClickListener
            }

            toast(R.string.preview_greeting_playing)
            greetingManager.playGreetingPreview(
                greeting = greeting,
                languageTag = config.ttsLanguage,
                engine = config.ttsEngine
            ) {
                runOnUiThread {
                    // Preview finished
                }
            }
        }
    }

    private fun setupListenIn() {
        updateListenInLabel()
        binding.settingsListenInHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(LISTEN_IN_OFF, getString(R.string.listen_in_off)),
                RadioItem(LISTEN_IN_NOTIFICATION, getString(R.string.listen_in_notification)),
                RadioItem(LISTEN_IN_AUTO, getString(R.string.listen_in_auto))
            )

            RadioGroupDialog(this@SettingsActivity, items, config.listenInMode) {
                config.listenInMode = it as Int
                updateListenInLabel()
            }
        }
    }

    private fun updateListenInLabel() {
        binding.settingsListenIn.text = getString(
            when (config.listenInMode) {
                LISTEN_IN_AUTO -> R.string.listen_in_auto
                LISTEN_IN_NOTIFICATION -> R.string.listen_in_notification
                else -> R.string.listen_in_off
            }
        )
    }

    private fun setupTtsEngine() {
        updateTtsEngineLabel()
        binding.settingsTtsEngineHolder.setOnClickListener {
            val engines = greetingManager.getAvailableEngines()
            if (engines.isEmpty()) {
                toast(R.string.tts_engine_default)
                return@setOnClickListener
            }

            val items = arrayListOf(RadioItem(0, getString(R.string.tts_engine_default)))
            engines.forEachIndexed { index, engine ->
                items.add(RadioItem(index + 1, engine.label))
            }

            val currentEngine = config.ttsEngine
            val selectedIndex = if (currentEngine.isEmpty()) {
                0
            } else {
                val idx = engines.indexOfFirst { it.name == currentEngine }
                if (idx >= 0) idx + 1 else 0
            }

            RadioGroupDialog(this@SettingsActivity, items, selectedIndex) {
                val selected = it as Int
                config.ttsEngine = if (selected == 0) "" else engines[selected - 1].name
                updateTtsEngineLabel()
                // Reset language when engine changes since available languages differ
                config.ttsLanguage = ""
                updateTtsLanguageLabel()
            }
        }
    }

    private fun updateTtsEngineLabel() {
        val engineName = config.ttsEngine
        if (engineName.isEmpty()) {
            binding.settingsTtsEngine.text = getString(R.string.tts_engine_default)
        } else {
            val engines = greetingManager.getAvailableEngines()
            val label = engines.firstOrNull { it.name == engineName }?.label ?: engineName
            binding.settingsTtsEngine.text = label
        }
    }

    private fun setupTtsLanguage() {
        updateTtsLanguageLabel()
        binding.settingsTtsLanguageHolder.setOnClickListener {
            // We need TTS initialised to query available languages
            // Use a temporary TTS with the selected engine
            val enginePkg = config.ttsEngine
            val initListener = TextToSpeech.OnInitListener { status ->
                if (status != TextToSpeech.SUCCESS) {
                    runOnUiThread { toast(R.string.tts_language_default) }
                    return@OnInitListener
                }
            }

            val tempTts = if (enginePkg.isNotEmpty()) {
                TextToSpeech(this, initListener, enginePkg)
            } else {
                TextToSpeech(this, initListener)
            }

            // Give TTS a moment to initialise then query languages
            android.os.Handler(mainLooper).postDelayed({
                val locales = try {
                    tempTts.availableLanguages?.toList()?.sortedBy { it.displayName } ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                if (locales.isEmpty()) {
                    tempTts.shutdown()
                    toast(R.string.tts_language_default)
                    return@postDelayed
                }

                val items = arrayListOf(RadioItem(0, getString(R.string.tts_language_default)))
                locales.forEachIndexed { index, locale ->
                    items.add(RadioItem(index + 1, locale.displayName))
                }

                val currentTag = config.ttsLanguage
                val selectedIndex = if (currentTag.isEmpty()) {
                    0
                } else {
                    val idx = locales.indexOfFirst { it.toLanguageTag() == currentTag }
                    if (idx >= 0) idx + 1 else 0
                }

                RadioGroupDialog(this@SettingsActivity, items, selectedIndex) {
                    val selected = it as Int
                    config.ttsLanguage = if (selected == 0) "" else locales[selected - 1].toLanguageTag()
                    updateTtsLanguageLabel()
                }

                tempTts.shutdown()
            }, 1000)
        }
    }

    private fun updateTtsLanguageLabel() {
        val tag = config.ttsLanguage
        binding.settingsTtsLanguage.text = if (tag.isEmpty()) {
            getString(R.string.tts_language_default)
        } else {
            Locale.forLanguageTag(tag).displayName
        }
    }

    private fun setupPerSimSettings() {
        val container = binding.settingsPerSimContainer
        container.removeAllViews()

        val simAccounts = getAvailableSIMCardLabels()
        if (simAccounts.size < 2) {
            // No point showing per-SIM settings with a single SIM
            container.beGone()
            return
        }

        container.beVisibleIf(config.autoAnswerMode != AUTO_ANSWER_NONE)

        for (sim in simAccounts) {
            val simId = sim.id.toString()
            val simSettings = config.getSimSettings(simId)

            // Create a settings row for each SIM
            val holder = RelativeLayout(this).apply {
                val padding = resources.getDimensionPixelSize(com.simplemobiletools.commons.R.dimen.activity_margin)
                setPadding(padding, padding / 2, padding, padding / 2)
                setBackgroundResource(com.simplemobiletools.commons.R.drawable.ripple_background)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val labelView = com.simplemobiletools.commons.views.MyTextView(this).apply {
                text = getString(R.string.sim_settings_title, sim.id, sim.label)
                setTextAppearance(com.simplemobiletools.commons.R.style.SettingsTextLabelStyle)
                id = android.view.View.generateViewId()
            }
            holder.addView(labelView, RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ))

            val valueView = com.simplemobiletools.commons.views.MyTextView(this).apply {
                val hasCustom = simSettings.greeting.isNotEmpty() || simSettings.language.isNotEmpty()
                text = if (hasCustom) getString(R.string.sim_settings_configured) else getString(R.string.sim_settings_default)
                setTextAppearance(com.simplemobiletools.commons.R.style.SettingsTextValueStyle)
                val lp = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                )
                lp.addRule(RelativeLayout.BELOW, labelView.id)
                layoutParams = lp
            }
            holder.addView(valueView)

            holder.setOnClickListener {
                showPerSimDialog(sim.id, sim.label, valueView)
            }

            container.addView(holder)
        }
    }

    private fun showPerSimDialog(simId: Int, simLabel: String, valueView: com.simplemobiletools.commons.views.MyTextView) {
        val simIdStr = simId.toString()
        val currentSettings = config.getSimSettings(simIdStr)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
        }

        // Language selector
        val languageLabel = android.widget.TextView(this).apply {
            text = getString(R.string.sim_language, simId)
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setPadding(0, 10, 0, 5)
        }
        layout.addView(languageLabel)

        val languageButton = android.widget.Button(this).apply {
            val langTag = currentSettings.language
            text = if (langTag.isEmpty()) getString(R.string.tts_language_default) else Locale.forLanguageTag(langTag).displayName
            isAllCaps = false
        }
        // Store the current language tag
        languageButton.tag = currentSettings.language
        languageButton.setOnClickListener {
            showLanguagePickerForSim(languageButton)
        }
        layout.addView(languageButton)

        // Greeting editor
        val greetingLabel = android.widget.TextView(this).apply {
            text = getString(R.string.sim_greeting, simId)
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setPadding(0, 20, 0, 5)
        }
        layout.addView(greetingLabel)

        val greetingEdit = EditText(this).apply {
            setText(currentSettings.greeting)
            hint = getString(R.string.sim_greeting_hint)
            minLines = 3
        }
        layout.addView(greetingEdit)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sim_settings_title, simId, simLabel))
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val newSettings = SimAutoAnswerSettings(
                    language = (languageButton.tag as? String) ?: "",
                    greeting = greetingEdit.text.toString().trim()
                )
                config.setSimSettings(simIdStr, newSettings)
                val hasCustom = newSettings.greeting.isNotEmpty() || newSettings.language.isNotEmpty()
                valueView.text = if (hasCustom) getString(R.string.sim_settings_configured) else getString(R.string.sim_settings_default)
            }
            .setNeutralButton(R.string.call_recording_path_default) { _, _ ->
                // Reset to defaults
                config.setSimSettings(simIdStr, SimAutoAnswerSettings())
                valueView.text = getString(R.string.sim_settings_default)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLanguagePickerForSim(button: android.widget.Button) {
        val enginePkg = config.ttsEngine
        val initListener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                runOnUiThread { toast(R.string.tts_language_default) }
            }
        }

        val tempTts = if (enginePkg.isNotEmpty()) {
            TextToSpeech(this, initListener, enginePkg)
        } else {
            TextToSpeech(this, initListener)
        }

        android.os.Handler(mainLooper).postDelayed({
            val locales = try {
                tempTts.availableLanguages?.toList()?.sortedBy { it.displayName } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            if (locales.isEmpty()) {
                tempTts.shutdown()
                toast(R.string.tts_language_default)
                return@postDelayed
            }

            val items = arrayListOf(RadioItem(0, getString(R.string.tts_language_default)))
            locales.forEachIndexed { index, locale ->
                items.add(RadioItem(index + 1, locale.displayName))
            }

            val currentTag = (button.tag as? String) ?: ""
            val selectedIndex = if (currentTag.isEmpty()) {
                0
            } else {
                val idx = locales.indexOfFirst { it.toLanguageTag() == currentTag }
                if (idx >= 0) idx + 1 else 0
            }

            RadioGroupDialog(this@SettingsActivity, items, selectedIndex) {
                val selected = it as Int
                val newTag = if (selected == 0) "" else locales[selected - 1].toLanguageTag()
                button.tag = newTag
                button.text = if (newTag.isEmpty()) getString(R.string.tts_language_default) else Locale.forLanguageTag(newTag).displayName
            }

            tempTts.shutdown()
        }, 1000)
    }

    private fun setupSimulateCall() {
        binding.settingsSimulateCallHolder.setOnClickListener {
            val intent = Intent(this, SimulatedCallActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupCallRecordingPath() {
        updateRecordingPathLabel()

        binding.settingsCallRecordingPathHolder.setOnClickListener {
            if (config.callRecordingPath.isEmpty()) {
                // No custom path set — launch folder picker directly
                pickRecordingFolder.launch(null)
            } else {
                // Already has custom path — offer to change or reset
                val items = arrayListOf(
                    RadioItem(0, getString(R.string.call_recording_path_change)),
                    RadioItem(1, getString(R.string.call_recording_path_default))
                )

                RadioGroupDialog(this@SettingsActivity, items, -1) {
                    when (it as Int) {
                        0 -> {
                            // Try to open picker at current folder
                            val currentUri = Uri.parse(config.callRecordingPath)
                            pickRecordingFolder.launch(currentUri)
                        }
                        1 -> {
                            // Release persistable permissions for the old URI
                            try {
                                val oldUri = Uri.parse(config.callRecordingPath)
                                contentResolver.releasePersistableUriPermission(
                                    oldUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                            } catch (_: Exception) {}
                            config.callRecordingPath = ""
                            updateRecordingPathLabel()
                        }
                    }
                }
            }
        }
    }

    private fun setupNotificationActions() {
        updateNotificationActionsLabel()

        binding.settingsNotificationActionsHolder.setOnClickListener {
            showNotificationActionsDialog()
        }
    }

    private fun showNotificationActionsDialog() {
        val current = config.callEndNotificationActions
        val labels = arrayOf(
            getString(R.string.notif_action_play_recording),
            getString(R.string.notif_action_share),
            getString(R.string.notif_action_share_recording),
            getString(R.string.notif_action_share_transcription),
            getString(R.string.notif_action_show_transcription)
        )
        val flags = intArrayOf(
            NOTIF_ACTION_PLAY_RECORDING,
            NOTIF_ACTION_SHARE,
            NOTIF_ACTION_SHARE_RECORDING,
            NOTIF_ACTION_SHARE_TRANSCRIPTION,
            NOTIF_ACTION_SHOW_TRANSCRIPTION
        )
        val checked = BooleanArray(flags.size) { current and flags[it] != 0 }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notification_actions_label))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.ok) { _, _ ->
                var result = 0
                for (i in flags.indices) {
                    if (checked[i]) result = result or flags[i]
                }
                config.callEndNotificationActions = result
                updateNotificationActionsLabel()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateNotificationActionsLabel() {
        val current = config.callEndNotificationActions
        val names = mutableListOf<String>()
        if (current and NOTIF_ACTION_PLAY_RECORDING != 0) names.add(getString(R.string.notif_action_play_recording))
        if (current and NOTIF_ACTION_SHARE != 0) names.add(getString(R.string.notif_action_share))
        if (current and NOTIF_ACTION_SHARE_RECORDING != 0) names.add(getString(R.string.notif_action_share_recording))
        if (current and NOTIF_ACTION_SHARE_TRANSCRIPTION != 0) names.add(getString(R.string.notif_action_share_transcription))
        if (current and NOTIF_ACTION_SHOW_TRANSCRIPTION != 0) names.add(getString(R.string.notif_action_show_transcription))

        binding.settingsNotificationActions.text = if (names.isEmpty()) {
            getString(R.string.auto_answer_none)
        } else {
            names.joinToString(", ")
        }
    }

    private fun updateRecordingPathLabel() {
        val uriString = config.callRecordingPath
        binding.settingsCallRecordingPath.text = if (uriString.isEmpty()) {
            getString(R.string.call_recording_path_default)
        } else {
            try {
                val uri = Uri.parse(uriString)
                val docFile = DocumentFile.fromTreeUri(this, uri)
                docFile?.name ?: uriString
            } catch (_: Exception) {
                uriString
            }
        }
    }

    private fun getAutoAnswerText() = getString(
        when (config.autoAnswerMode) {
            AUTO_ANSWER_ALL -> R.string.auto_answer_all
            AUTO_ANSWER_UNKNOWN -> R.string.auto_answer_unknown
            else -> R.string.auto_answer_none
        }
    )

    private fun importCallHistory(uri: Uri) {
        try {
            val jsonString = contentResolver.openInputStream(uri)!!.use { inputStream ->
                inputStream.bufferedReader().readText()
            }

            val objects = Json.decodeFromString<List<RecentCall>>(jsonString)

            if (objects.isEmpty()) {
                toast(R.string.no_entries_for_importing)
                return
            }

            RecentsHelper(this).restoreRecentCalls(this, objects) {
                toast(R.string.importing_successful)
            }
        } catch (_: SerializationException) {
            toast(R.string.invalid_file_format)
        } catch (_: IllegalArgumentException) {
            toast(R.string.invalid_file_format)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun exportCallHistory(recents: List<RecentCall>, uri: Uri) {
        if (recents.isEmpty()) {
            toast(R.string.no_entries_for_exporting)
        } else {
            try {
                val outputStream = contentResolver.openOutputStream(uri)!!

                val jsonString = Json.encodeToString(recents)
                outputStream.use {
                    it.write(jsonString.toByteArray())
                }
                toast(R.string.exporting_successful)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }
}
