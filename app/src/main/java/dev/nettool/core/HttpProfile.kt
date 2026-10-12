package dev.nettool.core

object HttpProfile {
    val defaults=mapOf("User-Agent" to "SunNetTool/0.4", "Accept" to "*/*", "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8")
    val browser=defaults+mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36", "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    fun validate(headers:Map<String,String>):Map<String,String> {
        require(headers.keys.all {it in setOf("User-Agent","Accept","Accept-Language","Cache-Control")}) {"Cabeçalho não permitido neste perfil"}
        require(headers.values.all {v->v.length<=1024 && v.all {it.code in 32..126}}) {"Use até 1.024 caracteres ASCII por cabeçalho, sem quebras de linha"}
        return headers.filterValues {it.isNotBlank()}
    }
}
