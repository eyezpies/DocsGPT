package com.docsgpt.android.di

import com.docsgpt.android.BuildConfig
import com.docsgpt.android.streaming.ChatStreamingRepository
import com.docsgpt.android.streaming.DefaultChatStreamingRepository
import com.docsgpt.android.streaming.DocsGptConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Minimal manual DI container. Kept dependency-free (no Hilt/Dagger) so the streaming
 * repository stays easy to follow end to end; swap this for your DI framework of choice.
 */
class AppContainer(apiHost: String = BuildConfig.DEFAULT_API_HOST) {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        // Chat responses can legitimately stay open for minutes while the LLM generates;
        // callTimeout is left at 0 (no limit) and only the read timeout is relaxed.
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    val chatStreamingRepository: ChatStreamingRepository = DefaultChatStreamingRepository(
        config = DocsGptConfig(baseUrl = apiHost),
        client = okHttpClient,
    )
}
