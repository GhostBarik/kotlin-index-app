package tokenizers

import Token
import TokenPosition
import createIndexActor
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import removeFileFromIndex
import simpleTextTokenizer
import updateIndex

@ObsoleteCoroutinesApi
class IndexTests {

    /**
     * test, if index is correctly updated after update message is received by index actor
     */
    @Test
    fun indexUpdateTest(): Unit = runBlocking {

        val s = """
           Take a Kotlin for example.
           kotlin is a damn fine language!
        """

        val tokens = simpleTextTokenizer(s.lines().asSequence())

        // initialize index actor
        val indexActor = createIndexActor()
        val databasePath = DatabaseRelativePath.create("file.txt")
        val databasePath2 = DatabaseRelativePath.create("file2.txt")

        updateIndex(databasePath, tokens, indexActor)
        updateIndex(databasePath2, tokens, indexActor)

        // wait for index to update
        delay(1000)

        var result = queryIndex(Token("take"), indexActor)

        // token map should contain 2 files (both 'file.txt' and 'file2.txt')
        assert(result.size == 2)
        assert(result.containsKey(databasePath))
        assert(result.containsKey(databasePath2))

        assert(result[databasePath]!!.contains(TokenPosition(line=1, startPosition = 11)))

        result = queryIndex(Token("kotlin"), indexActor)

        assert(result[databasePath]!!.contains(TokenPosition(line=1, startPosition = 18)))
        assert(result[databasePath]!!.contains(TokenPosition(line=2, startPosition = 11)))

        indexActor.close()
    }

    /**
     * test, if index is correctly updated after 2 messages (update, then remove)
     */
    @Test
    fun indexRemoveTest(): Unit = runBlocking {

        val s = """
           Take a Kotlin for example.
           kotlin is a damn fine language!
        """

        val tokens = simpleTextTokenizer(s.lines().asSequence())

        // initialize index actor
        val indexActor = createIndexActor()
        val databasePath = DatabaseRelativePath.create("file.txt")

        // upload file to index
        updateIndex(databasePath, tokens, indexActor)

        // remove same file from index
        removeFileFromIndex(databasePath, indexActor)

        // wait for index to update
        delay(1000)

        val result = queryIndex(Token("take"), indexActor)

        // index should contain no records (no files tracked)
        assert(result.isEmpty())

        indexActor.close()
    }
}