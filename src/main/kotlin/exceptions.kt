/**
 * Exeptions, thrown by Uploader class methods
 */
class TokenizerNotFoundException(message: String) : Exception(message)
class FileNotFoundException(message: String) : Exception(message)
class FileIsAlreadyInUseException(message: String) : Exception(message)

/**
 * Exceptions, thrown by DatabaseRelativePath class methods
 */
class InvalidDatabasePath(message: String) : Exception(message)