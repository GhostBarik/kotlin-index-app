package tokenizers

import DatabaseRelativePath
import FileIsAlreadyInUseException
import QueryIndex
import Token
import TokenLocationsMap
import TokenizerMap
import Uploader
import createIndexActor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import simpleJavaCodeTokenizer
import simpleTextTokenizer
import java.io.File


@ObsoleteCoroutinesApi
class UploaderTests {

    private val tokenizerMap: TokenizerMap = mapOf(
        "txt" to ::simpleTextTokenizer,
        "java" to ::simpleJavaCodeTokenizer
    )

    private fun clearDatabase() {

        // get current working directory
        val cwd = File("")

        // clear /files subdirectory if it exists already
        val databasePath = File("${cwd.absolutePath}/files")
        println("[TEST]: database path: ${databasePath.absolutePath}")

        if (databasePath.exists()) {
            println("[TEST]: clear directory: ${databasePath.absolutePath}")
            databasePath.deleteRecursively()
        }
    }

    @BeforeEach
    fun testInit() {
        clearDatabase()
    }

    @AfterEach
    fun testCleanup() {
        clearDatabase()
    }

    /**
     * test if we can upload simple input stream to database
     */
    @Test
    fun uploadSimpleInputStreamTest(): Unit = runBlocking {

        // initialize uploader + index actor
        val indexActor = createIndexActor()
        val cwd = File("")
        val uploader = Uploader.initUploader(tokenizerMap, cwd, indexActor)

        val outputDatabaseFile = "B/file.txt"
        val databaseRelativePath = DatabaseRelativePath.create(outputDatabaseFile)

        val s = """
           Take a Kotlin for example.
           kotlin is a damn fine language!
        """

        // convert string to input stream
        val inputStream = s.byteInputStream()

        // upload input stream
        uploader.uploadFromInputStream(inputStream, databaseRelativePath)

        // wait 1s for index to update
        delay(1000)

        val result = queryIndex(Token("kotlin"), indexActor)

        // check if index was properly updated
        assert(result.isNotEmpty())
        assert(result[databaseRelativePath]!!.isNotEmpty())

        // shutdown index actor
        indexActor.close()
    }

    /**
     * test if we can upload simple string to database
     */
    @Test
    fun uploadSimpleStringTest(): Unit = runBlocking {

        // initialize uploader + index actor
        val indexActor = createIndexActor()
        val cwd = File("")
        val uploader = Uploader.initUploader(tokenizerMap, cwd, indexActor)

        val outputDatabaseFile = "B/file.txt"
        val databaseRelativePath = DatabaseRelativePath.create(outputDatabaseFile)

        val s = """
           Take a Kotlin for example.
           kotlin is a damn fine language!
        """

        uploader.uploadFromString(s, databaseRelativePath)

        // wait 1s for index to update
        delay(1000)

        val result = queryIndex(Token("kotlin"), indexActor)

        // check if index was properly updated
        assert(result.isNotEmpty())
        assert(result[databaseRelativePath]!!.isNotEmpty())

        // shutdown index actor
        indexActor.close()
    }

    /**
     * test if concurrent actions (upload + remove) on the same file fails with exception
     */
    @Test
    fun concurrentFileAccessTest(): Unit = runBlocking {

        // initialize uploader + index actor
        val indexActor = createIndexActor()
        val cwd = File("")
        val uploader = Uploader.initUploader(tokenizerMap, cwd, indexActor)

        var uploadFileJobHasFailed = false
        var removeFileJobHasFailed = false
        var exception: Exception? = null

        val testInputFile = "test_files/test.txt"
        val outputDatabaseFile = "B/file.txt"

        // launch first job, which will try to upload file
        val uploadFileJob = launch {
            try {
                uploader.uploadFile(File(testInputFile), DatabaseRelativePath.create(outputDatabaseFile))
            } catch (e: Exception) {
                uploadFileJobHasFailed = true
                exception = e
            }
        }

        // launch second job, which will try to remove the file
        val removeFileJob = launch {
            try {
                uploader.removeFileFromDatabase(DatabaseRelativePath.create(outputDatabaseFile))
            } catch (e: Exception) {
                removeFileJobHasFailed = true
                exception = e
            }
        }

        uploadFileJob.join()
        removeFileJob.join()

        // one of the 2 concurrent tasks on the same file should fail
        // (only one task will lock file and the second one will end up with exception)
        assert(removeFileJobHasFailed || uploadFileJobHasFailed)
        assert(exception is FileIsAlreadyInUseException)

        // shutdown index actor
        indexActor.close()
    }

    /**
     * test if concurrent actions (double (2x) upload) on the same file fails with exception
     */
    @Test
    fun concurrentFileUploadTest(): Unit = runBlocking {

        // initialize uploader + index actor
        val indexActor = createIndexActor()
        val cwd = File("")
        val uploader = Uploader.initUploader(tokenizerMap, cwd, indexActor)

        var uploadFileJobHasFailed = false
        var removeFileJobHasFailed = false
        var exception: Exception? = null

        val testInputFile = "test_files/test.txt"
        val outputDatabaseFile = "B/file.txt"

        // launch first job, which will try to upload file
        val uploadFileJob = launch {
            try {
                uploader.uploadFile(File(testInputFile), DatabaseRelativePath.create(outputDatabaseFile))
            } catch (e: Exception) {
                uploadFileJobHasFailed = true
                exception = e
            }
        }

        // launch second job, which will try to upload the same file concurrently to first job
        val removeFileJob = launch {
            try {
                uploader.removeFileFromDatabase(DatabaseRelativePath.create(outputDatabaseFile))
            } catch (e: Exception) {
                removeFileJobHasFailed = true
                exception = e
            }
        }

        uploadFileJob.join()
        removeFileJob.join()

        // one of the two concurrent tasks on the same file should fail
        // (only one task will lock file and the second one will end up with exception)
        assert(removeFileJobHasFailed || uploadFileJobHasFailed)
        assert(exception is FileIsAlreadyInUseException)

        // shutdown index actor
        indexActor.close()
    }
}

suspend fun queryIndex(token: Token, indexActor: SendChannel<QueryIndex>): TokenLocationsMap {
    val response = CompletableDeferred<TokenLocationsMap>()
    indexActor.send(QueryIndex(token, response))
    return response.await()
}