package dev.nettool

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal fun evidenceLabel(kind:String)=when(kind) {
    "location"->"Localização da sessão";"version"->"Versão anunciada";"security-header"->"Headers de segurança";"cve"->"Possível CVE";"cve-check"->"Consulta de CVEs";"http"->"Resposta HTTP";"dns"->"Consulta DNS";"dns-error"->"Falha de DNS";"portal"->"Teste de portal";"network"->"Estado informado pelo Android";"reference"->"Referência encontrada";"service"->"Serviço observado";"domain"->"Nome encontrado";"address"->"Endereço encontrado";"error"->"Tentativa sem sucesso";else->kind
}
@Composable internal fun EvidenceField(label:String,value:String) {
    if(value.isNotBlank()) Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        SelectionContainer {Text(Privacy.mask(value),style=MaterialTheme.typography.bodyMedium)}
    }
}
@Composable internal fun EvidenceDetails(r:Record) {
    val d=r.details
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        when(r.kind) {
            "location" -> {
                EvidenceField("Posição do telefone","${d["latitude"]}, ${d["longitude"]}")
                EvidenceField("Horário da posição",d["fixTime"].orEmpty())
                EvidenceField("Precisão aproximada","${d["accuracyMeters"]} m")
                EvidenceField("Provedor",d["provider"].orEmpty())
                EvidenceField("Durante captura",if(d["captureActive"]=="true") "Sim" else "Não")
                if(d["mock"]=="true") EvidenceField("Posição simulada","Informada por provedor de teste")
            }
            "version","security-header","cve","cve-check"->{
                EvidenceField("Observação",r.message)
                EvidenceField("Serviço / resposta",r.target)
                EvidenceField("Evidência",d["evidence"].orEmpty())
                EvidenceField("Origem",d["source"].orEmpty())
                EvidenceField("Produto e versão",listOfNotNull(d["product"],d["version"]).joinToString(" "))
                EvidenceField("Confiança",d["confidence"].orEmpty())
                if(r.kind=="cve") EvidenceField("Gravidade publicada pela NVD","${d["severity"]} · CVSS ${d["cvss"]}")
                EvidenceField("Descrição da fonte",d["description"].orEmpty())
                EvidenceField("Interpretação",d["meaning"].orEmpty())
                EvidenceField("Consultado em",d["queriedAt"].orEmpty())
                if(d["partial"]=="true") EvidenceField("Limite","Somente os primeiros 50 resultados foram consultados. Consulte a fonte para a lista completa.")
                d["reference"]?.let {reference->
                    val handler=androidx.compose.ui.platform.LocalUriHandler.current
                    if(reference.startsWith("https://nvd.nist.gov/vuln/detail/CVE-")||reference=="https://owasp.org/www-project-secure-headers/") TextButton(onClick={handler.openUri(reference)}) {Text("Abrir fonte do achado")}
                }
            }
            "http"->{
                EvidenceField("Requisição","GET ${r.target}")
                EvidenceField("Resposta recebida","Status ${d["status"].orEmpty()} · ${d["bodyBytes"] ?: "?"} bytes · ${d["tls"].orEmpty()}")
                Text("Cabeçalhos enviados pelo servidor",style=MaterialTheme.typography.titleSmall)
                val headers=d.filterKeys {it.startsWith("responseHeader.")}
                if(headers.isEmpty()) EvidenceField("Cabeçalhos preservados",d["headers"].orEmpty()) else headers.forEach {(key,value)->EvidenceField(key.removePrefix("responseHeader."),value)}
                EvidenceField("Medições do app","DNS: ${d["dnsMs"] ?: "?"} ms · conexão: ${d["tcpMs"] ?: "?"} ms · HTTP: ${d["httpMs"] ?: "?"} ms")
                EvidenceField("Endereço da conexão",d["remoteIp"].orEmpty())
                if(d["truncated"]=="true"||d["headersTruncated"]=="true") EvidenceField("Limite da coleta","Parte da resposta foi truncada.")
            }
            "reference","domain","address"->{
                EvidenceField("Encontrado",r.target)
                EvidenceField("Veio de",d["parent"].orEmpty())
                EvidenceField("Onde apareceu",listOfNotNull(d["source"],d["location"]).joinToString(" · "))
                EvidenceField("Como interpretar",if(d["source"]=="DNS PTR") "Nome informado pelo DNS para esse IP. O nome não confirma fabricante nem produto." else "É uma referência observada. A disponibilidade do destino precisa ser testada.")
                if(d["origin"]!=null) EvidenceField("Relação com a página",when(d["origin"]) {"same-origin"->"Mesmo site da resposta";"external"->"Outro site; não significa necessariamente fora da rede local";else->"Relação não determinada"})
            }
            "dns","dns-error"->{
                EvidenceField("Consultado",r.target)
                EvidenceField("Resultado",r.message)
                EvidenceField("Servidores configurados na rede",d["configuredServers"].orEmpty())
                EvidenceField("Como foi consultado",d["source"] ?: "Resolvedor DNS do Android na rede selecionada")
                EvidenceField("Como interpretar","PTR busca um nome para um IP. A e AAAA buscam endereços para um nome. Uma resposta vazia não lista nem descarta outros nomes internos.")
            }
            "portal"->{
                EvidenceField("O que foi testado",if(r.target=="Wi-Fi") "Estado da rede e início do teste HTTP de conectividade" else "GET ${r.target}")
                EvidenceField("Esperado","HTTP 204, sem conteúdo")
                EvidenceField("Observado",r.message+(d["status"]?.let {" (HTTP $it)"} ?: ""))
                EvidenceField("Redirecionamento recebido",d["location"].orEmpty())
                EvidenceField("Conclusão possível","Uma resposta diferente pode indicar portal ou proxy. Não confirma autenticação nem identifica o produto.")
            }
            else-> {EvidenceField("Alvo",r.target);EvidenceField("Resultado",r.message)}
        }
        CopyAction("Copiar evidência") {AnalysisCopy.records("Evidência",listOf(r))}
        d["locationRecordId"]?.let {EvidenceField("Localização associada (ID)",it);EvidenceField("Relação com a posição",d["locationMeaning"].orEmpty())}
        var raw by remember(r.id) {mutableStateOf(false)}
        TextButton(onClick={raw=!raw}) {Text(if(raw) "Ocultar dados técnicos" else "Ver dados técnicos do registro")}
        if(raw) EvidenceField("Metadados coletados pelo app",d.entries.joinToString("\n") {"${it.key}: ${it.value}"})
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RelatedEvidenceDialog(records:List<Record>,onClose:()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Scaffold(topBar={TopAppBar(title={Text("Evidências relacionadas")},navigationIcon={TextButton(onClick=onClose) {Text("Fechar")}})}) {padding->
            LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item {Text("${records.size} registros desta investigação",style=MaterialTheme.typography.labelLarge);CopyAction("Copiar investigação") {AnalysisCopy.records("Investigação relacionada",records)}}
                items(records.distinctBy {it.id},key={it.id}) {r->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {Text(evidenceLabel(r.kind),style=MaterialTheme.typography.titleMedium);EvidenceDetails(r)}}}
            }
        }
    }
}
