package com.pgpe.parser

import com.pgpe.ast.PolicySet
import com.pgpe.parser.generated.PolicyDslLexer
import com.pgpe.parser.generated.PolicyDslParser
import org.antlr.v4.runtime.*

class ParseException(message: String) : RuntimeException(message)

data class ParseError(
    val line: Int,
    val column: Int,
    val message: String,
    val fileName: String? = null
)

data class ParseResult(
    val policySet: PolicySet?,
    val errors: List<ParseError>
) {
    val success: Boolean get() = errors.isEmpty() && policySet != null
}

class CollectingErrorListener(private val fileName: String?) : BaseErrorListener() {
    val errors = mutableListOf<ParseError>()

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?
    ) {
        errors.add(ParseError(line, charPositionInLine, msg ?: "Unknown error", fileName))
    }
}

fun parse(source: String, fileName: String? = null): ParseResult {
    val input = CharStreams.fromString(source)
    val lexer = PolicyDslLexer(input)
    val errorListener = CollectingErrorListener(fileName)

    lexer.removeErrorListeners()
    lexer.addErrorListener(errorListener)

    val tokens = CommonTokenStream(lexer)
    val parser = PolicyDslParser(tokens)

    parser.removeErrorListeners()
    parser.addErrorListener(errorListener)

    val tree = parser.policySet()

    if (errorListener.errors.isNotEmpty()) {
        return ParseResult(null, errorListener.errors)
    }

    val builder = AstBuilder()
    val policySet = builder.visitPolicySet(tree)
    return ParseResult(policySet, emptyList())
}
