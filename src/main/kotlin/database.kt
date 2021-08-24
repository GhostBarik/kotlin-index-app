/**
 * This class is a wrapper on top of String. It ensures, that database path, stored in this String
 * conforms to the specific format: {catalogue}/{filename}.{file_extension}
 *
 * NOTE: This class is implemented as a ordinary Kotlin class, not data class,
 * to avoid accessing copy(..) constructor in user code (in case of data class it's always exposed)
 * user can only create the instance of this class via factory function, never via standard constructor or copy(..).
 * The downside is that hashcode(..)/equals(..) methods have to be implemented by hand.
 */
class DatabaseRelativePath private constructor(val path: String) {

    companion object Factory {

        // path should be always in the following format: {catalogue}/{filename}.{file_extension}
        // (any number of catalogs/subcatalogs can be provided in the input, even 0)
        // where A,B are names for catalogues and file name + file extension are provided
        private val pathRegex = """(\w+/)*(\w+)\.(\w+)""".toRegex()

        // factory function will perform validation on the input string
        // and will throw an exception in case user provides an incorrect value for path
        fun create(path: String): DatabaseRelativePath {

            if (!pathRegex.matches(path)) {
                throw InvalidDatabasePath(
                    "[DatabaseRelativePath]: Path is not valid, " +
                    "correct format: {catalogue}/{filename}.{extension}, " +
                    "provided: $path"
                )
            }

            return DatabaseRelativePath(path)
        }
    }

    val fileExtension: String by lazy {
        val fileExtension = pathRegex.findAll(path).first().groupValues.last()
        fileExtension
    }

    override fun equals(other: Any?): Boolean {

        if (this === other) {
            return true
        }

        if (javaClass != other?.javaClass) {
            return false
        }

        other as DatabaseRelativePath
        if (path != other.path) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        return path.hashCode()
    }

    override fun toString(): String {
        return "DatabaseRelativePath(path='$path')"
    }
}