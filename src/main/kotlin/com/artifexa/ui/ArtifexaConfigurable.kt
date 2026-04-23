package com.artifexa.ui

import com.artifexa.settings.ArtifexaSettings
import com.artifexa.settings.ComponentGranularity
import com.artifexa.settings.LlmProvider
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class ArtifexaConfigurable : Configurable {

    private val settings = ArtifexaSettings.getInstance()
    private val state = settings.state

    private val openAiKeyField = JBPasswordField()
    private val anthropicKeyField = JBPasswordField()
    private val customEndpointField = JBTextField()

    private var rootPanel: JComponent? = null

    override fun getDisplayName(): String = "Artifexa"

    override fun createComponent(): JComponent {
        openAiKeyField.text = settings.getApiKey(LlmProvider.OPENAI).orEmpty()
        anthropicKeyField.text = settings.getApiKey(LlmProvider.ANTHROPIC).orEmpty()
        customEndpointField.text = state.customEndpointUrl

        rootPanel = panel {
            group("Branch Analysis") {
                row("Target base branch:") {
                    textField().bindText(state::targetBranch).align(AlignX.FILL)
                }
                row("Component granularity:") {
                    comboBox(ComponentGranularity.values().toList())
                        .bindItem({ state.componentGranularity }, { state.componentGranularity = it ?: ComponentGranularity.CLASS })
                }
                row("Maximum graph nodes:") {
                    intTextField(10..1000).bindIntText(state::maxGraphNodes)
                }
            }

            group("AI Review") {
                row("Provider:") {
                    comboBox(LlmProvider.values().toList())
                        .bindItem({ state.llmProvider }, { state.llmProvider = it ?: LlmProvider.OPENAI })
                }
                row("Local-only mode (disable network):") {
                    checkBox("").bindSelected(state::localOnlyMode)
                }
                row("OpenAI model:") {
                    textField().bindText(state::openAiModel).align(AlignX.FILL)
                }
                row("Anthropic model:") {
                    textField().bindText(state::anthropicModel).align(AlignX.FILL)
                }
                row("Custom endpoint URL:") {
                    cell(customEndpointField).align(AlignX.FILL)
                }
                row("Code review standards URL:") {
                    textField().bindText(state::codeReviewStandardsUrl).align(AlignX.FILL)
                        .comment("Public URL to a Markdown/plain-text file (GitHub blob or raw). " +
                            "AI Review will grade the branch against this document.")
                }
            }

            group("API Keys (stored in OS keychain)") {
                row(JBLabel("OpenAI API key:")) { cell(openAiKeyField).align(AlignX.FILL) }
                row(JBLabel("Anthropic API key:")) { cell(anthropicKeyField).align(AlignX.FILL) }
            }
        }
        return rootPanel!!
    }

    override fun isModified(): Boolean {
        val storedOpenAi = settings.getApiKey(LlmProvider.OPENAI).orEmpty()
        val storedAnthropic = settings.getApiKey(LlmProvider.ANTHROPIC).orEmpty()
        return storedOpenAi != String(openAiKeyField.password) ||
            storedAnthropic != String(anthropicKeyField.password) ||
            state.customEndpointUrl != customEndpointField.text
    }

    override fun apply() {
        state.customEndpointUrl = customEndpointField.text.trim()
        val openAiKey = String(openAiKeyField.password).trim()
        val anthropicKey = String(anthropicKeyField.password).trim()
        settings.setApiKey(LlmProvider.OPENAI, openAiKey.takeIf { it.isNotEmpty() })
        settings.setApiKey(LlmProvider.ANTHROPIC, anthropicKey.takeIf { it.isNotEmpty() })
    }

    override fun reset() {
        openAiKeyField.text = settings.getApiKey(LlmProvider.OPENAI).orEmpty()
        anthropicKeyField.text = settings.getApiKey(LlmProvider.ANTHROPIC).orEmpty()
        customEndpointField.text = state.customEndpointUrl
    }

    override fun disposeUIResources() {
        rootPanel = null
    }
}
