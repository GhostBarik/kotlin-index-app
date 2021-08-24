import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.actor
import mu.KotlinLogging
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private val logger = KotlinLogging.logger {}

typealias TokenLocationsMutableMap = MutableMap<DatabaseRelativePath, List<TokenPosition>>
typealias TokenLocationsMap = Map<DatabaseRelativePath, List<TokenPosition>>
typealias IndexMap = MutableMap<Token, TokenLocationsMutableMap>

/**
 * Index Actor interface (input messages format)
 */
sealed class IndexActorInputMessage

// UPDATE -> send the name of specific file which was updated and new tokens which were parsed from this file
class UpdateIndex(val databaseFile: DatabaseRelativePath, val fileTokens: MapOfTokens) : IndexActorInputMessage()

// REMOVE -> send the name of the specific file that was removed from database
class RemoveFileFromIndex(val databaseFile: DatabaseRelativePath) : IndexActorInputMessage()

// QUERY -> query index for specific token (returns all files,
// which contain this token along with specific positions within each file)
class QueryIndex(val token: Token, val response: CompletableDeferred<TokenLocationsMap>) : IndexActorInputMessage()

// specify how many messages mailbox (channel) can buffer without blocking message producers
const val DEFAULT_ACTOR_MAILBOX_SIZE = 200


/**
 * Simple index implementation, using actor coroutine, which exposes mailbox (channel) for communication.
 *
 * Each update request ended-up in an input channel is processed by the actor serially.
 * During those updates, actor cannot serve concurrent read requests.
 * Update requests are processed one-by-one, ensuring quick incremental updates.
 * (we avoid buffering of the update requests to save memory because
 * multiple update messages can contain a lot of objects inside).
 *
 * Querying from index should be relatively fast (assuming average access time of the default hash map implementation),
 * however concurrency is severely limited, because of sequencing in mailbox processing
 * (requests are processed serially one by one)
 *
 * The current design of the index favors consistency/simplicity (no locks involved) over performance and scalability.
 */
@ObsoleteCoroutinesApi
fun CoroutineScope.createIndexActor(context: CoroutineContext = EmptyCoroutineContext) =
    actor<IndexActorInputMessage>(context = context, capacity = DEFAULT_ACTOR_MAILBOX_SIZE) {

    // map, containing tokens along with all metadata, relevant for each token
    // (such as list of files, when token can be found and list of precise token locations
    // within each file, i.e. line/character position)
    val index = Index()

    // sequentially process any message, coming into input actor mailbox (channel)
    for (msg in channel) {
        when (msg) {
            is QueryIndex -> {

                logger.info("[INDEX]: querying token: ${msg.token} ...")

                // find all token locations
                // (if result is not found, empty map of token locations is returned)
                // we always return copy of the map to avoid any further changes
                // in index map to be leaked outside of the actor
                val result = index.indexMap.getOrDefault(msg.token, mapOf()).toMap()

                // send result back to requester
                msg.response.complete(result)
            }
            is RemoveFileFromIndex -> {
                logger.info("[INDEX]: removing file: ${msg.databaseFile} from index")
                index.removeFileFromIndex(msg.databaseFile)
            }
            is UpdateIndex -> {
                logger.info(
                    "[INDEX]: updating index with tokens: " +
                    "${msg.fileTokens.toString().take(60)+"..."} from file ${msg.databaseFile}"
                )
                index.updateFileInIndex(msg.databaseFile, msg.fileTokens)
            }
        }
    }
}

// incapsulate all methods, modifying index map (side-effects) into separate class
class Index {

    val indexMap: IndexMap = mutableMapOf()

    // remove file from index
    fun removeFileFromIndex(file: DatabaseRelativePath) {

        // scan entire map and remove references to the file for each token referencing the file
        indexMap.values.forEach{ tokenLocationsMap ->
            tokenLocationsMap.remove(file)
        }

        // remove all tokens, which do not have any references anymore
        indexMap.entries.removeIf{ entry ->
            entry.value.isEmpty()
        }
    }

    // add or replace file in index
    fun updateFileInIndex(file: DatabaseRelativePath, fileTokens: MapOfTokens) {

        // for each new token ->
        fileTokens.forEach { (token, newPositions) ->

            // extract map of locations for current token
            val currentLocations = indexMap.getOrDefault(token, mutableMapOf())

            // update map of token locations for given file
            currentLocations[file] = newPositions

            // replace locations map with updated version
            indexMap[token] = currentLocations
        }
    }
}
