package `in`.specmatic.core

import `in`.specmatic.conversions.*
import `in`.specmatic.core.pattern.*
import `in`.specmatic.core.pattern.Examples.Companion.examplesFrom
import `in`.specmatic.core.utilities.jsonStringToValueMap
import `in`.specmatic.core.value.*
import `in`.specmatic.core.wsdl.parser.message.primitiveTypes
import `in`.specmatic.mock.NoMatchingScenario
import `in`.specmatic.mock.ScenarioStub
import `in`.specmatic.stub.HttpStubData
import `in`.specmatic.test.ContractTest
import `in`.specmatic.test.ScenarioTest
import `in`.specmatic.test.ScenarioTestGenerationFailure
import `in`.specmatic.test.TestExecutor
import io.cucumber.gherkin.GherkinDocumentBuilder
import io.cucumber.gherkin.Parser
import io.cucumber.messages.IdGenerator
import io.cucumber.messages.IdGenerator.Incrementing
import io.cucumber.messages.types.*
import io.cucumber.messages.types.Examples
import io.swagger.v3.oas.models.*
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.*
import io.swagger.v3.oas.models.parameters.*
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import kotlinx.coroutines.Deferred
import java.io.File
import java.net.URI

fun parseContractFileToFeature(contractPath: String): Feature {
    return parseContractFileToFeature(File(contractPath))
}

fun parseContractFileToFeature(file: File): Feature {
    information.forDebugging("Parsing contract file ${file.path}, absolute path ${file.absolutePath}")

    if (!file.exists())
        throw ContractException("File ${file.path} does not exist (absolute path ${file.canonicalPath})")

    return when (file.extension) {
        "yaml" -> OpenApiSpecification.fromFile(file.path).toFeature()
        "wsdl" -> wsdlContentToFeature(file.readText(), file.canonicalPath)
        in CONTRACT_EXTENSIONS -> parseGherkinStringToFeature(file.readText().trim(), file.canonicalPath)
        else -> throw ContractException("File extension of ${file.path} not recognized")
    }
}

fun parseGherkinStringToFeature(gherkinData: String, sourceFilePath: String = ""): Feature {
    val gherkinDocument = parseGherkinString(gherkinData, sourceFilePath)
    val (name, scenarios) = lex(gherkinDocument, sourceFilePath)
    return Feature(scenarios = scenarios, name = name)
}

data class Feature(
    val scenarios: List<Scenario> = emptyList(),
    private var serverState: Map<String, Value> = emptyMap(),
    val name: String,
    val testVariables: Map<String, String> = emptyMap(),
    val testBaseURLs: Map<String, String> = emptyMap()
) {
    fun lookupResponse(httpRequest: HttpRequest): HttpResponse {
        try {
            val resultList = lookupScenario(httpRequest, scenarios)
            return matchingScenario(resultList)?.generateHttpResponse(serverState)
                ?: Results(resultList.map { it.second }.toMutableList()).withoutFluff().generateErrorHttpResponse()
        } finally {
            serverState = emptyMap()
        }
    }

    fun stubResponse(httpRequest: HttpRequest): HttpResponse {
        try {
            val scenarioSequence = scenarios.asSequence()

            val localCopyOfServerState = serverState
            val resultList = scenarioSequence.zip(scenarioSequence.map {
                it.matchesStub(httpRequest, localCopyOfServerState)
            })
            return matchingScenario(resultList)?.generateHttpResponse(serverState)
                ?: Results(resultList.map { it.second }.toMutableList()).withoutFluff().generateErrorHttpResponse()
        } finally {
            serverState = emptyMap()
        }
    }

    fun lookupScenario(httpRequest: HttpRequest): List<Scenario> =
        try {
            val resultList = lookupScenario(httpRequest, scenarios)
            val matchingScenarios = matchingScenarios(resultList)

            val firstRealResult = resultList.filterNot { isFluffyError(it.second) }.firstOrNull()
            val resultsExist = resultList.firstOrNull() != null

            when {
                matchingScenarios.isNotEmpty() -> matchingScenarios
                firstRealResult != null -> throw ContractException(resultReport(firstRealResult.second))
                resultsExist -> throw ContractException(pathNotRecognizedMessage(httpRequest))
                else -> throw ContractException("The contract is empty.")
            }
        } finally {
            serverState = emptyMap()
        }

    private fun matchingScenarios(resultList: Sequence<Pair<Scenario, Result>>): List<Scenario> {
        return resultList.filter {
            it.second is Result.Success
        }.map { it.first }.toList()
    }

    private fun matchingScenario(resultList: Sequence<Pair<Scenario, Result>>): Scenario? {
        return resultList.find {
            it.second is Result.Success
        }?.first
    }

    private fun lookupScenario(httpRequest: HttpRequest, scenarios: List<Scenario>): Sequence<Pair<Scenario, Result>> {
        val scenarioSequence = scenarios.asSequence()

        val localCopyOfServerState = serverState
        return scenarioSequence.zip(scenarioSequence.map {
            it.matches(httpRequest, localCopyOfServerState)
        })
    }

    fun executeTests(testExecutorFn: TestExecutor, suggestions: List<Scenario> = emptyList()): Results =
        generateContractTestScenarios(suggestions).fold(Results()) { results, scenario ->
            Results(results = results.results.plus(executeTest(scenario, testExecutorFn)).toMutableList())
        }

    fun executeTests(
        testExecutorFn: TestExecutor,
        suggestions: List<Scenario> = emptyList(),
        scenarioNames: List<String>
    ): Results =
        generateContractTestScenarios(suggestions)
            .filter { scenarioNames.contains(it.name) }
            .fold(Results()) { results, scenario ->
                Results(results = results.results.plus(executeTest(scenario, testExecutorFn)).toMutableList())
            }

    fun setServerState(serverState: Map<String, Value>) {
        this.serverState = this.serverState.plus(serverState)
    }

    fun matches(request: HttpRequest, response: HttpResponse): Boolean {
        return scenarios.firstOrNull { it.matches(request, serverState) is Result.Success }
            ?.matches(response) is Result.Success
    }

    fun matchingStub(request: HttpRequest, response: HttpResponse): HttpStubData {
        try {
            val results = scenarios.map { scenario ->
                try {
                    when (val matchResult = scenario.matchesMock(request, response)) {
                        is Result.Success -> Pair(
                            scenario.resolverAndResponseFrom(response).let { (resolver, resolvedResponse) ->
                                val newRequestType = scenario.httpRequestPattern.generate(request, resolver)
                                val requestTypeWithAncestors =
                                    newRequestType.copy(
                                        headersPattern = newRequestType.headersPattern.copy(
                                            ancestorHeaders = scenario.httpRequestPattern.headersPattern.pattern
                                        )
                                    )
                                HttpStubData(
                                    response = resolvedResponse.copy(externalisedResponseCommand = response.externalisedResponseCommand),
                                    resolver = resolver,
                                    requestType = requestTypeWithAncestors,
                                    responsePattern = scenario.httpResponsePattern
                                )
                            }, Result.Success()
                        )
                        is Result.Failure -> {
                            Pair(null, matchResult.updateScenario(scenario))
                        }
                    }
                } catch (contractException: ContractException) {
                    Pair(null, contractException.failure())
                }
            }

            return results.find {
                it.first != null
            }?.let { it.first as HttpStubData }
                ?: throw NoMatchingScenario(failureResults(results).withoutFluff().report(request))
        } finally {
            serverState = emptyMap()
        }
    }

    private fun failureResults(results: List<Pair<HttpStubData?, Result>>): Results =
        Results(results.map { it.second }.filterIsInstance<Result.Failure>().toMutableList())

    fun generateContractTests(suggestions: List<Scenario>): List<ContractTest> {
        return scenarios.map {
            try {
                ScenarioTest(it.newBasedOn(suggestions))
            } catch (e: Throwable) {
                ScenarioTestGenerationFailure(it, e)
            }
        }.flatMap {
            it.generateTestScenarios(testVariables, testBaseURLs)
        }
    }

    fun generateContractTestScenarios(suggestions: List<Scenario>): List<Scenario> =
        scenarios.map { it.newBasedOn(suggestions) }.flatMap {
            it.generateTestScenarios(testVariables, testBaseURLs)
        }

    fun generateBackwardCompatibilityTestScenarios(): List<Scenario> =
        scenarios.flatMap { scenario ->
            scenario.copy(examples = emptyList()).generateBackwardCompatibilityScenarios()
        }

    fun assertMatchesMockKafkaMessage(kafkaMessage: KafkaMessage) {
        val result = matchesMockKafkaMessage(kafkaMessage)
        if (result is Result.Failure)
            throw NoMatchingScenario(resultReport(result))
    }

    fun matchesMockKafkaMessage(kafkaMessage: KafkaMessage): Result {
        val results = scenarios.asSequence().map {
            it.matchesMock(kafkaMessage)
        }

        return results.find { it is Result.Success } ?: results.firstOrNull()
        ?: Result.Failure("No match found, couldn't check the message")
    }

    fun matchingStub(scenarioStub: ScenarioStub): HttpStubData =
        matchingStub(scenarioStub.request, scenarioStub.response).copy(delayInSeconds = scenarioStub.delayInSeconds)

    fun clearServerState() {
        serverState = emptyMap()
    }

    fun lookupKafkaScenario(
        olderKafkaMessagePattern: KafkaMessagePattern,
        olderResolver: Resolver
    ): Sequence<Pair<Scenario, Result>> {
        try {
            return scenarios.asSequence()
                .filter { it.kafkaMessagePattern != null }
                .map { newerScenario ->
                    Pair(
                        newerScenario,
                        olderKafkaMessagePattern.encompasses(
                            newerScenario.kafkaMessagePattern as KafkaMessagePattern,
                            newerScenario.resolver,
                            olderResolver
                        )
                    )
                }
        } finally {
            serverState = emptyMap()
        }
    }

    fun toOpenApi(): OpenAPI {
        val openAPI = OpenAPI()
        openAPI.info = Info().also {
            it.title = this.name
            it.version = "1"
        }
        val paths = scenarios.map { scenario ->
            val path = PathItem()
            val operation = Operation()
            val pathParameters = scenario.httpRequestPattern.urlMatcher!!.pathParameters()
            val openApiPathParameters = pathParameters.map {
                val pathParameter: Parameter = PathParameter()
                pathParameter.name = it.key
                pathParameter.schema = toOpenApiSchema(it.pattern)
                pathParameter
            }
            val queryParameters = scenario.httpRequestPattern.urlMatcher!!.queryPattern
            val openApiQueryParameters = queryParameters.map { (key, pattern) ->
                val queryParameter: Parameter = QueryParameter()
                queryParameter.name = key.removeSuffix("?")
                queryParameter.schema = toOpenApiSchema(pattern)
                queryParameter
            }
            val openApiRequestHeaders = scenario.httpRequestPattern.headersPattern.pattern.map { (key, pattern) ->
                val headerParameter = HeaderParameter()
                headerParameter.name = key.removeSuffix("?")
                headerParameter.schema = toOpenApiSchema(pattern)
                headerParameter.required = key.contains("?").not()
                headerParameter
            }

            val requestBodyType = scenario.httpRequestPattern.body

            val requestBodySchema: Pair<String, MediaType>? = when {
                isJSONPayload(requestBodyType) || requestBodyType is DeferredPattern && isJSONPayload(requestBodyType.resolvePattern(scenario.resolver))-> {
                    jsonMediaType(requestBodyType)
                }
                requestBodyType is XMLPattern || requestBodyType is DeferredPattern && requestBodyType.resolvePattern(scenario.resolver) is XMLPattern -> {
                    throw ContractException("XML not supported yet")
                }
                else -> {
                    if(scenario.httpRequestPattern.formFieldsPattern.isNotEmpty()) {
                        val mediaType = MediaType()
                        mediaType.schema = Schema<Any>().apply {
                            this.required = scenario.httpRequestPattern.formFieldsPattern.keys.toList()
                            this.properties = scenario.httpRequestPattern.formFieldsPattern.map { (key, type) ->
                                val schema = toOpenApiSchema(type)
                                Pair(withoutOptionality(key), schema)
                            }.toMap()
                        }

                        val encoding: MutableMap<String, Encoding> = scenario.httpRequestPattern.formFieldsPattern.map { (key, type) ->
                            when {
                                isJSONPayload(type) || (type is DeferredPattern && isJSONPayload(type.resolvePattern(scenario.resolver))) -> {
                                    val encoding = Encoding().apply {
                                        this.contentType = "application/json"
                                    }

                                    Pair(withoutOptionality(key), encoding)
                                }
                                type is XMLPattern ->
                                    throw NotImplementedError("XML encoding not supported for form fields")
                                else -> {
                                    null
                                }
                            }
                        }.filterNotNull().toMap().toMutableMap()

                        if(encoding.isNotEmpty())
                            mediaType.encoding = encoding

                        Pair("application/x-www-form-urlencoded", mediaType)
                    }
                    else if(scenario.httpRequestPattern.multiPartFormDataPattern.isNotEmpty()) {
                        throw NotImplementedError("mulitpart form data not yet supported")
                    } else {
                        null
                    }
                }
            }

            if(requestBodySchema != null) {
                operation.requestBody = RequestBody().apply {
                    this.content = Content().apply {
                        this[requestBodySchema.first] = requestBodySchema.second
                    }
                }
            }

            operation.parameters = openApiPathParameters + openApiQueryParameters + openApiRequestHeaders
            val responses = ApiResponses()
            val apiResponse = ApiResponse()
            apiResponse.content =
                Content().also {
                    it.addMediaType("application/json", MediaType().also {
                        it.schema = toOpenApiSchema(scenario.httpResponsePattern.body)
                    })
                }
            apiResponse.description = "Response Description"
            val openApiResponseHeaders = scenario.httpResponsePattern.headersPattern.pattern.map { (key, pattern) ->
                val header = Header()
                header.schema = toOpenApiSchema(pattern)
                header.required = !key.endsWith("?")

                Pair(withoutOptionality(key), header)
            }.toMap()

            if(openApiResponseHeaders.isNotEmpty()) {
                apiResponse.headers = openApiResponseHeaders
            }

            if(scenario.httpResponsePattern.body !is EmptyStringPattern) {
                apiResponse.content = Content().apply {
                    val responseBodyType = scenario.httpResponsePattern.body

                    val responseBodySchema: Pair<String, MediaType>? = when {
                        isJSONPayload(responseBodyType) || responseBodyType is DeferredPattern && isJSONPayload(responseBodyType.resolvePattern(scenario.resolver))-> {
                            jsonMediaType(responseBodyType)
                        }
                        responseBodyType is XMLPattern || responseBodyType is DeferredPattern && responseBodyType.resolvePattern(scenario.resolver) is XMLPattern -> {
                            throw ContractException("XML not supported yet")
                        }
                        else -> {
                            val mediaType = MediaType()
                            mediaType.schema = toOpenApiSchema(responseBodyType)
                            Pair("text/plain", mediaType)
                        }
                    }

                    if(responseBodySchema != null)
                        this.addMediaType(responseBodySchema.first, responseBodySchema.second)
                }
            }

            responses.addApiResponse(scenario.httpResponsePattern.status.toString(), apiResponse)

            operation.responses = responses

            when (scenario.httpRequestPattern.method) {
                "GET" -> path.get = operation
                "POST" -> path.post = operation
                "DELETE" -> path.delete = operation
            }
            val pathName = scenario.httpRequestPattern.urlMatcher!!.toOpenApiPath()
            pathName to path
        }

        val schemas: Map<String, Pattern> = this.scenarios.map {
            it.patterns.entries
        }.flatten().fold(emptyMap<String, Pattern>()) { acc, entry ->
            if(acc.contains(entry.key) && isObjectType(acc.getValue(entry.key))) {
                val converged: Map<String, Pattern> = objectStructure(acc.getValue(entry.key))
                val new: Map<String, Pattern> = objectStructure(entry.value)

                acc.plus(entry.key to TabularPattern(converge(converged, new)))
            }
            else {
                acc.plus(entry.key to entry.value)
            }
        }.mapKeys {
            withoutPatternDelimiters(it.key)
        }

        if(schemas.isNotEmpty()) {
            openAPI.components = Components()
            openAPI.components.schemas = schemas.mapValues {
                toOpenApiSchema(it.value)
            }
        }

        openAPI.paths = Paths().also {
            paths.forEach { (pathName, newPath) ->
                it.addPathItem(pathName, newPath)
            }
        }

//        openAPI.paths = Paths().also {
//            paths.forEach { (pathName, newPath) ->
//                if(it.contains(pathName)) {
//                    val existingPath = it.getValue(pathName)
//                    if(existingPath.get == null)
//                        existingPath.get = newPath.get
//                    else {
//                        existingPath.get.requestBody = newPath.get.requestBody
//
//                        newPath.get.responses.forEach { (responseType, response) ->
//                            existingPath.get.responses[responseType] = response
//                        }
//                    }
//                } else {
//                    it.addPathItem(pathName, newPath)
//                }
//            }
//        }

        return openAPI
    }

    private fun jsonMediaType(requestBodyType: Pattern): Pair<String, MediaType> {
        val mediaType = MediaType()
        mediaType.schema = toOpenApiSchema(requestBodyType)
        return Pair("application/json", mediaType)
    }

    private fun converge(map1: Map<String, Pattern>, map2: Map<String, Pattern>): Map<String, Pattern> {
        val common = map1.filter { entry ->
            val cleanedKey = withoutOptionality(entry.key)
            cleanedKey in map2 || "${cleanedKey}?" in map2
        }.mapKeys { entry ->
            val cleanedKey = withoutOptionality(entry.key)
            if(isOptional(entry.key) || "${cleanedKey}?" in map2) {
                "${cleanedKey}?"
            } else
                cleanedKey
        }.toMap()

        val onlyInMap1 = map1.filter { entry ->
            val cleanedKey = withoutOptionality(entry.key)
            (cleanedKey !in common && "${cleanedKey}?" !in common)
        }.mapKeys { entry ->
            val cleanedKey = withoutOptionality(entry.key)
            "${cleanedKey}?"
        }

        val onlyInMap2 = map2.filter { entry ->
            val cleanedKey = withoutOptionality(entry.key)
            (cleanedKey !in common && "${cleanedKey}?" !in common)
        }.mapKeys { entry ->
            val cleanedKey = withoutOptionality(entry.key)
            "${cleanedKey}?"
        }

        return common.plus(onlyInMap1).plus(onlyInMap2)
    }

    private fun objectStructure(objectType: Pattern): Map<String, Pattern> {
        return when (objectType) {
            is TabularPattern -> objectType.pattern
            is JSONObjectPattern -> objectType.pattern
            else -> throw ContractException("Unrecognized type ${objectType.typeName}")
        }
    }

    private fun isObjectType(type: Pattern): Boolean = type is TabularPattern || type is JSONObjectPattern

    private fun isJSONPayload(type: Pattern) =
        type is TabularPattern || type is JSONObjectPattern || type is JSONArrayPattern

    private fun toOpenApiSchema(pattern: Pattern): Schema<Any> {
        val schema = when {
            pattern is TabularPattern -> tabularToSchema(pattern)
            pattern is JSONObjectPattern -> jsonObjectToSchema(pattern)
            isArrayOfNullables(pattern) -> {
                ArraySchema().apply {
                    this.items = Schema<Any>().apply {
                        val typeAlias = ((pattern as ListPattern).pattern as AnyPattern).pattern.first { it.typeAlias != "(empty)" }.let {
                            if(it.pattern is String && primitiveTypes.contains(withoutPatternDelimiters(it.pattern.toString())))
                                withoutPatternDelimiters(it.pattern as String)
                            else
                                it.typeAlias ?: throw ContractException("Unknown type: $it")
                        }

                        setSchemaType(withoutPatternDelimiters(typeAlias), this)
                        this.nullable = true
                    }
                }
            }
            isNullableArray(pattern) -> {
                ArraySchema().apply {
                    pattern as AnyPattern

                    this.items = Schema<Any>().apply {
                        setSchemaType(withoutPatternDelimiters(pattern.pattern.first { it.typeAlias != "(empty)" }.let {
                            it as ListPattern
                            it.pattern.typeAlias!!
                        }), this)
                    }
                    this.nullable = true
                }
            }
            isNullable(pattern) -> {
                pattern as AnyPattern

                Schema<Any>().apply {
                    setSchemaType(withoutPatternDelimiters(pattern.pattern.first { it.typeAlias != "(empty)" }.typeAlias!!), this)
                    this.nullable = true
                }
            }
            pattern is ListPattern -> {
                if(pattern.pattern is DeferredPattern) {
                    ArraySchema().apply {
                        this.items = Schema<Any>().apply {
                            setSchemaType(pattern.pattern.typeAlias!!, this)
                        }
                    }
                } else if (isArrayOfNullables(pattern)) {
                    TODO("Array of nullables not yet supported")
                } else {
                    ArraySchema().apply {
                        this.items = toOpenApiSchema(pattern.pattern)
                    }
                }
            }
            pattern is NumberPattern || (pattern is DeferredPattern && pattern.pattern == "(number)") -> NumberSchema()
            pattern is BooleanPattern || (pattern is DeferredPattern && pattern.pattern == "(boolean)") -> BooleanSchema()
            pattern is StringPattern || pattern is EmptyStringPattern || (pattern is DeferredPattern && pattern.pattern == "(string)") || (pattern is DeferredPattern && pattern.pattern == "(nothing)") -> StringSchema()
            pattern is NullPattern || (pattern is DeferredPattern && pattern.pattern == "(null)") -> StringSchema().apply {
                this.nullable = true
            }.also {
                information.forTheUser("Specmatic encountered a (null) in the spec. OpenAPI does not support raw nulls. Data types may be nullable, but you must specify the data type. Please make sure you specify the nullable datatype wherever null is encountered in the contract.")
            }
            pattern is DeferredPattern -> Schema<Any>().apply {
                this.`$ref` = withoutPatternDelimiters(pattern.pattern)
            }
            pattern is LookupRowPattern -> toOpenApiSchema(pattern.pattern)
            else ->
                TODO("Not supported: ${pattern.typeAlias ?: pattern.typeName}")
        }

        return schema as Schema<Any>;
    }

    private fun setSchemaType(type: String, schema: Schema<Any>) {
        val cleanedUpType = withoutPatternDelimiters(type)
        if(primitiveTypes.contains(cleanedUpType))
            schema.type = cleanedUpType
        else
            schema.`$ref` = cleanedUpType
    }

    private fun isNullableArray(pattern: Pattern): Boolean =
        isNullable(pattern) && pattern is AnyPattern && pattern.pattern.first { it.typeAlias != "(empty)" } is ListPattern

    private fun isArrayOfNullables(pattern: Pattern) =
        pattern is ListPattern && pattern.pattern is AnyPattern && isNullable(pattern.pattern)

    private fun isNullable(pattern: Pattern) =
        pattern is AnyPattern && pattern.pattern.any { it.typeAlias == "(empty)" }

    private fun jsonObjectToSchema(pattern: JSONObjectPattern): Schema<Any> = jsonToSchema(pattern.pattern)
    private fun tabularToSchema(pattern: TabularPattern): Schema<Any> = jsonToSchema(pattern.pattern)

    private fun jsonToSchema(pattern: Map<String, Pattern>): Schema<Any> {
        val schema = Schema<Any>()

        schema.required = pattern.keys.filterNot { it.endsWith("?") }

        val properties: Map<String, Schema<Any>> = pattern.mapValues { (key, valueType) ->
            toOpenApiSchema(valueType)
        }.mapKeys { withoutOptionality(it.key) }

        schema.properties = properties

        return schema
    }
}

private fun toFixtureInfo(rest: String): Pair<String, Value> {
    val fixtureTokens = breakIntoPartsMaxLength(rest.trim(), 2)

    if (fixtureTokens.size != 2)
        throw ContractException("Couldn't parse fixture data: $rest")

    return Pair(fixtureTokens[0], toFixtureData(fixtureTokens[1]))
}

private fun toFixtureData(rawData: String): Value = parsedJSON(rawData)

internal fun stringOrDocString(string: String?, step: StepInfo): String {
    val trimmed = string?.trim() ?: ""
    return trimmed.ifEmpty { step.docString }
}

private fun toPatternInfo(step: StepInfo, rowsList: List<TableRow>): Pair<String, Pattern> {
    val tokens = breakIntoPartsMaxLength(step.rest, 2)

    val patternName = withPatternDelimiters(tokens[0])

    val patternDefinition = stringOrDocString(tokens.getOrNull(1), step)

    val pattern = when {
        patternDefinition.isEmpty() -> rowsToTabularPattern(rowsList, typeAlias = patternName)
        else -> parsedPattern(patternDefinition, typeAlias = patternName)
    }

    return Pair(patternName, pattern)
}

private fun toFacts(rest: String, fixtures: Map<String, Value>): Map<String, Value> {
    return try {
        jsonStringToValueMap(rest)
    } catch (notValidJSON: Exception) {
        val factTokens = breakIntoPartsMaxLength(rest, 2)
        val name = factTokens[0]
        val data = factTokens.getOrNull(1)?.let { StringValue(it) } ?: fixtures.getOrDefault(name, True)

        mapOf(name to data)
    }
}

private fun lexScenario(
    steps: List<Step>,
    examplesList: List<Examples>,
    featureTags: List<Tag>,
    backgroundScenarioInfo: ScenarioInfo?,
    filePath: String,
    includedSpecifications: List<IncludedSpecification?>
): ScenarioInfo {
    val filteredSteps =
        steps.map { step -> StepInfo(step.text, listOfDatatableRows(step), step) }.filterNot { it.isEmpty }

    val parsedScenarioInfo = filteredSteps.fold(backgroundScenarioInfo ?: ScenarioInfo()) { scenarioInfo, step ->
        when (step.keyword) {
            in HTTP_METHODS -> {
                step.words.getOrNull(1)?.let {
                    val urlMatcher = try {
                        toURLMatcherWithOptionalQueryParams(URI.create(step.rest))
                    } catch (e: Throwable) {
                        throw Exception(
                            "Could not parse the contract URL \"${step.rest}\" in scenario \"${scenarioInfo.scenarioName}\"",
                            e
                        )
                    }

                    scenarioInfo.copy(
                        httpRequestPattern = scenarioInfo.httpRequestPattern.copy(
                            urlMatcher = urlMatcher,
                            method = step.keyword.uppercase()
                        )
                    )
                } ?: throw ContractException("Line ${step.line}: $step.text")
            }
            "REQUEST-HEADER" ->
                scenarioInfo.copy(
                    httpRequestPattern = scenarioInfo.httpRequestPattern.copy(
                        headersPattern = plusHeaderPattern(
                            step.rest,
                            scenarioInfo.httpRequestPattern.headersPattern
                        )
                    )
                )
            "RESPONSE-HEADER" ->
                scenarioInfo.copy(
                    httpResponsePattern = scenarioInfo.httpResponsePattern.copy(
                        headersPattern = plusHeaderPattern(
                            step.rest,
                            scenarioInfo.httpResponsePattern.headersPattern
                        )
                    )
                )
            "STATUS" ->
                scenarioInfo.copy(
                    httpResponsePattern = scenarioInfo.httpResponsePattern.copy(
                        status = Integer.valueOf(
                            step.rest
                        )
                    )
                )
            "REQUEST-BODY" ->
                scenarioInfo.copy(httpRequestPattern = scenarioInfo.httpRequestPattern.copy(body = toPattern(step)))
            "RESPONSE-BODY" ->
                scenarioInfo.copy(httpResponsePattern = scenarioInfo.httpResponsePattern.bodyPattern(toPattern(step)))
            "FACT" ->
                scenarioInfo.copy(
                    expectedServerState = scenarioInfo.expectedServerState.plus(
                        toFacts(
                            step.rest,
                            scenarioInfo.fixtures
                        )
                    )
                )
            "TYPE", "PATTERN", "JSON" ->
                scenarioInfo.copy(patterns = scenarioInfo.patterns.plus(toPatternInfo(step, step.rowsList)))
            "ENUM" ->
                scenarioInfo.copy(patterns = scenarioInfo.patterns.plus(parseEnum(step)))
            "FIXTURE" ->
                scenarioInfo.copy(fixtures = scenarioInfo.fixtures.plus(toFixtureInfo(step.rest)))
            "FORM-FIELD" ->
                scenarioInfo.copy(
                    httpRequestPattern = scenarioInfo.httpRequestPattern.copy(
                        formFieldsPattern = plusFormFields(
                            scenarioInfo.httpRequestPattern.formFieldsPattern,
                            step.rest,
                            step.rowsList
                        )
                    )
                )
            "REQUEST-PART" ->
                scenarioInfo.copy(
                    httpRequestPattern = scenarioInfo.httpRequestPattern.copy(
                        multiPartFormDataPattern = scenarioInfo.httpRequestPattern.multiPartFormDataPattern.plus(
                            toFormDataPart(step, filePath)
                        )
                    )
                )
            "KAFKA-MESSAGE" ->
                scenarioInfo.copy(kafkaMessage = toAsyncMessage(step))
            "VALUE" ->
                scenarioInfo.copy(
                    references = values(
                        step.rest,
                        scenarioInfo.references,
                        backgroundScenarioInfo?.references ?: emptyMap(),
                        filePath
                    )
                )
            "EXPORT" ->
                scenarioInfo.copy(
                    bindings = setters(
                        step.rest,
                        backgroundScenarioInfo?.bindings ?: emptyMap(),
                        scenarioInfo.bindings
                    )
                )
            else -> {
                val location = when (step.raw.location) {
                    null -> ""
                    else -> " at line ${step.raw.location.line}"
                }

                throw ContractException("""Invalid syntax$location: ${step.raw.keyword.trim()} ${step.raw.text} -> keyword "${step.originalKeyword}" not recognised.""")
            }
        }
    }

    val tags = featureTags.map { tag -> tag.name }
    val ignoreFailure = when {
        tags.asSequence().map { it.uppercase() }.contains("@WIP") -> true
        else -> false
    }

    return if (includedSpecifications.isEmpty() || backgroundScenarioInfo == null) {
        scenarioInfoWithExamples(
            parsedScenarioInfo,
            backgroundScenarioInfo ?: ScenarioInfo(),
            examplesList,
            ignoreFailure
        )
    } else {
        val matchingScenarios: List<ScenarioInfo> = includedSpecifications.mapNotNull {
            it?.matches(parsedScenarioInfo, steps).orEmpty()
        }.flatten()

        if (matchingScenarios.size > 1) throw ContractException("Scenario: ${parsedScenarioInfo.scenarioName} is not specific, it matches ${matchingScenarios.size} in the included Wsdl / OpenApi")

        scenarioInfoWithExamples(matchingScenarios.first(), backgroundScenarioInfo, examplesList, ignoreFailure)
    }
}

private fun listOfDatatableRows(it: Step) = it.dataTable?.rows ?: mutableListOf()

fun parseEnum(step: StepInfo): Pair<String, Pattern> {
    val tokens = step.text.split(" ")

    if (tokens.size < 5)
        throw ContractException("Enum syntax error in step at line ${step.raw.location.line}. Syntax should be Given(/When/Then) enum EnumName <TypeName> values choice1,choice2,choice3")
    val enumName = tokens[1]
    val enumValues = tokens[4].split(",")
    val enumType = tokens[2]
    val exactValuePatterns = enumValues.map { enumValue ->
        val enumPattern = parsedPattern(enumType).run {
            when (this) {
                is DeferredPattern -> this.resolvePattern(Resolver())
                is AnyPattern -> throw ContractException("Enums ${enumName} type $enumType cannot be nullable. To mark the enum nullable please use it with nullable syntax. Suggested Usage: (${enumName}?)")
                else -> this
            }
        }
        ExactValuePattern(
            when (enumPattern) {
                is StringPattern -> StringValue(enumValue)
                is NumberPattern -> NumberValue(enumValue.toInt())
                else -> throw ContractException("Enums can only be of type String or Number")
            }
        )
    }
    return Pair("($enumName)", AnyPattern(exactValuePatterns))
}

private fun scenarioInfoWithExamples(
    parsedScenarioInfo: ScenarioInfo,
    backgroundScenarioInfo: ScenarioInfo,
    examplesList: List<Examples>,
    ignoreFailure: Boolean
) = parsedScenarioInfo.copy(
    examples = backgroundScenarioInfo.examples.plus(examplesFrom(examplesList)),
    bindings = backgroundScenarioInfo.bindings.plus(parsedScenarioInfo.bindings),
    references = backgroundScenarioInfo.references.plus(parsedScenarioInfo.references),
    ignoreFailure = ignoreFailure
)

fun setters(
    rest: String,
    backgroundSetters: Map<String, String>,
    scenarioSetters: Map<String, String>
): Map<String, String> {
    val parts = breakIntoPartsMaxLength(rest, 3)

    if (parts.size != 3 || parts[1] != "=")
        throw ContractException("Setter syntax is incorrect in \"$rest\". Syntax should be \"Then set <variable> = <selector>\"")

    val variableName = parts[0]
    val selector = parts[2]

    return backgroundSetters.plus(scenarioSetters).plus(variableName to selector)
}

fun values(
    rest: String,
    scenarioReferences: Map<String, References>,
    backgroundReferences: Map<String, References>,
    filePath: String
): Map<String, References> {
    val parts = breakIntoPartsMaxLength(rest, 3)

    if (parts.size != 3 || parts[1] != "from")
        throw ContractException("Incorrect syntax for value statement: $rest - it should be \"Given value <value name> from <$APPLICATION_NAME file name>\"")

    val valueStoreName = parts[0]
    val qontractFileName = parts[2]

    val qontractFilePath = ContractFileWithExports(qontractFileName, AnchorFile(filePath))

    return backgroundReferences.plus(scenarioReferences).plus(
        valueStoreName to References(
            valueStoreName,
            qontractFilePath,
            contractCache = contractCache
        )
    )
}

private val contractCache = ContractCache()

fun toAsyncMessage(step: StepInfo): KafkaMessagePattern {
    val parts = breakIntoPartsMaxLength(step.rest, 3)

    return when (parts.size) {
        2 -> {
            val (name, type) = parts
            KafkaMessagePattern(name, value = parsedPattern(type))
        }
        3 -> {
            val (name, key, contentType) = parts
            KafkaMessagePattern(name, parsedPattern(key), parsedPattern(contentType))
        }
        else -> throw ContractException("The message keyword must have either 2 params (topic, value) or 3 (topic, key, value)")
    }
}

fun toFormDataPart(step: StepInfo, contractFilePath: String): MultiPartFormDataPattern {
    val parts = breakIntoPartsMaxLength(step.rest, 4)

    if (parts.size < 2)
        throw ContractException("There must be at least 2 words after request-part in $step.line")

    val (name, content) = parts.slice(0..1)

    return when {
        content.startsWith("@") -> {
            val contentType = parts.getOrNull(2)
            val contentEncoding = parts.getOrNull(3)

            val multipartFilename = content.removePrefix("@")

            val expandedFilenamePattern = when (val filenamePattern = parsedPattern(multipartFilename)) {
                is ExactValuePattern -> {
                    val multipartFilePath =
                        File(contractFilePath).absoluteFile.parentFile.resolve(multipartFilename).absolutePath
                    ExactValuePattern(StringValue(multipartFilePath))
                }
                else ->
                    filenamePattern
            }

            MultiPartFilePattern(name, expandedFilenamePattern, contentType, contentEncoding)
        }
        isPatternToken(content) -> {
            MultiPartContentPattern(name, parsedPattern(content))
        }
        else -> {
            MultiPartContentPattern(name, ExactValuePattern(parsedValue(content)))
        }
    }
}

fun toPattern(step: StepInfo): Pattern {
    return when (val stringData = stringOrDocString(step.rest, step)) {
        "" -> {
            if (step.rowsList.isEmpty()) throw ContractException("Not enough information to describe a type in $step")
            rowsToTabularPattern(step.rowsList)
        }
        else -> parsedPattern(stringData)
    }
}

fun plusFormFields(
    formFields: Map<String, Pattern>,
    rest: String,
    rowsList: List<TableRow>
): Map<String, Pattern> =
    formFields.plus(when (rowsList.size) {
        0 -> toQueryParams(rest).map { (key, value) -> key to value }
        else -> rowsList.map { row -> row.cells[0].value to row.cells[1].value }
    }.associate { (key, value) -> key to parsedPattern(value) }
    )

private fun toQueryParams(rest: String) = rest.split("&")
    .map { breakIntoPartsMaxLength(it, 2) }

fun plusHeaderPattern(rest: String, headersPattern: HttpHeadersPattern): HttpHeadersPattern {
    val parts = breakIntoPartsMaxLength(rest, 2)

    return when (parts.size) {
        2 -> headersPattern.copy(pattern = headersPattern.pattern.plus(toPatternPair(parts[0], parts[1])))
        1 -> throw ContractException("Header $parts[0] should have a value")
        else -> throw ContractException("Unrecognised header params $rest")
    }
}

fun toPatternPair(key: String, value: String): Pair<String, Pattern> = key to parsedPattern(value)

fun breakIntoPartsMaxLength(whole: String, partCount: Int) = whole.split("\\s+".toRegex(), partCount)
fun breakIntoPartsMaxLength(whole: String, separator: String, partCount: Int) =
    whole.split(separator.toRegex(), partCount)

private val HTTP_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

fun parseGherkinString(gherkinData: String, sourceFilePath: String): GherkinDocument {
    return parseGherkinString(gherkinData)
        ?: throw ContractException("There was no contract in the file $sourceFilePath.")
}

internal fun parseGherkinString(gherkinData: String): GherkinDocument? {
    val idGenerator: IdGenerator = Incrementing()
    val parser = Parser(GherkinDocumentBuilder(idGenerator))
    return parser.parse(gherkinData)
}

internal fun lex(gherkinDocument: GherkinDocument, filePath: String = ""): Pair<String, List<Scenario>> =
    Pair(gherkinDocument.feature.name, lex(gherkinDocument.feature.children, filePath))

internal fun lex(featureChildren: List<FeatureChild>, filePath: String): List<Scenario> {
    return scenarioInfos(featureChildren, filePath)
        .map { scenarioInfo ->
            Scenario(
                scenarioInfo.scenarioName,
                scenarioInfo.httpRequestPattern,
                scenarioInfo.httpResponsePattern,
                scenarioInfo.expectedServerState,
                scenarioInfo.examples,
                scenarioInfo.patterns,
                scenarioInfo.fixtures,
                scenarioInfo.kafkaMessage,
                scenarioInfo.ignoreFailure,
                scenarioInfo.references,
                scenarioInfo.bindings
            )
        }
}

fun scenarioInfos(
    featureChildren: List<FeatureChild>,
    filePath: String
): List<ScenarioInfo> {
    val openApiSpecification =
        toIncludedSpecification(featureChildren, { backgroundOpenApi(it) }) {
            OpenApiSpecification.fromFile(
                it,
                filePath
            )
        }

    val wsdlSpecification =
        toIncludedSpecification(featureChildren, { backgroundWsdl(it) }) { WsdlSpecification(WSDLFile(it)) }

    val includedSpecifications = listOfNotNull(openApiSpecification, wsdlSpecification)

    val scenarioInfosBelongingToIncludedSpecifications =
        includedSpecifications.mapNotNull { it.toScenarioInfos() }.flatten()

    val backgroundInfo = backgroundScenario(featureChildren)?.let { feature ->
        lexScenario(
            feature.background.steps
                .filter { !it.text.contains("openapi", true) }
                .filter { !it.text.contains("wsdl", true) },
            listOf(),
            emptyList(),
            null,
            filePath,
            includedSpecifications
        )
    } ?: ScenarioInfo()

    val specmaticScenarioInfos = scenarios(featureChildren).map { featureChild ->
        if (featureChild.scenario.name.isBlank())
            throw ContractException("Error at line ${featureChild.scenario.location.line}: scenario name must not be empty")

        val backgroundInfoCopy = backgroundInfo.copy(scenarioName = featureChild.scenario.name)

        lexScenario(
            featureChild.scenario.steps,
            featureChild.scenario.examples,
            featureChild.scenario.tags,
            backgroundInfoCopy,
            filePath,
            includedSpecifications
        )
    }

    return specmaticScenarioInfos.plus(scenarioInfosBelongingToIncludedSpecifications.filter { scenarioInfo ->
        !specmaticScenarioInfos.any {
            it.httpResponsePattern.status == scenarioInfo.httpResponsePattern.status
        }
    })
}

private fun toIncludedSpecification(
    featureChildren: List<FeatureChild>,
    selector: (List<FeatureChild>) -> Step?,
    creator: (String) -> IncludedSpecification
): IncludedSpecification? =
    selector(featureChildren)?.run { creator(text.split(" ")[1]) }

private fun backgroundScenario(featureChildren: List<FeatureChild>) =
    featureChildren.firstOrNull { it.background != null }

private fun backgroundOpenApi(featureChildren: List<FeatureChild>): Step? {
    return backgroundScenario(featureChildren)?.let { background ->
        background.background.steps.firstOrNull {
            it.keyword.contains("Given", true)
                    && it.text.contains("openapi", true)
        }
    }
}

private fun backgroundWsdl(featureChildren: List<FeatureChild>): Step? {
    return backgroundScenario(featureChildren)?.let { background ->
        background.background.steps.firstOrNull {
            it.keyword.contains("Given", true)
                    && it.text.contains("wsdl", true)
        }
    }
}

private fun scenarios(featureChildren: List<FeatureChild>) =
    featureChildren.filter { it.background == null }

fun toGherkinFeature(stub: NamedStub): String = toGherkinFeature("New Feature", listOf(stub))

private fun stubToClauses(namedStub: NamedStub): Pair<List<GherkinClause>, ExampleDeclarations> {
    return when (namedStub.stub.kafkaMessage) {
        null -> {
            val (requestClauses, typesFromRequest, examples) = toGherkinClauses(namedStub.stub.request)

            for (message in examples.messages) {
                information.forTheUser(message)
            }

            val (responseClauses, allTypes, _) = toGherkinClauses(namedStub.stub.response, typesFromRequest)
            val typeClauses = toGherkinClauses(allTypes)
            Pair(typeClauses.plus(requestClauses).plus(responseClauses), examples)
        }
        else -> Pair(toGherkinClauses(namedStub.stub.kafkaMessage), UseExampleDeclarations())
    }
}

data class GherkinScenario(val scenarioName: String, val clauses: List<GherkinClause>)

fun toGherkinFeature(featureName: String, stubs: List<NamedStub>): String {
    val groupedStubs = stubs.map { stub ->
        val (clauses, examples) = stubToClauses(stub)
        val commentedExamples = addCommentsToExamples(examples, stub)

        Pair(GherkinScenario(stub.name, clauses), listOf(commentedExamples))
    }.fold(emptyMap<GherkinScenario, List<ExampleDeclarations>>()) { groups, (scenario, examples) ->
        groups.plus(scenario to groups.getOrDefault(scenario, emptyList()).plus(examples))
    }

    val scenarioStrings = groupedStubs.map { (nameAndClauses, examplesList) ->
        val (name, clauses) = nameAndClauses

        toGherkinScenario(name, clauses, examplesList)
    }

    return withFeatureClause(featureName, scenarioStrings.joinToString("\n\n"))
}

private fun addCommentsToExamples(examples: ExampleDeclarations, stub: NamedStub): ExampleDeclarations {
    val date = stub.stub.response.headers["Date"]
    return examples.withComment(date)
}
