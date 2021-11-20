package `in`.specmatic.core

fun pathNotRecognizedMessage(httpRequest: HttpRequest): String {
    val soapActionHeader = "SOAPAction"
    if(httpRequest.headers.containsKey(soapActionHeader))
        return "SOAP request not recognized; path=" + httpRequest.path + ", SOAPAction=${httpRequest.headers.getValue(soapActionHeader)}"

    return "Request not recognized; method=${httpRequest.method}, path=${httpRequest.path}"
}

const val PATH_NOT_RECOGNIZED_ERROR = "URL path or SOAPAction not recognised"

data class Results(val results: List<Result> = emptyList()) {
    fun hasResults(): Boolean = results.isNotEmpty()

    fun hasFailures(): Boolean = results.any { it is Result.Failure }
    fun success(): Boolean = !hasFailures()

    fun withoutFluff(): Results = copy(results = results.filterNot { it.isFluffy() })

    fun toResultIfAny(): Result {
        return results.find { it is Result.Success } ?: Result.Failure(results.joinToString("\n\n") { it.toReport().toText() })
    }

    val failureCount
        get(): Int = results.count { it is Result.Failure }

    val successCount
        get(): Int = results.count { it is Result.Success }

    fun generateErrorHttpResponse(): HttpResponse {
        val report = report("").trim()

        val defaultHeaders = mapOf("Content-Type" to "text/plain", SPECMATIC_RESULT_HEADER to "failure")
        val headers = when {
            report.isEmpty() -> defaultHeaders.plus(SPECMATIC_EMPTY_HEADER to "true")
            else -> defaultHeaders
        }

        return HttpResponse(400, report(PATH_NOT_RECOGNIZED_ERROR), headers)
    }

    fun report(httpRequest: HttpRequest): String {
        return report(pathNotRecognizedMessage(httpRequest))
    }

    fun report(defaultMessage: String = PATH_NOT_RECOGNIZED_ERROR): String {
        val filteredResults = withoutFluff().results

        return when {
            filteredResults.isNotEmpty() -> listToReport(filteredResults)
            else -> "$defaultMessage\n\n${listToReport(results)}".trim()
        }
    }
}

private fun listToReport(results: List<Result>): String =
    results.filterIsInstance<Result.Failure>().joinToString("${System.lineSeparator()}${System.lineSeparator()}") {
        it.toFailureReport().toText()
    }
