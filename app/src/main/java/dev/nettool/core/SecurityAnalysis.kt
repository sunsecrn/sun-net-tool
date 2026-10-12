package dev.nettool.core

import org.json.JSONObject

/** Passive interpretation of observed responses. Never confirms exploitability. */
object SecurityAnalysis {
    data class Finding(val kind:String,val title:String,val details:Map<String,String>)
    private data class Product(val label:String,val vendor:String,val name:String,val pattern:Regex)
    private val products=listOf(
        Product("Apache HTTP Server","apache","http_server",Regex("(?i)(?<![a-z])Apache/([0-9]+(?:\\.[0-9]+){1,3})(?![a-z0-9.])")),
        Product("nginx","f5","nginx",Regex("(?i)(?<![a-z])nginx/([0-9]+(?:\\.[0-9]+){1,3})(?![a-z0-9.])")),
        Product("PHP","php","php",Regex("(?i)(?<![a-z])PHP/([0-9]+(?:\\.[0-9]+){1,3})(?![a-z0-9.])")),
        Product("OpenSSH","openbsd","openssh",Regex("OpenSSH_([0-9]+(?:\\.[0-9]+){1,3})(p[0-9]+)?(?![a-z0-9.])"))
    )
    fun versions(value:String,source:String):List<Finding> = products.mapNotNull {p->
        val match=p.pattern.find(value.take(4096)) ?: return@mapNotNull null
        val version=match.groupValues[1]
        val update=if(p.name=="openssh") match.groupValues.getOrNull(2).orEmpty().ifEmpty {"*"} else "*"
        Finding("version","${p.label} $version${if(update=="*") "" else update} anunciado",mapOf(
            "product" to p.label,"version" to version,"cpe" to "cpe:2.3:a:${p.vendor}:${p.name}:$version:$update:*:*:*:*:*:*",
            "source" to source,"evidence" to match.value,"confidence" to "Versão declarada pelo servidor, não verificada",
            "meaning" to "Um proxy pode anunciar sua própria versão. Correções locais e condições de exploração não foram verificadas."))
    }
    fun headers(url:String,headers:Map<String,String>,cookies:List<String>,complete:Boolean):List<Finding> {
        val h=headers.mapKeys {it.key.lowercase()};val out=mutableListOf<Finding>()
        listOf("server","x-powered-by").forEach {key->h[key]?.let {out+=versions(it,"Header $key")}}
        fun review(name:String,reason:String,value:String?) {
            out+=Finding("security-header",reason,mapOf("header" to name,"evidence" to (value ?: "Ausente na resposta coletada"),"source" to "Resposta HTTP","assessment" to "Revisar configuração","reference" to "https://owasp.org/www-project-secure-headers/","meaning" to "Observação desta resposta; não comprova vulnerabilidade explorável."))
        }
        val html=h["content-type"].orEmpty().contains("text/html",true)
        if(url.startsWith("https://")) {
            val sts=h["strict-transport-security"]
            if(sts==null && complete) review("Strict-Transport-Security","HSTS ausente na resposta HTTPS",null)
            else if(sts!=null && (Regex("(?i)(?:^|;)\\s*max-age\\s*=\\s*([0-9]+)").find(sts)?.groupValues?.get(1)?.toLongOrNull() ?: 0)<=0) review("Strict-Transport-Security","HSTS sem duração positiva reconhecida",sts)
        }
        if(html) {
            val csp=h["content-security-policy"]
            if(csp==null && complete) review("Content-Security-Policy","Política CSP não informada por header",null)
            val frame=h["x-frame-options"]
            val ancestors=csp?.split(';')?.any {it.trim().startsWith("frame-ancestors ",true)}==true
            if(!ancestors && complete && frame?.trim()?.uppercase() !in setOf("DENY","SAMEORIGIN")) review("X-Frame-Options / CSP frame-ancestors","Proteção contra enquadramento não reconhecida",frame)
            val sniff=h["x-content-type-options"]
            if(complete && !sniff.equals("nosniff",true)) review("X-Content-Type-Options","Proteção nosniff não reconhecida",sniff)
        }
        cookies.take(40).forEachIndexed {index,cookie->
            val attrs=cookie.split(';').drop(1).map {it.trim().substringBefore('=').lowercase()}.toSet()
            val missing=buildList {if(url.startsWith("https://")&&"secure" !in attrs) add("Secure");if("httponly" !in attrs) add("HttpOnly");if("samesite" !in attrs) add("SameSite")}
            if(missing.isNotEmpty()) review("Set-Cookie","Cookie ${index+1}: revisar atributos", "Atributos ausentes: ${missing.joinToString()}. Nome e valor omitidos. A necessidade depende da finalidade do cookie.")
        }
        out+=Finding("security-header","Análise de headers concluída",mapOf("assessment" to "Informativo","source" to "Resposta HTTP","evidence" to "${h.size} headers observados; coleta ${if(complete) "completa" else "parcial"}","meaning" to "Somente HSTS em HTTPS, CSP/enquadramento/nosniff em HTML e atributos de cookies. Ausência de alertas não significa segurança comprovada; políticas HTML e contexto da aplicação não foram avaliados."))
        return out
    }
    data class CvePage(val total:Int,val findings:List<Finding>)
    fun cves(body:String):CvePage {
        val root=JSONObject(body);require(root.has("totalResults")&&root.has("vulnerabilities")) {"Resposta NVD inválida"}
        val a=root.getJSONArray("vulnerabilities")
        val findings=(0 until minOf(a.length(),50)).mapNotNull {i->
            val c=a.getJSONObject(i).getJSONObject("cve");val id=c.getString("id")
            if(!id.matches(Regex("CVE-[0-9]{4}-[0-9]{4,}"))||c.optString("vulnStatus")=="Rejected") return@mapNotNull null
            val descriptions=c.optJSONArray("descriptions")
            val description=(0 until (descriptions?.length() ?: 0)).map {descriptions!!.getJSONObject(it)}.firstOrNull {it.optString("lang")=="en"}?.optString("value").orEmpty().take(3000)
            val metrics=c.optJSONObject("metrics")
            val metric=listOf("cvssMetricV40","cvssMetricV31","cvssMetricV30","cvssMetricV2").firstNotNullOfOrNull {metrics?.optJSONArray(it)?.optJSONObject(0)?.optJSONObject("cvssData")}
            Finding("cve","$id: possível correspondência",mapOf("cve" to id,"severity" to (metric?.optString("baseSeverity") ?: "Não informada"),"cvss" to (metric?.optString("baseScore") ?: "Não informado"),"description" to description,"reference" to "https://nvd.nist.gov/vuln/detail/$id","source" to "NVD, consulta por CPE exato","modified" to c.optString("lastModified"),"meaning" to "Correspondência de produto/versão, não confirmação. Sistema, módulos, configuração e correções aplicadas precisam ser verificados."))
        }
        return CvePage(root.getInt("totalResults"),findings)
    }
}
