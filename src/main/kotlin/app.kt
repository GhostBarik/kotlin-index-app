import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File


/**
 * Runs CLI (command-line-interface) to interact with file database and index.
 */
@ObsoleteCoroutinesApi
fun main() {

    val tokenizerMap: TokenizerMap = mapOf(
        "txt"  to ::simpleTextTokenizer,
        "java" to ::simpleJavaCodeTokenizer
    )

    val queryRegex  = """(\s*)(query)(\s+)(.+)""".toRegex()
    val uploadRegex = """(\s*)(upload)(\s+)(.+?)(\s+)(.+?)""".toRegex()
    val removeRegex = """(\s*)(remove)(\s+)(.+?)(\s*)""".toRegex()
    val helpRegex   = """(\s*)help(\s*)""".toRegex()
    val quitRegex   = """(\s*)quit(\s*)""".toRegex()

    runBlocking {

        println("Application is staring...")

        val cwd = File("")

        println("initializing index module...")
        val indexActor: SendChannel<IndexActorInputMessage> = createIndexActor()

        println("initializing uploader module...")
        val uploader = Uploader.initUploader(tokenizerMap, cwd, indexActor)

        while(true) {

            var userInput: String

            // consume user input
            withContext(Dispatchers.IO) {
                print(">> ")
                userInput = readLine() ?: ""
            }

            // analyze user input
            if (quitRegex.matches(userInput)) {

                // exit from main loop
                break

            // 'LIST' command
            } else if (userInput.startsWith("list")) {

                runSafe {
                    println("getting list of all files, currently stored in database:")
                    val files = uploader.getListOfDatabaseFiles()

                    if (files.isEmpty()) {
                        println("database is empty")
                    }

                    files.forEach { file ->
                        println(file)
                    }
                }

            // 'QUERY' command
            } else if (queryRegex.matches(userInput)) {

                runSafe {
                    val tokenName = queryRegex.findAll(userInput).first().groupValues[4]
                    val token = Token(tokenName)

                    println("searching for word: ${tokenName}...")
                    val result = queryIndex(Token(tokenName), indexActor)

                    printTokenLocations(token, result)
                }

            // 'REMOVE' command
            } else if (removeRegex.matches(userInput)) {

                runSafe {
                    val commandParts = userInput.trim().split("""\s+""".toRegex())
                    val databasePath = commandParts[1]

                    uploader.removeFileFromDatabase(DatabaseRelativePath.create(databasePath))
                    println("successfully removed file: $databasePath")
                }

            } else if (uploadRegex.matches(userInput)) {

                runSafe {
                    val commandParts = userInput.trim().split("""\s+""".toRegex())
                    val filePath     = commandParts[1]
                    val databasePath = commandParts[2]

                    uploader.uploadFile(File(filePath), DatabaseRelativePath.create(databasePath))
                    println("successfully uploaded file: $filePath")
                    println("file will be stored in database under name: $databasePath")
                }

            // 'HELP' command
            } else if (helpRegex.matches(userInput)) {

                println("1. list - list of text files, stored in database")
                println("2. query [word] - search for word in database")
                println(
                    "3. upload [disk_file] - upload file from disk " +
                    "(example: upload ./test_files/lorem.txt A/a.txt)"
                )
                println("4. remove [db_file] - remove file from database (example: remove A/text.txt)")
                println("5. quit - exit from application")

            } else {
                println("unknown command, try typing 'help' to get additional information")
            }
        }

        println("closing application...")
        indexActor.close()
    }
}

private suspend fun runSafe(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        println("command failed, reason: $e")
    }
}