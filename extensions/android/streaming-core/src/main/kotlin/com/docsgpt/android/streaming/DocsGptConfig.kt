package com.docsgpt.android.streaming

/**
 * Connection settings for a DocsGPT deployment.
 *
 * [baseUrl] should point at the API host (e.g. "https://docsapi.arc53.com" or a
 * self-hosted instance), matching VITE_API_HOST in the web frontend.
 */
data class DocsGptConfig(
    val baseUrl: String,
)
