package com.b1nd.dodamdodam.file.infrastructure.kafka.consumer

import com.b1nd.dodamdodam.core.github.client.GitHubClient
import com.b1nd.dodamdodam.core.kafka.constants.KafkaTopics
import com.b1nd.dodamdodam.core.kafka.event.app.AppReleaseActivatedEvent
import com.b1nd.dodamdodam.file.infrastructure.build.AppBuildService
import com.b1nd.dodamdodam.file.infrastructure.kafka.producer.AppBuildResultEventProducer
import com.b1nd.dodamdodam.file.infrastructure.s3.S3ReleaseUploader
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class AppReleaseActivatedEventConsumer(
    private val gitHubClient: GitHubClient,
    private val appBuildService: AppBuildService,
    private val s3ReleaseUploader: S3ReleaseUploader,
    private val buildResultEventProducer: AppBuildResultEventProducer,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [KafkaTopics.APP_RELEASE_ACTIVATED],
        groupId = "service-file-release",
        containerFactory = "kafkaListenerContainerFactory"
    )
    fun consumeReleaseActivated(event: AppReleaseActivatedEvent) {
        log.info("Received release activated event: app={}, release={}", event.appPublicId, event.releasePublicId)

        var buildLog: String? = null
        var success = false

        try {
            val repoInfo = GitHubClient.parseGitHubRepoUrl(event.repositoryUrl)
            val sourceZipBytes = gitHubClient.downloadSourceArchive(
                repoInfo.owner,
                repoInfo.repo,
                event.ref,
            )

            val buildResult = appBuildService.buildFromSource(sourceZipBytes)
            buildLog = buildResult.buildLog

            if (buildResult.success && buildResult.distZipBytes != null) {
                s3ReleaseUploader.uploadReleaseArchive(buildResult.distZipBytes, event.appPublicId, event.releasePublicId)
                success = true
                log.info("Build and deploy succeeded: app={}, release={}", event.appPublicId, event.releasePublicId)
            } else {
                log.warn("Build failed: app={}, release={}", event.appPublicId, event.releasePublicId)
            }
        } catch (e: Exception) {
            log.error("Failed to build release: app={}, release={}", event.appPublicId, event.releasePublicId, e)
            buildLog = (buildLog ?: "") + "\n\n${e.message}"
        }

        buildResultEventProducer.publish(
            event.appPublicId,
            event.releasePublicId,
            success,
            buildLog?.takeLast(10000),
        )
    }
}
