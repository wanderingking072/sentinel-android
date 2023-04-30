package com.samourai.sentinel.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class TokenAuthenticator(private val apiService: ApiService) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        runBlocking {
            apiService.authenticateDojo()
            delay(100)
        }
        return null
    }
}
