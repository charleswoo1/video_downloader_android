package com.charleswoo1.videodownloader.data.download

import android.content.Context
import android.os.Build
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object RuntimeDiagnosticsHelper {
    private const val TAG = "RuntimeDiagnostics"

    @Volatile
    private var cachedDiagnostics: RuntimeDiagnostics? = null

    fun getCachedDiagnostics(): RuntimeDiagnostics? = cachedDiagnostics

    suspend fun inspectRuntime(context: Context): RuntimeDiagnostics = inspect(context)

    suspend fun inspect(context: Context): RuntimeDiagnostics = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val ytdlpVer = try {
            YoutubeDL.getInstance().version(context) ?: "2026.08.30.232658"
        } catch (e: Exception) {
            "error (${e.message})"
        }

        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val pythonDir = File(context.noBackupFilesDir, "youtubedl-android/packages/python")
        val ffmpegDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")

        val pythonBin = File(nativeLibDir, "libpython.so")
        val qjsCli = File(nativeLibDir, "libqjs-cli.so")
        val ffmpegBin = File(nativeLibDir, "libffmpeg.so")

        var pythonVer = "unknown"
        var curlCffiOk = false
        var curlCffiVer: String? = null
        val extraDetails = mutableListOf<String>()

        if (pythonBin.exists()) {
            try {
                val pyEnv = mutableMapOf<String, String>()
                pyEnv["PYTHONHOME"] = "${pythonDir.absolutePath}/usr"
                pyEnv["LD_LIBRARY_PATH"] = "${pythonDir.absolutePath}/usr/lib:${nativeLibDir.absolutePath}"
                pyEnv["SSL_CERT_FILE"] = "${pythonDir.absolutePath}/usr/lib/python3.14/site-packages/certifi/cacert.pem"
                pyEnv["OPENSSL_MODULES"] = "${pythonDir.absolutePath}/usr/lib/ossl-modules"
                pyEnv["TMPDIR"] = context.cacheDir.absolutePath

                // Test python version
                val pyVerProcess = ProcessBuilder(
                    pythonBin.absolutePath,
                    "-c",
                    "import sys; print(sys.version.split()[0])"
                ).apply {
                    environment().putAll(pyEnv)
                    redirectErrorStream(true)
                }.start()

                if (pyVerProcess.waitFor(5, TimeUnit.SECONDS)) {
                    val out = pyVerProcess.inputStream.bufferedReader().readText().trim()
                    if (pyVerProcess.exitValue() == 0 && out.isNotBlank()) {
                        pythonVer = out
                    } else {
                        extraDetails.add("Python ver exit ${pyVerProcess.exitValue()}: $out")
                    }
                } else {
                    pyVerProcess.destroy()
                    extraDetails.add("Python ver timeout")
                }

                // Test curl_cffi availability
                val curlProcess = ProcessBuilder(
                    pythonBin.absolutePath,
                    "-c",
                    "import curl_cffi; print(curl_cffi.__version__)"
                ).apply {
                    environment().putAll(pyEnv)
                    redirectErrorStream(true)
                }.start()

                if (curlProcess.waitFor(5, TimeUnit.SECONDS)) {
                    val out = curlProcess.inputStream.bufferedReader().readText().trim()
                    if (curlProcess.exitValue() == 0 && out.isNotBlank()) {
                        curlCffiOk = true
                        curlCffiVer = out
                    } else {
                        extraDetails.add("curl_cffi import failed (${curlProcess.exitValue()}): $out")
                    }
                } else {
                    curlProcess.destroy()
                    extraDetails.add("curl_cffi check timeout")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed running Python diagnostics", e)
                extraDetails.add("Python check error: ${e.message}")
            }
        } else {
            extraDetails.add("libpython.so not found in $nativeLibDir")
        }

        // Test FFmpeg
        var ffmpegVer = "8.1.2"
        if (ffmpegBin.exists()) {
            try {
                val ffProcess = ProcessBuilder(ffmpegBin.absolutePath, "-version").apply {
                    environment()["LD_LIBRARY_PATH"] = "${ffmpegDir.absolutePath}/usr/lib:${nativeLibDir.absolutePath}"
                    redirectErrorStream(true)
                }.start()
                if (ffProcess.waitFor(5, TimeUnit.SECONDS)) {
                    val firstLine = ffProcess.inputStream.bufferedReader().readLine() ?: ""
                    if (firstLine.contains("ffmpeg version", ignoreCase = true)) {
                        ffmpegVer = firstLine.substringAfter("ffmpeg version").trim().substringBefore(" ")
                    }
                } else {
                    ffProcess.destroy()
                }
            } catch (e: Exception) {
                Log.w(TAG, "FFmpeg version check fallback", e)
            }
        }

        // Test QuickJS
        var quickJsOk = false
        if (qjsCli.exists()) {
            try {
                val qjsProcess = ProcessBuilder(qjsCli.absolutePath, "-e", "console.log('qjs-ok')").apply {
                    environment()["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
                    redirectErrorStream(true)
                }.start()
                if (qjsProcess.waitFor(5, TimeUnit.SECONDS)) {
                    val out = qjsProcess.inputStream.bufferedReader().readText().trim()
                    quickJsOk = (qjsProcess.exitValue() == 0 && out.contains("qjs-ok"))
                } else {
                    qjsProcess.destroy()
                }
            } catch (_: Exception) {}
        }

        val diag = RuntimeDiagnostics(
            youtubedlSource = "PR #361 (76ed1bf9) + PR #359 (a505022a)",
            ytdlpVersion = ytdlpVer,
            pythonVersion = pythonVer,
            curlCffiAvailable = curlCffiOk,
            curlCffiVersion = curlCffiVer,
            ffmpegVersion = ffmpegVer,
            quickJsAvailable = quickJsOk,
            abi = abi,
            details = if (extraDetails.isNotEmpty()) extraDetails.joinToString("; ") else null
        )

        cachedDiagnostics = diag
        Log.i(TAG, diag.toFormattedReport())
        diag
    }
}
