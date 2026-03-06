package com.metrolist.music.eq.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for fetching headphone EQ profiles from the GitHub-hosted AutoEq database.
 * Uses the public Git Trees API (no token required) to index all profiles locally for instant search.
 */
@Singleton
class AutoEqRepository @Inject constructor() {

    private val okHttpClient = OkHttpClient()
    private var fileIndex: List<AutoEqMatch> = emptyList()

    /**
     * Fetches the entire file tree from GitHub.
     * This endpoint is public and does NOT require authentication.
     */
    suspend fun initializeIndex(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/jaakkopasanen/AutoEq/git/trees/master?recursive=1"
            val request = Request.Builder().url(url).build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.e("Failed to fetch AutoEq tree: ${response.code}")
                    return@withContext Result.failure(IOException("Failed to fetch tree: ${response.code}"))
                }

                val json = JSONObject(response.body?.string() ?: "")
                val tree = json.getJSONArray("tree")
                val matches = mutableListOf<AutoEqMatch>()

                for (i in 0 until tree.length()) {
                    val obj = tree.getJSONObject(i)
                    val path = obj.getString("path")

                    if (path.contains("results/") && path.endsWith("ParametricEQ.txt")) {
                        val parts = path.split("/")
                        if (parts.size >= 3) {
                            val name = parts[parts.size - 2]
                            val rawUrl = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/$path"
                            matches.add(AutoEqMatch(name = name, downloadUrl = rawUrl))
                        }
                    }
                }

                fileIndex = matches
                Timber.d("AutoEq index loaded: ${matches.size} profiles")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Timber.e(e, "AutoEq initializeIndex failed")
            Result.failure(e)
        }
    }

    /**
     * Local fuzzy search against the cached index.
     */
    fun search(query: String): List<AutoEqMatch> {
        if (query.isBlank()) return emptyList()
        return fileIndex.filter { it.name.contains(query, ignoreCase = true) }.take(20)
    }

    /**
     * Download the ParametricEQ.txt content from the given raw URL.
     */
    suspend fun downloadProfile(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Download error: ${response.code}"))
                }
                Result.success(response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Timber.e(e, "AutoEq downloadProfile failed")
            Result.failure(e)
        }
    }
}
