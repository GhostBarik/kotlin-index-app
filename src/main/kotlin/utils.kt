import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel

/**
 * Utility functions to simplify communication with Index Actor
 * (wrapping deferred result into suspended function)
 */
suspend fun queryIndex(token: Token, indexActor: SendChannel<QueryIndex>): TokenLocationsMap {

    val response = CompletableDeferred<TokenLocationsMap>()
    indexActor.send(QueryIndex(token, response))
    return response.await()
}

suspend fun updateIndex(databasePath: DatabaseRelativePath, tokens: MapOfTokens, indexActor: SendChannel<UpdateIndex>) {
    indexActor.send(UpdateIndex(databasePath, tokens))
}

suspend fun removeFileFromIndex(databasePath: DatabaseRelativePath, indexActor: SendChannel<RemoveFileFromIndex>) {
    indexActor.send(RemoveFileFromIndex(databasePath))
}

/**
 * Pretty-prints map of token locations to standard output (STDOUT)
 */
fun printTokenLocations(token: Token, map: TokenLocationsMap) {

    println("********* All occurrences of word '${token.name}': ******")

    if (map.isEmpty()) {
        println("nothing was found")
    }

    map.forEach{ (databaseFile, positions) ->
        println("")
        println("database file: ${databaseFile.path}")
        positions.groupBy { it.line }
            .forEach{ (line, positions) ->
                println(
                    "LINE: $line => positions: " +
                    "${positions.map{it.startPosition}.toList()}"
                )
            }
    }
    println("***********************************************************")
}
