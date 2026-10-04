package com.webuntis.dashboard.api

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * WebUntis' public school search — the same endpoint the official WebUntis / Untis Mobile apps
 * use to turn "type your school's name" into the server + school-login-name pair. No account
 * or session needed (it runs on mobile.webuntis.com, not on the school's own server).
 */
interface SchoolSearchService {
    @POST("ms/schoolquery2")
    suspend fun searchSchool(@Body body: SchoolSearchRequest): SchoolSearchResponse
}

@Keep
data class SchoolSearchRequest(
    val id: String = "a_untis_school_search",
    val method: String = "searchSchool",
    val params: List<SchoolSearchParams>,
    val jsonrpc: String = "2.0"
)

@Keep
data class SchoolSearchParams(val search: String)

@Keep
data class SchoolSearchResponse(
    val result: SchoolSearchResult? = null,
    /** Set instead of [result], e.g. when the query matches too many schools. */
    val error: SchoolSearchError? = null
)

@Keep
data class SchoolSearchError(val code: Int? = null, val message: String? = null)

@Keep
data class SchoolSearchResult(val schools: List<School>? = null)

@Keep
data class School(
    val displayName: String? = null,
    /** The value for the "school" parameter / the login screen's "Schul-Kurzname". */
    val loginName: String? = null,
    /** Host name, e.g. "xyz.webuntis.com". */
    val server: String? = null,
    val serverUrl: String? = null,
    val address: String? = null
) {
    /** Host without scheme/path — what the login screen's server field expects. */
    val serverHost: String?
        get() = (server?.takeIf { it.isNotBlank() } ?: serverUrl)
            ?.trim()
            ?.removePrefix("https://")?.removePrefix("http://")
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
}
