package tokenizers

import DatabaseRelativePath
import InvalidDatabasePath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class DatabaseTests {

    /**
     * create valid database path
     */
    @Test
    fun testValidDatabasePath(): Unit = run {
        assertDoesNotThrow {
            DatabaseRelativePath.create("A/test.txt")
            DatabaseRelativePath.create("A/B/test.txt")
        }
    }

    /**
     * attempt to create invalid database path (case 1)
     */
    @Test
    fun testInvalidDatabasePath2(): Unit = run {
        assertThrows<InvalidDatabasePath> {
            DatabaseRelativePath.create("A/test")
        }
    }

    /**
     * attempt to create invalid database path (case 2)
     */
    @Test
    fun testInvalidDatabasePath(): Unit = run {
        assertThrows<InvalidDatabasePath> {
            DatabaseRelativePath.create("/test.txt")
        }
    }
}