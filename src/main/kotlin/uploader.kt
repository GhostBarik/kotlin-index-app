import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.io.File
import java.io.FileReader
import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.exists

private val logger = KotlinLogging.logger {}

typealias FileExtension = String
typealias Tokenizer = ((Sequence<String>) -> MapOfTokens)
typealias TokenizerMap = Map<FileExtension, Tokenizer>

/**
 * Uploader provides simple interface for managing (create/update/delete) text files in our database.
 * Each file can be imported from multiple sources (disk file, String, InputStream)
 * After file is successfully imported into database, it is parsed by tokenizer
 * (user can configure different tokenizers for different file extensions)
 * which analyzes the file, extracts tokes from it and sends them to be asynchronously processed by the Index Actor.
 * Index Actor then synchronizes its own private state (index) based on new tokens, sent by uploader.
 *
 * Multiple upload request files can be run simultaneously (all upload requests are processed on separate I/O dispatcher,
 * by default Dispatchers.IO is used, but user is free to override it with its own custom dispatcher).
 * However it is not possible to update file with the same database name simultaneously.
 * Optimistic concurrency approach is taken: if there are multiple requests to update the same file, only first is
 * being processed and others are immediately cancelled (the exception [FileIsAlreadyInUseException] is thrown )
 * It's up to the user to retry again in case of such error.
 *
 * There are multiple ways of how we can create text files in database:
 * 1. by uploading some other file from FS
 * 2. by reading from InputStream
 * 3. by reading ordinary String
 *
 * When user creates/updates text file in database, he have to provide its unique path name in the following format:
 * {catalogue}/{file}.{extension}
 *
 * There can be any (0 or more) number of nested catalogues, e.g. "A/B/C/file.txt".
 * Based on the extension name (e.g. ".txt", ".kt", ".json"), the appropriate tokenizer is taken to
 * process the file. If no tokenizer is found for given file extension,
 * the exception [TokenizerNotFoundException] is being thrown
 *
 *
 * @param tokenizerMap tokenizer map contain specific tokenizers for each supported file format
 * @param workingDirectory working directory is used as a root directory for database specific files
 * @param indexActorMailbox channel for the Index Actor to synchronize state changes between Uploader and Index Actor
 * @param ioDispatcher I/O dispatcher used for running I/O heavy operations (like accessing files on filesystem)
 */
class Uploader private constructor (
    private val tokenizerMap: TokenizerMap,
    private val workingDirectory: File,
    private val indexActorMailbox: SendChannel<IndexActorInputMessage>,
    private val ioDispatcher: CoroutineDispatcher
) {

    // mutex and map are used in conjunction to guard against
    // concurrent updates on the same files in database
    private val fileLockMapMutex: Mutex = Mutex()
    private val fileLockMap: MutableMap<DatabaseRelativePath, Boolean> = mutableMapOf()

    companion object Factory {

        /**
         * Factory function for creating Uploader instance.
         */
        suspend fun initUploader(
            tokenizerMap: TokenizerMap,
            workingDirectory: File,
            indexActorMailbox: SendChannel<IndexActorInputMessage>,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): Uploader {

            withContext(ioDispatcher) {

                logger.info("[UPLOADER]: Initializing Uploader...")

                if (!workingDirectory.toPath().exists()) {
                    throw Exception(
                        "Working directory ${workingDirectory.absolutePath} does not exist on " +
                        "drive, cannot create Uploader."
                    )
                }

                // enumerate already existing files in database and add to index
                fun databaseFilesDirectory(): File {
                    return File("${workingDirectory.absolutePath}/files")
                }

                // if there are some files in database -> iterate over them and update index
                if (databaseFilesDirectory().exists()) {

                    databaseFilesDirectory()
                        .walkTopDown()
                        .filter { it.isFile }
                        .forEach { file ->

                            val databaseFile = file.relativeTo(databaseFilesDirectory().parentFile)
                            val databaseRelativeFile = file.relativeTo(databaseFilesDirectory())

                            logger.info("[UPLOADER]: found existing file, add to index: ${databaseFile.path}")

                            val databaseRelativePath = DatabaseRelativePath.create(
                                // convert '\' part of the path to '/'
                                databaseRelativeFile.path.replace("\\", "/")
                            )

                            val tokenizer = tokenizerMap[databaseRelativePath.fileExtension]!!

                            processFileAndUpdateIndex(
                                databaseFile.absoluteFile, databaseRelativePath,
                                tokenizer, indexActorMailbox
                            )
                        }
                }
            }

            return Uploader(tokenizerMap, workingDirectory, indexActorMailbox, ioDispatcher)
        }
    }

    suspend fun getListOfDatabaseFiles(): List<String> {
        val root = File("${workingDirectory.absolutePath}/files")
        return withContext(ioDispatcher) {
            root
                .walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(root).path }
                .map { it.replace("\\", "/") }
                .toList()
        }
    }

    /**
     * Upload file from [InputStream] into Database.
     *
     * @param inputStream input source of text data
     * @param databasePath specify file name, extension and (optionally) name of the catalog where it will be stored
     */
    suspend fun uploadFromInputStream(inputStream: InputStream, databasePath: DatabaseRelativePath) {

        // this should be outside of try block because if locking file fails
        // we know that another coroutine is holding this lock and we should
        // not release it from here (holding coroutine will release it instead)
        lockDatabaseFile(databasePath)

        try {
            withContext(ioDispatcher) {

                if (!tokenizerMap.containsKey(databasePath.fileExtension)) {
                    throw TokenizerNotFoundException(
                        "Tokenizer for file extension '${databasePath.fileExtension}' is not found."
                    )
                }

                // create database file
                val databaseFile = File("${workingDirectory.absolutePath}/files/${databasePath.path}")

                // create file directory if it does not exists yet
                databaseFile.parentFile.mkdirs()
                databaseFile.createNewFile()

                // write to file from input stream
                databaseFile.printWriter().use { out ->
                    inputStream.bufferedReader().useLines {
                        lines -> lines.forEach { line -> out.println(line) }
                    }
                }

                // process file and update index
                val tokenizer = tokenizerMap[databasePath.fileExtension]!!
                processFileAndUpdateIndex(databaseFile, databasePath, tokenizer, indexActorMailbox)
            }
        } finally {
            unlockDatabaseFile(databasePath)
        }
    }

    /**
     * Create and upload file from [String] source into Database.
     *
     * @param s input source of text data (plain String)
     * @param databasePath specify file name, extension and (optionally) name of the catalog where it will be stored
     */
    suspend fun uploadFromString(s: String, databasePath: DatabaseRelativePath) {

        // this should be outside of try block because if locking file fails
        // we know that another coroutine is holding this lock and we should
        // not release it from here (holding coroutine will release it instead)
        lockDatabaseFile(databasePath)

        try {

            withContext(ioDispatcher) {

                if (!tokenizerMap.containsKey(databasePath.fileExtension)) {
                    throw TokenizerNotFoundException(
                        "Tokenizer for file extension '${databasePath.fileExtension}' is not found."
                    )
                }

                // create database file
                val databaseFile = File("${workingDirectory.absolutePath}/files/${databasePath.path}")

                // create file directory if it does not exists yet
                databaseFile.parentFile.mkdirs()
                databaseFile.createNewFile()

                // write to file from string
                databaseFile.printWriter().use { out -> out.println(s) }

                // process file and update index
                val tokenizer = tokenizerMap[databasePath.fileExtension]!!
                processFileAndUpdateIndex(databaseFile, databasePath, tokenizer, indexActorMailbox)
            }
        } finally {
            unlockDatabaseFile(databasePath)
        }
    }


    /**
     * Create and upload file from [File] source into Database.
     *
     * @param inputFile file on disk used as a source for data
     * @param databasePath specify file name, extension and (optionally) name of the catalog where it will be stored
     */
    suspend fun uploadFile(inputFile: File, databasePath: DatabaseRelativePath) {

        // this should be outside of try block because if locking file fails
        // we know that another coroutine is holding this lock and we should
        // not release it from here (holding coroutine will release it instead)
        lockDatabaseFile(databasePath)

        try {

            withContext(ioDispatcher) {

                if (!inputFile.exists()) {
                    throw FileNotFoundException(
                        "Provided input file '${inputFile.absolutePath}' does not exists."
                    )
                }

                if (!tokenizerMap.containsKey(databasePath.fileExtension)) {
                    throw TokenizerNotFoundException(
                        "Tokenizer for file extension '${databasePath.fileExtension}' is not found."
                    )
                }

                logger.info("[UPLOADER]: uploading file: ${inputFile.absolutePath} into DB[${databasePath.path}]")

                // upload input file to our database
                uploadFileToDatabase(inputFile, databasePath)

                // process file and update index
                val tokenizer = tokenizerMap[databasePath.fileExtension]!!
                processFileAndUpdateIndex(inputFile, databasePath, tokenizer, indexActorMailbox)
            }

        } finally {
            unlockDatabaseFile(databasePath)
        }
    }

    /**
     * Remove existing file from Database.
     *
     * @param databasePath specify full file path in DB format
     */
    suspend fun removeFileFromDatabase(databasePath: DatabaseRelativePath) {

        // this should be outside of try block because if locking file fails
        // we know that another coroutine is holding this lock and we should
        // not release it from here (holding coroutine will release it instead)
        lockDatabaseFile(databasePath)

        try {

            withContext(ioDispatcher) {

                val fullPath = "${workingDirectory.absolutePath}/files/${databasePath.path}"
                logger.info("[UPLOADER]: Deleting file: $fullPath")

                val file = File(fullPath)
                if (!file.exists()) {
                    throw FileNotFoundException("Cannot remove file: ${file.absolutePath}. Files does not exist.")
                }
                Files.delete(file.toPath())

                // send remove message to IndexActor
                indexActorMailbox.send(RemoveFileFromIndex(databasePath))
            }
        } finally {
            unlockDatabaseFile(databasePath)
        }
    }

    private suspend fun lockDatabaseFile(databasePath: DatabaseRelativePath) {

        // lock map and check if path where user wants to upload the file is currently in use
        // if the file is already being used (i.e. some other concurrent upload is being performed)
        // the method throws exception, so user is free to choose on how to react on file path being locked at this moment
        // (i.e. method throws immediately instead of waiting on potentially long blocking request)
        fileLockMapMutex.withLock {

            logger.info("[UPLOADER]: locking $databasePath")

            // check if path is already in map or not
            if (fileLockMap.containsKey(databasePath)) {
                throw FileIsAlreadyInUseException("file ${databasePath.path} is in use, please try again later.")
            }

            // current file path is not being used, so just put the path to the lock map
            // (so we effectively 'lock' current path to prevent concurrent access
            // from other upload requests)
            fileLockMap.put(databasePath, true)
        }
    }

    private suspend fun unlockDatabaseFile(databasePath: DatabaseRelativePath) {

        // remove lock from lock map (so other requests can be performed on this file)
        fileLockMapMutex.withLock {
            logger.info("[UPLOADER]: unlocking $databasePath")
            fileLockMap.remove(databasePath)
        }
    }

    private fun uploadFileToDatabase(inputFile: File, databaseRelativePath: DatabaseRelativePath) {
        inputFile.copyTo(
            File(
                "${workingDirectory.absolutePath}/files/${databaseRelativePath.path}"
            ), overwrite = true
        )
    }
}

/**
 * This suspended function is shared between Uploader and its companion object (Factory).
 * Because it involves blocking I/O it should be always called inside dedicated I/O dispatcher.
 */
private suspend fun processFileAndUpdateIndex(
    inputFile: File,
    databasePath: DatabaseRelativePath,
    tokenizer: Tokenizer,
    indexActorMailbox: SendChannel<IndexActorInputMessage>) {

    // process file and extract tokens
    val reader = FileReader(inputFile)

    // parse file by appropriate tokenizer (depending on the file extension) and extract tokens
    val tokens = reader.useLines {  lines -> tokenizer(lines) }

    // update index (send message to index actor)
    indexActorMailbox.send(UpdateIndex(databasePath, tokens))
}

