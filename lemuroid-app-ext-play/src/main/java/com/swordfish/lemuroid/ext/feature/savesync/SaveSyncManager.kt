package com.swordfish.lemuroid.ext.feature.savesync

import android.content.Context
import android.preference.PreferenceManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.http.FileContent
import com.google.api.client.util.DateTime
import com.google.api.services.drive.Drive
import com.swordfish.lemuroid.common.kotlin.SharedPreferencesDelegates
import com.swordfish.lemuroid.common.kotlin.calculateMd5
import com.swordfish.lemuroid.ext.R
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import io.reactivex.Completable
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat

class SaveSyncManager(
    private val appContext: Context,
    private val directoriesManager: DirectoriesManager
) {
    private var lastSyncTimestamp: Long by SharedPreferencesDelegates.LongDelegate(
        PreferenceManager.getDefaultSharedPreferences(appContext),
        appContext.getString(R.string.pref_key_last_save_sync),
        0L
    )

    fun getSettingsActivity() = ActivateGoogleDriveActivity::class.java

    fun isSupported(): Boolean = true

    fun isConfigured(): Boolean = GoogleSignIn.getLastSignedInAccount(appContext) != null

    fun getLastSyncInfo(): String {
        val dateString = SimpleDateFormat.getDateTimeInstance().format(lastSyncTimestamp)
        return appContext.getString(R.string.gdrive_last_sync_completed, dateString)
    }

    fun getConfigInfo(): String {
        val email = GoogleSignIn.getLastSignedInAccount(appContext)?.email
        return if (email != null) {
            appContext.getString(R.string.gdrive_connected_summary, email)
        } else {
            appContext.getString(R.string.gdrive_connected_none_summary)
        }
    }

    fun sync(includeStates: Boolean) = Completable.fromAction {
        synchronized(SYNC_LOCK) {
            val drive = DriveFactory(appContext).create().toNullable() ?: return@fromAction

            syncLocalAndRemoteFolder(
                drive,
                getOrCreateAppDataFolder("saves"),
                directoriesManager.getSavesDirectory()
            )

            if (includeStates) {
                syncLocalAndRemoteFolder(
                    drive,
                    getOrCreateAppDataFolder("states"),
                    directoriesManager.getStatesDirectory()
                )
                syncLocalAndRemoteFolder(
                    drive,
                    getOrCreateAppDataFolder("state-previews"),
                    directoriesManager.getStatesPreviewDirectory()
                )
            }

            lastSyncTimestamp = System.currentTimeMillis()
        }
    }

    fun computeSavesSpace() = getSizeHumanReadable(directoriesManager.getSavesDirectory())

    fun computeStatesSpace() = getSizeHumanReadable(directoriesManager.getStatesDirectory())

    private fun getSizeHumanReadable(directory: File): String {
        val size = directory.walkBottomUp()
            .fold(0L) { acc, file -> acc + file.length() }
        return android.text.format.Formatter.formatShortFileSize(appContext, size)
    }

    private fun syncLocalAndRemoteFolder(drive: Drive, remoteFolderId: String, localFolder: File) {
        val remoteFiles = getRemoteFiles(drive, remoteFolderId)
        val remoteFilesMap = buildRemoteFileMap(remoteFiles)
        val localFilesMap = buildLocalFileMap(localFolder)

        (remoteFilesMap.keys + localFilesMap.keys).forEach {
            handleFileSync(drive, remoteFolderId, localFolder, remoteFilesMap[it], localFilesMap[it])
        }
    }

    private fun handleFileSync(
        drive: Drive,
        remoteParentFolderId: String,
        localParentFolder: File,
        remoteFile: com.google.api.services.drive.model.File?,
        localFile: File?
    ) {
        Timber.i("Handling file pair: $localFile $remoteFile")

        runCatching {
            if (remoteFile != null && localFile == null) {
                onRemoteOnly(localParentFolder, remoteFile, drive)
            } else if (remoteFile == null && localFile != null) {
                onLocalOnly(remoteParentFolderId, localFile, localParentFolder, drive)
            } else if (remoteFile != null && localFile != null) {
                if (areFileDifferent(remoteFile, localFile)) {
                    if (remoteFile.modifiedTime.value < localFile.lastModified()) {
                        onLocalUpdated(localFile, drive, remoteFile)
                    } else if (remoteFile.modifiedTime.value > localFile.lastModified()) {
                        onRemoteUpdated(drive, remoteFile, localFile)
                    }
                }
            }
        }
    }

    private fun areFileDifferent(
        remoteFile: com.google.api.services.drive.model.File,
        localFile: File
    ): Boolean {
        if (remoteFile.modifiedTime.value == localFile.lastModified())
            return false

        if (remoteFile.size.toLong() != localFile.length())
            return true

        return remoteFile.md5Checksum != localFile.calculateMd5()
    }

    private fun onLocalUpdated(
        localFile: File,
        drive: Drive,
        remoteFile: com.google.api.services.drive.model.File
    ) {
        Timber.i("Local file updated $localFile")

        val mediaContent = FileContent("application/x-binary", localFile)
        val metadata = com.google.api.services.drive.model.File()
        metadata.modifiedTime = DateTime(localFile.lastModified())
        drive.files().update(remoteFile.id, metadata, mediaContent)
            .execute()
    }

    private fun onLocalOnly(
        remoteParentFolderId: String,
        localFile: File,
        localParentFolder: File,
        drive: Drive
    ) {
        Timber.i("Local-only file detected $localFile")

        val metadata = com.google.api.services.drive.model.File()
        metadata.parents = listOf(remoteParentFolderId)
        metadata.name = localFile.name
        metadata.appProperties = mapOf(
            GDRIVE_PROPERTY_LOCAL_PATH to localFile.toRelativeString(
                localParentFolder
            )
        )
        metadata.modifiedTime = DateTime(localFile.lastModified())
        val mediaContent = FileContent("application/x-binary", localFile)
        drive.files().create(metadata, mediaContent)
            .setFields("id")
            .execute()
    }

    private fun onRemoteOnly(
        localParentFolder: File,
        remoteFile: com.google.api.services.drive.model.File,
        drive: Drive
    ) {
        Timber.i("Remote only file detected $remoteFile")
        val outputFile = File(
            localParentFolder,
            remoteFile.appProperties[GDRIVE_PROPERTY_LOCAL_PATH]!!
        ).apply {
            parentFile?.mkdirs()
        }
        downloadToLocal(drive, remoteFile, outputFile)
    }

    private fun onRemoteUpdated(
        drive: Drive,
        remoteFile: com.google.api.services.drive.model.File,
        localFile: File
    ) {
        Timber.i("Remote file updated $remoteFile")
        downloadToLocal(drive, remoteFile, localFile)
    }

    private fun downloadToLocal(
        drive: Drive,
        remoteFile: com.google.api.services.drive.model.File,
        localFile: File
    ) {
        if (remoteFile.size == 0) return
        Timber.i("Downloading file to $localFile")
        drive.files()
            .get(remoteFile.id)
            .executeMediaAndDownloadTo(localFile.outputStream())
        localFile.setLastModified(remoteFile.modifiedTime.value)
    }

    private fun buildRemoteFileMap(
        remoteFiles: Sequence<com.google.api.services.drive.model.File>
    ): Map<String, com.google.api.services.drive.model.File> {
        return remoteFiles
            .filter { it.appProperties?.get(GDRIVE_PROPERTY_LOCAL_PATH) != null }
            .map { it.appProperties?.get(GDRIVE_PROPERTY_LOCAL_PATH)!! to it }
            .toMap()
    }

    private fun buildLocalFileMap(folder: File): Map<String, File> {
        return folder
            .walkBottomUp()
            .filter { it.exists() && !it.isDirectory && it.length() > 0 }
            .map { it.toRelativeString(folder) to it }
            .toMap()
    }

    private fun getOrCreateAppDataFolder(folderName: String): String {
        val drive = DriveFactory(appContext).create().toNullable()
            ?: throw UnsupportedOperationException()

        val query = drive.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$folderName' and mimeType = 'application/vnd.google-apps.folder'")
            .setFields("files(id)")
            .execute()

        if (query.files.size > 0) {
            return query.files[0].id
        }

        val metadata = com.google.api.services.drive.model.File()
        metadata.parents = listOf("appDataFolder")
        metadata.name = folderName
        metadata.mimeType = "application/vnd.google-apps.folder"

        val file = drive.files().create(metadata)
            .setFields("id")
            .execute()

        return file.id
    }

    private fun getRemoteFiles(
        drive: Drive,
        folderId: String
    ): Sequence<com.google.api.services.drive.model.File> {
        var pageToken: String? = null
        return sequence {
            do {
                val query =
                    """
                    '$folderId' in parents and trashed = false and mimeType = 'application/x-binary'
                    """.trimIndent()

                val fields =
                    """
                    nextPageToken,
                    files(id, name, size, appProperties, modifiedTime, parents, md5Checksum)
                    """.trimIndent()

                val result = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ(query)
                    .setFields(fields)
                    .setPageToken(pageToken)
                    .execute()

                yieldAll(result.files)
                pageToken = result.nextPageToken
            } while (pageToken != null)
        }
    }

    companion object {
        const val GDRIVE_PROPERTY_LOCAL_PATH = "localPath"
        private val SYNC_LOCK = Object()
    }
}
