package com.artifexa.ai

import com.intellij.openapi.diagnostic.logger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches the content of a public code-review standards document.
 *
 * - Rewrites GitHub blob URLs to raw.githubusercontent.com automatically.
 * - Follows redirects (gist raw URLs hop through a short-lived location).
 * - Caps the fetched body at [maxBytes] so a huge doc cannot blow the LLM context window.
 */
class StandardsFetcher(
    private val client: OkHttpClient = defaultClient,
    private val maxBytes: Int = 60_000,
) {

    data class Fetched(val content: String, val sourceUrl: String, val truncated: Boolean)

    fun fetch(url: String): Result<Fetched> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Empty URL"))

        // Local file path or file:// URI — read from disk, no network.
        if (trimmed.startsWith("file://") || trimmed.startsWith("/")) {
            return runCatching {
                val path = if (trimmed.startsWith("file://")) trimmed.removePrefix("file://") else trimmed
                val f = java.io.File(path)
                if (!f.isFile) throw RuntimeException("Standards file not found: $path")
                val bytes = f.readBytes()
                val truncated = bytes.size > maxBytes
                val slice = if (truncated) bytes.copyOf(maxBytes) else bytes
                Fetched(String(slice, Charsets.UTF_8), f.absolutePath, truncated)
            }.onFailure { log.warn("Artifexa: local standards read failed for $url: ${it.message}") }
        }

        val resolved = normaliseGithubUrl(trimmed)
        return runCatching {
            val req = Request.Builder()
                .url(resolved)
                .addHeader("Accept", "text/plain, text/markdown, */*")
                .addHeader("User-Agent", "Artifexa-IntelliJ-Plugin")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code} fetching $resolved")
                }
                val body = resp.body ?: throw RuntimeException("Empty response body")
                val bytes = body.source().apply { request(maxBytes.toLong() + 1L) }.buffer
                val truncated = bytes.size > maxBytes
                val slice = if (truncated) bytes.readByteArray(maxBytes.toLong()) else bytes.readByteArray()
                Fetched(
                    content = String(slice, Charsets.UTF_8),
                    sourceUrl = resolved,
                    truncated = truncated,
                )
            }
        }.onFailure { log.warn("Artifexa: standards fetch failed for $url: ${it.message}") }
    }

    /** `github.com/owner/repo/blob/<ref>/<path>` → `raw.githubusercontent.com/owner/repo/<ref>/<path>`. */
    internal fun normaliseGithubUrl(raw: String): String {
        val ghBlob = Regex("""^https?://(www\.)?github\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$""")
        ghBlob.matchEntire(raw)?.let {
            val (_, owner, repo, ref, path) = it.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$ref/$path"
        }
        return raw
    }

    companion object {
        private val log = logger<StandardsFetcher>()
        private val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
