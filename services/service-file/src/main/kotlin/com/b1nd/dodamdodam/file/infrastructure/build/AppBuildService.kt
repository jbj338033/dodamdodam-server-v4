package com.b1nd.dodamdodam.file.infrastructure.build

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Volume
import com.github.dockerjava.api.model.WaitResponse
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Component
class AppBuildService(
    private val properties: AppBuildProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun buildFromSource(sourceZipBytes: ByteArray): BuildResult {
        if (sourceZipBytes.size > properties.maxSourceSizeMb.toLong() * 1024 * 1024) {
            return BuildResult(false, null, "source archive exceeds max size: ${properties.maxSourceSizeMb}MB")
        }

        val workDir = Path.of(properties.workDir, System.currentTimeMillis().toString())
        val sourceDir = workDir.resolve("source")

        try {
            Files.createDirectories(sourceDir)
            extractZipball(sourceZipBytes, sourceDir)

            val packageManager = detectPackageManager(sourceDir)
            log.info("Detected package manager: {}", packageManager)

            if (!Files.exists(sourceDir.resolve("package.json"))) {
                return BuildResult(false, null, "package.json not found in repository root")
            }

            val buildLog = runDockerBuild(sourceDir, packageManager)

            val distDir = findDistDir(sourceDir)
                ?: return BuildResult(false, null, "build output directory not found (tried dist/, build/, out/)\n\n$buildLog")

            val distZip = zipDirectory(distDir)
            return BuildResult(true, distZip, buildLog)
        } catch (e: Exception) {
            log.error("Build failed", e)
            return BuildResult(false, null, "build failed: ${e.message}")
        } finally {
            runCatching { workDir.toFile().deleteRecursively() }
        }
    }

    private fun extractZipball(zipBytes: ByteArray, targetDir: Path) {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var rootPrefix: String? = null
            var entry = zis.nextEntry
            while (entry != null) {
                if (rootPrefix == null && entry.name.contains("/")) {
                    rootPrefix = entry.name.substringBefore("/") + "/"
                }
                val relativePath = if (rootPrefix != null && entry.name.startsWith(rootPrefix)) {
                    entry.name.removePrefix(rootPrefix)
                } else {
                    entry.name
                }
                if (relativePath.isBlank()) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                val target = targetDir.resolve(relativePath).normalize()
                if (!target.startsWith(targetDir.normalize())) {
                    throw SecurityException("zip slip detected: $relativePath")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.write(target, zis.readBytes())
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun detectPackageManager(sourceDir: Path): PackageManager {
        return when {
            Files.exists(sourceDir.resolve("pnpm-lock.yaml")) -> PackageManager.PNPM
            Files.exists(sourceDir.resolve("yarn.lock")) -> PackageManager.YARN
            else -> PackageManager.NPM
        }
    }

    private fun runDockerBuild(sourceDir: Path, packageManager: PackageManager): String {
        val config = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
        val httpClient = ApacheDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .build()
        val dockerClient: DockerClient = DockerClientImpl.getInstance(config, httpClient)

        try {
            pullImageIfNeeded(dockerClient)

            val installCmd = when (packageManager) {
                PackageManager.PNPM -> "corepack enable && pnpm install --no-frozen-lockfile"
                PackageManager.YARN -> "yarn install"
                PackageManager.NPM -> "npm install"
            }
            val buildCmd = when (packageManager) {
                PackageManager.PNPM -> "pnpm run build"
                PackageManager.YARN -> "yarn build"
                PackageManager.NPM -> "npm run build"
            }

            val container = dockerClient.createContainerCmd(properties.nodeImage)
                .withCmd("sh", "-c", "cd /app && $installCmd && $buildCmd")
                .withHostConfig(
                    HostConfig.newHostConfig()
                        .withBinds(Bind(sourceDir.toAbsolutePath().toString(), Volume("/app")))
                        .withMemory(512 * 1024 * 1024L)
                        .withCpuCount(2)
                )
                .withWorkingDir("/app")
                .exec()

            val containerId = container.id

            try {
                dockerClient.startContainerCmd(containerId).exec()

                val logBuilder = StringBuffer()
                val logLatch = CountDownLatch(1)
                dockerClient.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .exec(object : ResultCallback<Frame> {
                        override fun onStart(closeable: Closeable?) {}
                        override fun onNext(frame: Frame) {
                            logBuilder.append(String(frame.payload))
                        }
                        override fun onError(throwable: Throwable) { logLatch.countDown() }
                        override fun onComplete() { logLatch.countDown() }
                        override fun close() {}
                    })

                val waitLatch = CountDownLatch(1)
                var exitCode = -1
                dockerClient.waitContainerCmd(containerId)
                    .exec(object : ResultCallback<WaitResponse> {
                        override fun onStart(closeable: Closeable?) {}
                        override fun onNext(response: WaitResponse) {
                            exitCode = response.statusCode
                            waitLatch.countDown()
                        }
                        override fun onError(throwable: Throwable) { waitLatch.countDown() }
                        override fun onComplete() { waitLatch.countDown() }
                        override fun close() {}
                    })

                val completed = waitLatch.await(properties.timeoutSeconds, TimeUnit.SECONDS)
                logLatch.await(5, TimeUnit.SECONDS)

                val buildLog = logBuilder.toString().takeLast(10000)

                if (!completed) {
                    runCatching { dockerClient.killContainerCmd(containerId).exec() }
                    throw IllegalStateException("build timeout exceeded (${properties.timeoutSeconds}s)\n\n$buildLog")
                }

                if (exitCode != 0) {
                    throw IllegalStateException("build exited with code $exitCode\n\n$buildLog")
                }

                return buildLog
            } finally {
                runCatching {
                    dockerClient.removeContainerCmd(containerId).withForce(true).exec()
                }
            }
        } finally {
            runCatching { httpClient.close() }
        }
    }

    private fun pullImageIfNeeded(dockerClient: DockerClient) {
        runCatching {
            dockerClient.inspectImageCmd(properties.nodeImage).exec()
        }.onFailure {
            log.info("Pulling image: {}", properties.nodeImage)
            dockerClient.pullImageCmd(properties.nodeImage)
                .start()
                .awaitCompletion(120, TimeUnit.SECONDS)
        }
    }

    private fun findDistDir(sourceDir: Path): Path? {
        val candidates = listOf("dist", "build", "out")
        return candidates.map { sourceDir.resolve(it) }
            .firstOrNull { Files.isDirectory(it) }
    }

    private fun zipDirectory(dir: Path): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            Files.walk(dir)
                .filter { Files.isRegularFile(it) }
                .forEach { file ->
                    val relativePath = dir.relativize(file).toString()
                    zos.putNextEntry(ZipEntry(relativePath))
                    Files.newInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
        return baos.toByteArray()
    }
}

data class BuildResult(
    val success: Boolean,
    val distZipBytes: ByteArray?,
    val buildLog: String,
)

enum class PackageManager {
    NPM, PNPM, YARN
}
