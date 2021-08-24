package tokenizers

import Token
import org.junit.jupiter.api.Test
import simpleJavaCodeTokenizer
import simpleTextTokenizer

class TokenizersTests {

    @Test
    fun testJavaCodeTokenizer(): Unit = run {

        val s = """
            // Your First Program
            class HelloWorld {
                public static void main(String[] args) {
                    System.out.println("Hello, World!"); // another comment
                }
            }
        """

        val mapOfTokens = simpleJavaCodeTokenizer(s.lines().asSequence())

        assert(mapOfTokens.containsKey(Token("java_comment")))
        assert(mapOfTokens.containsKey(Token("java_keyword[public]")))
        assert(mapOfTokens.containsKey(Token("java_keyword[static]")))
        assert(mapOfTokens.containsKey(Token("java_keyword[void]")))
        assert(mapOfTokens.containsKey(Token("java_keyword[class]")))

        // check if 2 comments in the file were found
        assert(mapOfTokens[Token("java_comment")]?.size ?: 0 == 2)

        assert(mapOfTokens[Token("java_keyword[public]")]?.size ?: 0 == 1)
        assert(mapOfTokens[Token("java_keyword[static]")]?.size ?: 0 == 1)
        assert(mapOfTokens[Token("java_keyword[void]")]?.size ?: 0 == 1)
        assert(mapOfTokens[Token("java_keyword[class]")]?.size ?: 0 == 1)
    }

    @Test
    fun testTextTokenizer(): Unit = run {

        val s = """
           Take a Kotlin for example.
           kotlin is a damn fine language!
        """

        val mapOfTokens = simpleTextTokenizer(s.lines().asSequence())

        assert(mapOfTokens.containsKey(Token("kotlin")))

        // "kotlin" token should be found in 2 different positions
        assert(mapOfTokens[Token("kotlin")]!!.size == 2)

        assert(mapOfTokens.containsKey(Token("take")))

        // "kotlin" token should be found in only single position
        assert(mapOfTokens[Token("take")]!!.size == 1)

        assert(mapOfTokens.containsKey(Token("a")))

        // "kotlin" token should be found in 2 different positions
        assert(mapOfTokens[Token("a")]!!.size == 2)
    }

    @Test
    fun testTextTokenizer2(): Unit = run {

        val s = """
           Contrary to popular belief, Lorem Ipsum is not simply random text. 
           It has roots in a piece of classical Latin literature from 45 BC, making it over 2000 years old. Richard McClintock, 
           a Latin professor at Hampden-Sydney College in Virginia, 
           looked up one of the more obscure Latin words, consectetur, 
           from a Lorem Ipsum passage, and going through the cites of the word in classical literature, 
           discovered the undoubtable source. Lorem Ipsum comes from sections 1.10.32 and 1.10.33 of 
           "de Finibus Bonorum et Malorum" (The Extremes of Good and Evil) by Cicero, written in 45 BC. 
           This book is a treatise on the theory of ethics, very popular during the Renaissance. 
           The first line of Lorem Ipsum, "Lorem ipsum dolor sit amet..", comes from a line in section 1.10.32.
        """

        val mapOfTokens = simpleTextTokenizer(s.lines().asSequence())

        assert(mapOfTokens.containsKey(Token("lorem")))

        // "lorem" token should be found in 5 different positions
        assert(mapOfTokens[Token("lorem")]!!.size == 5)

        assert(mapOfTokens.containsKey(Token("of")))

        // "of" token should be found in 5 different positions
        assert(mapOfTokens[Token("of")]!!.size == 7)
    }
}