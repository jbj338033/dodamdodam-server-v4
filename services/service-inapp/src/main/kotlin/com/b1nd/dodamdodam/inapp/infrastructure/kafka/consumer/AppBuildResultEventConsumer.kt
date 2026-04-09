package com.b1nd.dodamdodam.inapp.infrastructure.kafka.consumer

import com.b1nd.dodamdodam.core.kafka.constants.KafkaTopics
import com.b1nd.dodamdodam.core.kafka.event.app.AppBuildResultEvent
import com.b1nd.dodamdodam.inapp.domain.app.service.AppService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AppBuildResultEventConsumer(
    private val appService: AppService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [KafkaTopics.APP_BUILD_RESULT],
        groupId = "service-inapp-build",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    fun consume(event: AppBuildResultEvent) {
        log.info("Received build result: release={}, success={}", event.releasePublicId, event.success)
        appService.handleBuildResult(event.releasePublicId, event.success, event.buildLog)
    }
}
