package com.veer.assistant

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal fun interface VeerClaudeAgentTransport { fun generate(apiKey:String,turn:AgentTurn):String }

internal class VeerClaudeHttpTransport(
    private val model:String=VeerClaudeAgentRequest.DEFAULT_MODEL,
    private val connectionFactory:(URL)->HttpURLConnection={it.openConnection() as HttpURLConnection}
):VeerClaudeAgentTransport{
    override fun generate(apiKey:String,turn:AgentTurn):String{
        require(apiKey.isNotBlank())
        val body=VeerClaudeAgentRequest.build(turn,model).toString()
        val conn=connectionFactory(URL("https://api.anthropic.com/v1/messages"))
        try{
            conn.requestMethod="POST";conn.connectTimeout=10_000;conn.readTimeout=25_000;conn.doOutput=true
            conn.setRequestProperty("Content-Type","application/json; charset=UTF-8")
            conn.setRequestProperty("anthropic-version","2023-06-01");conn.setRequestProperty("x-api-key",apiKey)
            conn.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}
            val code=conn.responseCode;val stream=if(code in 200..299)conn.inputStream else conn.errorStream
            val response=stream?.use{VeerBoundedHttpBody.readUtf8(it)}?: ""
            if(code !in 200..299)throw AgentProviderException("Anthropic request failed (HTTP $code).",code==408||code==429||code in 500..599)
            if(response.isBlank())throw IllegalStateException("Anthropic returned empty response.")
            return response
        }catch(e:IOException){throw AgentProviderException("Anthropic network request failed.",true)}
        finally{conn.disconnect()}
    }
}