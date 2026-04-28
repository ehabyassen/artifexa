package com.artifexa.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

enum class LlmProvider { OPENAI, ANTHROPIC, GITHUB_MODELS, CUSTOM }

enum class ComponentGranularity { CLASS, PACKAGE, MODULE }

@State(
    name = "ArtifexaSettings",
    storages = [Storage("artifexa.xml")],
)
class ArtifexaSettings : PersistentStateComponent<ArtifexaSettings.State> {

    class State {
        var targetBranch: String = "main"
        var llmProvider: LlmProvider = LlmProvider.OPENAI
        var customEndpointUrl: String = ""
        var openAiModel: String = "gpt-4o"
        var anthropicModel: String = "claude-sonnet-4-20250514"
        var githubModel: String = "openai/gpt-4o"
        var componentGranularity: ComponentGranularity = ComponentGranularity.CLASS
        var maxGraphNodes: Int = 150
        var localOnlyMode: Boolean = false
        /** Public URL (or `file://` path) to a code-review standards document.
         *  When set, Review grades the branch against it. */
        var codeReviewStandardsUrl: String = ""
        var layerPatterns: MutableMap<String, MutableList<String>> = defaultLayerPatterns()

        companion object {
            fun defaultLayerPatterns(): MutableMap<String, MutableList<String>> = mutableMapOf(
                "CONTROLLER" to mutableListOf(".controller", ".web", ".rest", ".api"),
                "SERVICE" to mutableListOf(".service", ".usecase", ".application"),
                "REPOSITORY" to mutableListOf(".repository", ".dao", ".persistence"),
                "DOMAIN" to mutableListOf(".domain", ".model", ".entity", ".dto"),
                "UTIL" to mutableListOf(".util", ".common", ".shared"),
            )
        }
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    // ---- API key storage via PasswordSafe ----------------------------------

    fun getApiKey(provider: LlmProvider): String? {
        val safe = PasswordSafe.instance
        return safe.getPassword(credentialAttributesFor(provider))
    }

    fun setApiKey(provider: LlmProvider, key: String?) {
        val attrs = credentialAttributesFor(provider)
        val creds = if (key.isNullOrEmpty()) null else Credentials("artifexa", key)
        PasswordSafe.instance.set(attrs, creds)
    }

    private fun credentialAttributesFor(provider: LlmProvider): CredentialAttributes =
        CredentialAttributes(generateServiceName("Artifexa", "apiKey.${provider.name}"))

    companion object {
        fun getInstance(): ArtifexaSettings =
            ApplicationManager.getApplication().getService(ArtifexaSettings::class.java)
    }
}
