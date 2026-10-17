package dev.uednd.hydros.data.update

import dev.uednd.hydros.data.feedback.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

const val HYDROS_REPOSITORY = "https://github.com/UE-DND/Hydros"
const val HYDROS_RELEASES = "$HYDROS_REPOSITORY/releases"

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

data class AppRelease(
    val version: String, val tag: String, val notes: String,
    val name: String = "v$version", val publishedAt: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
) {
    val url: String get() = "$HYDROS_RELEASES/tag/${URLEncoder.encode(tag, "UTF-8").replace("+", "%20")}"
}

data class ReleasePage(val releases: List<AppRelease>, val hasMore: Boolean)

fun interface AppUpdateSource {
    suspend fun latest(): AppRelease?
    suspend fun history(page: Int): ReleasePage = ReleasePage(emptyList(), false)
    suspend fun release(tag: String): AppRelease? = latest()?.takeIf { it.tag == tag }
}

/** Only published, stable GitHub releases can announce an update. */
class GitHubAppUpdates(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val endpoint: String = "https://api.github.com/repos/UE-DND/Hydros/releases/latest",
) : AppUpdateSource {
    override suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        request(endpoint, allowMissing = true)?.let { parseUpdateData { parseRelease(it.jsonObject) } }
    }

    override suspend fun history(page: Int): ReleasePage = withContext(Dispatchers.IO) {
        require(page > 0)
        val entries = parseUpdateData { request("${endpoint.removeSuffix("/latest")}?per_page=30&page=$page")!!.jsonArray }
        ReleasePage(entries.mapNotNull { parseUpdateData { parseRelease(it.jsonObject) } }, entries.size == 30)
    }

    override suspend fun release(tag: String): AppRelease? = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(tag, "UTF-8").replace("+", "%20")
        request("${endpoint.removeSuffix("/latest")}/tags/$encoded", allowMissing = true)?.let { parseUpdateData { parseRelease(it.jsonObject) } }
    }

    private fun request(url: String, allowMissing: Boolean = false): JsonElement? {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Hydros-Android").build()
        return client.newCall(request).execute().use { response ->
            if (allowMissing && response.code == 404) return null
            if (!response.isSuccessful) throw AppFailure(FailureOperation.UPDATE, FailureStage.RESPONSE, FailureReason.REJECTED)
            parseUpdateData { Json.parseToJsonElement(response.body.string()) }
        }
    }
}

private inline fun <T> parseUpdateData(block: () -> T): T = try { block() }
catch (error: AppFailure) { throw error }
catch (error: IllegalArgumentException) { throw AppFailure(FailureOperation.UPDATE, FailureStage.RESPONSE, FailureReason.INVALID_DATA, error) }

internal fun parseRelease(json: JsonObject): AppRelease? {
    if (json["draft"]?.jsonPrimitive?.booleanOrNull != false ||
        json["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return null
    val tag = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw AppFailure(FailureOperation.UPDATE, FailureStage.RESPONSE, FailureReason.INVALID_DATA)
    val version = tag.removePrefix("v")
    if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version)) throw AppFailure(FailureOperation.UPDATE, FailureStage.RESPONSE, FailureReason.INVALID_DATA)
    val assets = json["assets"]?.jsonArray.orEmpty().mapNotNull { element ->
        val asset = element.jsonObject
        val name = asset["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        if (!name.endsWith(".apk", ignoreCase = true) || !url.startsWith("https://github.com/UE-DND/Hydros/releases/download/")) return@mapNotNull null
        ReleaseAsset(name, url, asset["size"]?.jsonPrimitive?.longOrNull ?: 0,
            asset["digest"]?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("sha256:[a-fA-F0-9]{64}")) }?.substringAfter(':'))
    }
    return AppRelease(version, tag, json["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        json["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: tag,
        json["published_at"]?.jsonPrimitive?.contentOrNull, assets)
}

/** Prefer a matching split, then an explicitly universal or sole unqualified APK. */
fun AppRelease.installAsset(abis: List<String>): ReleaseAsset? {
    val known = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    fun matches(name: String, abi: String): Boolean =
        (abi != "x86" || !Regex("(^|[-_.])${Regex.escape("x86_64")}(?=[-_.]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)) &&
            Regex("(^|[-_.])${Regex.escape(abi)}(?=[-_.]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name)
    for (abi in abis) assets.singleOrNull { asset -> known.firstOrNull { matches(asset.name, it) } == abi }?.let { return it }
    assets.singleOrNull { it.name.contains("universal", ignoreCase = true) }?.let { return it }
    return assets.filter { asset -> known.none { matches(asset.name, it) } }.singleOrNull()
}

/** Compare numeric version components; 0.10.0 must be newer than 0.9.0. */
fun isNewerRelease(latest: String, installed: String): Boolean {
    fun parts(value: String) = value.removePrefix("v").substringBefore('-').substringBefore('+')
        .split('.').takeIf { it.size == 3 }?.map { it.toLongOrNull() }
        ?.takeIf { values -> values.all { it != null && it >= 0 } }?.filterNotNull()
    val next = parts(latest) ?: return false
    val current = parts(installed) ?: return false
    for (index in next.indices) {
        if (next[index] != current[index]) return next[index] > current[index]
    }
    return '-' in installed && '-' !in latest
}
