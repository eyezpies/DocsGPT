package com.docsgpt.android.di

import android.content.Context
import com.docsgpt.android.data.SettingsRepository
import com.docsgpt.android.streaming.ChatStreamingRepository
import com.docsgpt.android.streaming.DefaultChatStreamingRepository
import com.docsgpt.android.streaming.DocsGptConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Minimal manual DI container. Kept dependency-free (no Hilt/Dagger) so the streaming
 * repository stays easy to follow end to end; swap this for your DI framework of choice.
 */
class AppContainer(context: Context) {

    val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        // Chat responses can legitimately stay open for minutes while the LLM generates;
        // callTimeout is left at 0 (no limit) and only the read timeout is relaxed.
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /** The API host is user-configurable at runtime, so the repository is built per-request. */
    fun chatStreamingRepository(baseUrl: String): ChatStreamingRepository =
        DefaultChatStreamingRepository(config = DocsGptConfig(baseUrl = baseUrl), client = okHttpClient)
}
