package com.artifexa.ui

import com.artifexa.settings.ArtifexaSettings
import com.artifexa.settings.ComponentGranularity
import com.artifexa.settings.LlmProvider
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
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
    private val githubKeyField = JBPasswordField()
    private val customEndpointField = JBTextField()

    private var dslPanel: DialogPanel? = null

    override fun getDisplayName(): String = "Artifexa"

    override fun createComponent(): JComponent {
        openAiKeyField.text = settings.getApiKey(LlmProvider.OPENAI).orEmpty()
        anthropicKeyField.text = settings.getApiKey(LlmProvider.ANTHROPIC).orEmpty()
        githubKeyField.text = settings.getApiKey(LlmProvider.GITHUB_MODELS).orEmpty()
        customEndpointField.text = state.customEndpointUrl

        val panel = panel {
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
                row("Local-only mode (no network, no LLM):") {
                    checkBox("").bindSelected(state::localOnlyMode)
                        .comment("When enabled, AI Review runs entirely offline using rules-based " +
                            "heuristics (layer violations, method length, naming, test coverage, " +
                            "standards keyword matching). No data leaves your machine.")
                }
                row("OpenAI model:") {
                    textField().bindText(state::openAiModel).align(AlignX.FILL)
                }
                row("Anthropic model:") {
                    textField().bindText(state::anthropicModel).align(AlignX.FILL)
                }
                row("GitHub Models model:") {
                    textField().bindText(state::githubModel).align(AlignX.FILL)
                        .comment("Publisher-namespaced model id, e.g. <code>openai/gpt-4o</code>, " +
                            "<code>meta/Meta-Llama-3.1-70B-Instruct</code>, <code>mistral-ai/Mistral-Large-2411</code>.")
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
                row(JBLabel("GitHub token:")) {
                    cell(githubKeyField).align(AlignX.FILL)
                        .comment("PAT with the <code>models:read</code> scope (or fine-grained equivalent). " +
                            "Free-tier rate limits are tighter than paid OpenAI/Anthropic.")
                }
            }
        }
        dslPanel = panel
        return panel
    }

    override fun isModified(): Boolean {
        val storedOpenAi = settings.getApiKey(LlmProvider.OPENAI).orEmpty()
        val storedAnthropic = settings.getApiKey(LlmProvider.ANTHROPIC).orEmpty()
        val storedGithub = settings.getApiKey(LlmProvider.GITHUB_MODELS).orEmpty()
        val keysChanged = storedOpenAi != String(openAiKeyField.password) ||
            storedAnthropic != String(anthropicKeyField.password) ||
            storedGithub != String(githubKeyField.password)
        val endpointChanged = state.customEndpointUrl != customEndpointField.text
        val panelChanged = dslPanel?.isModified() == true
        return keysChanged || endpointChanged || panelChanged
    }

    override fun apply() {
        // Flush the Kotlin UI DSL bindings into the State object — without this, every
        // bindText/bindItem/bindSelected field is discarded on Apply.
        dslPanel?.apply()

        state.customEndpointUrl = customEndpointField.text.trim()
        val openAiKey = String(openAiKeyField.password).trim()
        val anthropicKey = String(anthropicKeyField.password).trim()
        val githubKey = String(githubKeyField.password).trim()
        settings.setApiKey(LlmProvider.OPENAI, openAiKey.takeIf { it.isNotEmpty() })
        settings.setApiKey(LlmProvider.ANTHROPIC, anthropicKey.takeIf { it.isNotEmpty() })
        settings.setApiKey(LlmProvider.GITHUB_MODELS, githubKey.takeIf { it.isNotEmpty() })
    }

    override fun reset() {
        dslPanel?.reset()
        openAiKeyField.text = settings.getApiKey(LlmProvider.OPENAI).orEmpty()
        anthropicKeyField.text = settings.getApiKey(LlmProvider.ANTHROPIC).orEmpty()
        githubKeyField.text = settings.getApiKey(LlmProvider.GITHUB_MODELS).orEmpty()
        customEndpointField.text = state.customEndpointUrl
    }

    override fun disposeUIResources() {
        dslPanel = null
    }
}
