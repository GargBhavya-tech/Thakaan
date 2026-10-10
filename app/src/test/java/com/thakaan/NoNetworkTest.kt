package com.thakaan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Airplane-mode proof, part one (#56): the app cannot reach the network at all. The demo path has no INTERNET permission,
 * and no source file builds a URL connection, socket or web view. (The on-stage airplane-mode run is a phone test.)
 */
class NoNetworkTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun manifestDeclaresNoNetworkPermissions() {
        for (p in listOf("android.permission.INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_WIFI_STATE", "CHANGE_NETWORK_STATE", "NEARBY_WIFI_DEVICES")) {
            assertFalse("manifest must not declare $p", manifest.contains(p))
        }
    }

    @Test fun noSourceOpensAConnection() {
        assertTrue(sources.size > 30)
        val banned = listOf("HttpURLConnection", "HttpsURLConnection", "java.net.Socket", "okhttp", "URL(\"http", "WebView", "fonts.googleapis", "DownloadManager", "retrofit")
        for (f in sources) {
            val text = f.readText()
            for (b in banned) assertFalse("${f.name} uses $b", text.contains(b))
        }
    }

    @Test fun noHardcodedWebAddressesInCode() {
        val url = Regex("https?://[A-Za-z0-9]")
        for (f in sources) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }
            for (line in code) {
                if (url.containsMatchIn(line)) assertTrue("${f.name} has a web address in code: $line", line.contains("schemas.android.com") || line.contains("tools.android") || line.contains("maps.google.com") /* link text inside the SMS body, never fetched by the app */)
            }
        }
    }

    @Test fun mapAndWeatherDataShipInsideTheApp() {
        for (p in listOf("src/main/assets/chhaya/graph.json", "src/main/assets/chhaya/shade.json", "src/main/assets/weather/bengaluru_open-meteo.json", "src/main/assets/ectemp_vectors.json")) {
            assertTrue("missing bundled asset $p", File(p).isFile)
        }
    }
}

