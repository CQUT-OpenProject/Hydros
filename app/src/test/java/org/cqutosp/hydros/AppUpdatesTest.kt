package org.cqutosp.hydros

import org.cqutosp.hydros.data.update.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun comparesNumericVersionsAndDoesNotAnnounceEqualOrOlderVersions() {
        assertTrue(isNewerRelease("0.10.0", "0.9.0"))
        assertTrue(isNewerRelease("1.0.0", "0.99.99"))
        assertTrue(isNewerRelease("0.1.0", "0.1.0-beta.1"))
        assertFalse(isNewerRelease("0.1.0", "0.1.0"))
        assertFalse(isNewerRelease("0.1.0", "0.2.0"))
        assertFalse(isNewerRelease("invalid", "0.1.0"))
        assertFalse(isNewerRelease("999999999999999999999.0.0", "0.1.0"))
    }
    @Test fun selectsOnlyCompatibleApksAndHandlesUniversalFallback() {
        fun asset(name: String) = ReleaseAsset(name, "https://github.com/file", 100, null)
        fun release(vararg names: String) = AppRelease("0.2.0", "v0.2.0", "", assets = names.map(::asset))
        assertEquals("app-arm64-v8a.apk", release("app-x86.apk", "app-arm64-v8a.apk", "app-universal.apk").installAsset(listOf("arm64-v8a"))?.name)
        assertNull(release("app-x86_64.apk").installAsset(listOf("x86")))
        assertEquals("app-universal.apk", release("app-x86.apk", "app-universal.apk").installAsset(listOf("arm64-v8a"))?.name)
        assertEquals("app-release.apk", release("app-release.apk").installAsset(listOf("arm64-v8a"))?.name)
        assertNull(release("one.apk", "two.apk").installAsset(listOf("arm64-v8a")))
    }

    @Test fun parsesReleaseMetadataAndPaginatesStableHistory() = runTest {
        val server = MockWebServer(); server.start()
        try {
            val source = GitHubAppUpdates(endpoint = server.url("/releases/latest").toString())
            val release = """{"draft":false,"prerelease":false,"tag_name":"v0.2.0","name":"Hydros 0.2.0","published_at":"2026-10-15T00:00:00Z","assets":[{"name":"app.apk","size":123,"browser_download_url":"https://github.com/CQUT-OpenProject/Hydros/releases/download/v0.2.0/app.apk","digest":"sha256:${"ab".repeat(32)}"},{"name":"bad.apk","browser_download_url":"https://example.com/bad.apk"}]}"""
            server.enqueue(MockResponse.Builder().body(release).build())
            val latest = source.latest()!!
            assertEquals("Hydros 0.2.0", latest.name)
            assertEquals("2026-10-15T00:00:00Z", latest.publishedAt)
            assertEquals(1, latest.assets.size)
            assertEquals("ab".repeat(32), latest.assets.single().sha256)
            server.takeRequest()
            server.enqueue(MockResponse.Builder().body("[$release,{\"draft\":false,\"prerelease\":true,\"tag_name\":\"v9.0.0-beta\"}]").build())
            val page = source.history(2)
            assertEquals(listOf("0.2.0"), page.releases.map { it.version })
            assertFalse(page.hasMore)
            assertEquals("/releases?per_page=30&page=2", server.takeRequest().target)
        } finally { server.close() }
    }

    @Test fun readsPublishedReleaseAndDistinguishesNoReleaseFromFailure() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val source = GitHubAppUpdates(endpoint = server.url("/releases/latest").toString())
            server.enqueue(MockResponse.Builder().body("""{"draft":false,"prerelease":false,"tag_name":"v0.2.0","body":"更新说明"}""").build())
            val release = source.latest()!!
            assertEquals("0.2.0", release.version)
            assertEquals("更新说明", release.notes)
            assertEquals("https://github.com/CQUT-OpenProject/Hydros/releases/tag/v0.2.0", release.url)
            server.enqueue(MockResponse.Builder().code(404).build())
            assertNull(source.latest())
            server.enqueue(MockResponse.Builder().code(403).build())
            assertTrue(runCatching { source.latest() }.isFailure)
            server.enqueue(MockResponse.Builder().body("""{"draft":false,"prerelease":true,"tag_name":"v9.0.0-beta.1"}""").build())
            assertNull(source.latest())
            server.enqueue(MockResponse.Builder().body("""{"draft":true,"prerelease":false,"tag_name":"v9.0.0"}""").build())
            assertNull(source.latest())
            server.enqueue(MockResponse.Builder().body("""{"draft":false,"prerelease":false,"tag_name":"broken"}""").build())
            assertTrue(runCatching { source.latest() }.isFailure)
        } finally { server.close() }
    }
}
