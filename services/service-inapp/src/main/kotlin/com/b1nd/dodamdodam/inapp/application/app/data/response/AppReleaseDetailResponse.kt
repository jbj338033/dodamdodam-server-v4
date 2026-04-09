package com.b1nd.dodamdodam.inapp.application.app.data.response

import com.b1nd.dodamdodam.inapp.domain.app.enumeration.AppStatusType
import java.time.LocalDateTime
import java.util.UUID

data class AppReleaseDetailResponse(
    val releaseId: UUID,
    val repositoryUrl: String,
    val ref: String,
    val memo: String?,
    val denyResult: String?,
    val status: AppStatusType,
    val enabled: Boolean,
    val buildLog: String?,
    val createdAt: LocalDateTime?,
    val modifiedAt: LocalDateTime?,
)
