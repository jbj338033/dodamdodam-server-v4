package com.b1nd.dodamdodam.file.infrastructure.build

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.build")
data class AppBuildProperties(
    val timeoutSeconds: Long = 300,
    val maxSourceSizeMb: Int = 100,
    val nodeImage: String = "node:20-alpine",
    val workDir: String = "/tmp/aid-builds",
)
