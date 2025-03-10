package co.aospa.hub.utils

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.RecoverySystem
import android.os.UpdateEngine
import android.util.Log
import co.aospa.hub.GlobalConstants
import co.aospa.hub.utils.FileUtils.copyFile
import co.aospa.hub.utils.FileUtils.getZipEntryOffset
import co.aospa.hub.utils.FileUtils.isEncrypted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipFile

class UpdateService : Service() {
    companion object {
        private var instance: UpdateService? = null

        private const val TAG = "UpdateService"

        fun getInstance(): UpdateService {
            if (instance == null) {
                instance = UpdateService()
            }
            return instance!!
        }
    }

    private val _updateStatusFlow = MutableStateFlow("")
    val updateStatusFlow: StateFlow<String> = _updateStatusFlow

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val file = intent?.getSerializableExtra("updateFile", File::class.java)
        val packageSize = intent?.getLongExtra("packageSize", 0)
        val context = applicationContext

        if (file != null) {
            updateStatus("Preparing update...")

            val updateHandler = UpdateHandler(context, file, packageSize)
            updateHandler.prepareUpdate()
        } else {
            updateStatus("Error: File is null or invalid")
            Log.e(TAG, "File is null or invalid.")
        }
        return START_STICKY
    }

    private fun updateStatus(status: String) {
        _updateStatusFlow.value = status
    }

    class UpdateHandler(
        private val context: Context,
        private val file: File,
        private val packageSize: Long?
    ) {
        fun prepareUpdate() {
            try {
                val offset = getPayloadOffset(file)
                val headerKeyValuePairs = readHeaderProperties(file)
                if (offset >= 0) {
                    startFlashing(file, offset, headerKeyValuePairs)
                } else {
                    Log.e(TAG, "Invalid offset: $offset")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare update", e)
            }
        }

        private fun getPayloadOffset(file: File): Long {
            return try {
                ZipFile(file).use { zipFile ->
                    getZipEntryOffset(zipFile, GlobalConstants.AB_PAYLOAD_BIN_PATH)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get payload offset", e)
                -1
            }
        }

        private fun readHeaderProperties(file: File): Array<String> {
            return try {
                ZipFile(file).use { zipFile ->
                    val payloadPropEntry =
                        zipFile.getEntry(GlobalConstants.AB_PAYLOAD_PROPERTIES_PATH)
                    zipFile.getInputStream(payloadPropEntry).bufferedReader().use { br ->
                        br.readLines().toTypedArray()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read header properties", e)
                emptyArray()
            }
        }

        private fun startFlashing(file: File, offset: Long, headerKeyValuePairs: Array<String>) {
            try {
                if (DeviceUtils().isABDevice()) {
                    val updateEngine = UpdateEngine()
                    updateEngine.setPerformanceMode(true)
                    Log.i(TAG, "Performance mode set correctly")
                    if (packageSize != null) {
                        updateEngine.applyPayload(
                            file.absolutePath,
                            offset,
                            packageSize,
                            headerKeyValuePairs
                        )
                    }
                } else {
                    if (isEncrypted(context, file)) {
                        decryptAndInstall(file)
                    } else {
                        installPackageAOnly(file)
                    }
                }
            } catch (e: Exception) {
                Log.i("Flashing exception", e.toString())
            }
        }

        private fun installPackageAOnly(file: File) {
            try {
                RecoverySystem.installPackage(context, file)
            } catch (e: Exception) {
                Log.i("A only update", "failed", e)
            }
        }

        @Synchronized
        private fun decryptAndInstall(file: File) {
            val decryptedFilePath = "${file.absolutePath}.decrypt"
            val decryptedFile = File(decryptedFilePath)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    copyFile(file, decryptedFile)
                    try {
                        val perms = setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.GROUP_READ
                        )
                        Files.setPosixFilePermissions(file.toPath(), perms)
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to set file permissions", e)
                    }

                    if (!isActive) {
                        decryptedFile.delete()
                    } else {
                        installPackageAOnly(decryptedFile)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Could not copy update", e)
                    decryptedFile.delete()
                }
            }
        }
    }
}
