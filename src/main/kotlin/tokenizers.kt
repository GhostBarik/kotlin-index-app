import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class Token(val name: String)
data class TokenPosition(val line: Int, val startPosition: Int)

typealias MapOfTokens = Map<Token, List<TokenPosition>>
typealias MutableMapOfTokens = MutableMap<Token, List<TokenPosition>>


/**
 * Simple word tokenizer, which will splits text file to separate words.
 * Each word is defined as a sequence of alphanumeric characters. Some other
 * special characters such as whitespaces, newlines or other symbols (,.!) are threaten
 * as separators and are ignored.
 */
fun simpleTextTokenizer(lines: Sequence<String>): MapOfTokens {

    // we extract only words, containing alphanumeric characters
    // (i.e. all other special characters such as ,.! etc. will be ignored)
    val wordRegex = """\w+""".toRegex()
    val mapOfTokens: MutableMapOfTokens = mutableMapOf()

    // process each line
    lines.forEachIndexed { lineIndex, lineContents ->

        // split line by regex
        val foundTokens = wordRegex.findAll(lineContents)

        // process each found word
        foundTokens.forEach { matchResult ->

            // convert word to token by applying lowercase() function
            val token = Token(matchResult.value.lowercase())

            // store token position
            val position = TokenPosition(lineIndex, matchResult.range.first)

            // store results in the map
            mapOfTokens.merge(token, listOf(position)) { previousPositions, newPositions ->
                previousPositions + newPositions
            }
        }
    }

    logger.info("[TEXT TOKENIZER]: extracted tokens: ${mapOfTokens.size}")
    return mapOfTokens
}


/**
 * Very simple tokenizer for Java files to demonstrate a different approach.
 * Only extracts comments and 4 basic keyword (class/public/static/void).
 */
fun simpleJavaCodeTokenizer(lines: Sequence<String>): MapOfTokens {

    val commentRegex = """(//.*)$""".toRegex()
    val keywordRegex = """(\s*)(class|public|static|void)(\s*)""".toRegex()

    val mapOfTokens: MutableMapOfTokens = mutableMapOf()

    // process each line
    lines.forEachIndexed { lineIndex, lineContents ->

        var line = lineContents

        // does line contain comment inside?
        if (commentRegex.containsMatchIn(line)) {

            // extract comment token part from line
            val commentRange = commentRegex.findAll(line).first().range

            // remove comment from line
            line = line.removeRange(commentRange)

            val token = Token("java_comment")
            val position = TokenPosition(lineIndex, commentRange.first)

            mapOfTokens.merge(token, listOf(position)) { previousPositions, newPositions ->
                previousPositions + newPositions
            }
        }

        keywordRegex.findAll(line).forEach {

            val token = Token("java_keyword[${it.groups[2]!!.value}]")
            val range = it.groups[2]!!.range
            val position = TokenPosition(lineIndex, range.first)

            mapOfTokens.merge(token, listOf(position)) { previousPositions, newPositions ->
                previousPositions + newPositions
            }
        }
    }

    logger.info("[JAVA CODE TOKENIZER]: extracted tokens: ${mapOfTokens.size}")
    return mapOfTokens
}
