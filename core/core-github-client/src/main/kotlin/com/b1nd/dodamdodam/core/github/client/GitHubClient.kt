package com.b1nd.dodamdodam.core.github.client

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.client.HttpClient

class GitHubClient(
    private val webClient: WebClient
) {
    private val objectMapper = ObjectMapper()

    fun downloadReleaseAsset(owner: String, repo: String, tag: String): ByteArray {
        val json = webClient.get()
            .uri("/repos/{owner}/{repo}/releases/tags/{tag}", owner, repo, tag)
            .retrieve()
            .bodyToMono<String>()
            .block() ?: throw IllegalStateException("Release not found: $owner/$repo@$tag")

        val release = objectMapper.readTree(json)
        val assets = release["assets"]

        if (assets == null || !assets.isArray || assets.isEmpty) {
            throw IllegalStateException("No assets found in release: $owner/$repo@$tag")
        }

        val zipAsset = assets.firstOrNull {
            it["name"]?.asText()?.endsWith(".zip") == true
        } ?: throw IllegalStateException(
            "No .zip asset found in release: $owner/$repo@$tag. Available: ${assets.map { it["name"]?.asText() }}"
        )

        val downloadUrl = zipAsset["browser_download_url"]?.asText()
            ?: throw IllegalStateException("No download URL for asset: ${zipAsset["name"]?.asText()}")

        val httpClient = HttpClient.create().followRedirect(true)
        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { it.defaultCodecs().maxInMemorySize(50 * 1024 * 1024) }
            .build()
            .get()
            .uri(downloadUrl)
            .accept(MediaType.APPLICATION_OCTET_STREAM)
            .retrieve()
            .bodyToMono<ByteArray>()
            .block() ?: throw IllegalStateException("Failed to download asset from: $downloadUrl")
    }

    fun downloadSourceArchive(owner: String, repo: String, ref: String): ByteArray {
        val redirectUrl = webClient.get()
            .uri("/repos/{owner}/{repo}/zipball/{ref}", owner, repo, ref)
            .exchangeToMono { response ->
                if (!response.statusCode().is3xxRedirection) {
                    response.releaseBody().then(
                        reactor.core.publisher.Mono.error<String>(
                            IllegalStateException("expected redirect but got ${response.statusCode()} for $owner/$repo@$ref")
                        )
                    )
                } else {
                    val location = response.headers().asHttpHeaders().location
                        ?: return@exchangeToMono response.releaseBody().then(
                            reactor.core.publisher.Mono.error<String>(
                                IllegalStateException("redirect without location for $owner/$repo@$ref")
                            )
                        )
                    response.releaseBody().thenReturn(location.toString())
                }
            }
            .block() ?: throw IllegalStateException("failed to get source archive url: $owner/$repo@$ref")

        val httpClient = HttpClient.create().followRedirect(true)
        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { it.defaultCodecs().maxInMemorySize(100 * 1024 * 1024) }
            .build()
            .get()
            .uri(redirectUrl)
            .accept(MediaType.APPLICATION_OCTET_STREAM)
            .retrieve()
            .bodyToMono<ByteArray>()
            .block() ?: throw IllegalStateException("failed to download source archive: $owner/$repo@$ref")
    }

    fun getReleaseNote(owner: String, repo: String, tag: String): String? {
        return runCatching {
            val json = webClient.get()
                .uri("/repos/{owner}/{repo}/releases/tags/{tag}", owner, repo, tag)
                .retrieve()
                .bodyToMono<String>()
                .block() ?: return null
            val release = objectMapper.readTree(json)
            release["body"]?.asText()
        }.getOrNull()
    }

    companion object {
        private val GITHUB_RELEASE_URL_PATTERN =
            Regex("https?://github\\.com/([^/]+)/([^/]+)/releases/tag/([^/]+)")
        private val GITHUB_REPO_URL_PATTERN =
            Regex("https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$")

        fun parseGitHubReleaseUrl(url: String): GitHubReleaseInfo {
            val match = GITHUB_RELEASE_URL_PATTERN.matchEntire(url)
                ?: throw IllegalArgumentException("Invalid GitHub release URL: $url")
            return GitHubReleaseInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                tag = match.groupValues[3],
            )
        }

        fun parseGitHubRepoUrl(url: String): GitHubRepoInfo {
            val match = GITHUB_REPO_URL_PATTERN.matchEntire(url)
                ?: throw IllegalArgumentException("invalid GitHub repository URL: $url")
            return GitHubRepoInfo(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
            )
        }
    }
}
