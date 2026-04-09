package com.b1nd.dodamdodam.file.infrastructure.kafka.producer

import com.b1nd.dodamdodam.core.kafka.constants.KafkaTopics
import com.b1nd.dodamdodam.core.kafka.event.app.AppBuildResultEvent
import com.b1nd.dodamdodam.core.kafka.producer.KafkaMessageProducer
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AppBuildResultEventProducer(
    private val kafkaMessageProducer: KafkaMessageProducer,
) {
    fun publish(appPublicId: UUID, releasePublicId: UUID, success: Boolean, buildLog: String?) {
        kafkaMessageProducer.send(
            KafkaTopics.APP_BUILD_RESULT,
            appPublicId.toString(),
            AppBuildResultEvent(
                appPublicId = appPublicId,
                releasePublicId = releasePublicId,
                success = success,
                buildLog = buildLog,
            )
        )
    }
}
