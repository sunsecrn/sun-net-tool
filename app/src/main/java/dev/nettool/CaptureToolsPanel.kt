package dev.nettool

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nettool.capture.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable fun CaptureToolsPanel(model:InvestigationModel,running:Boolean,pcapPath:String?) {
    val scope=rememberCoroutineScope()
    var kind by rememberSaveable {mutableStateOf(ProbeKind.HTTP)}
    var target by rememberSaveable {mutableStateOf("http://example.com/")}
    var port by rememberSaveable {mutableStateOf("80")}
    var working by remember {mutableStateOf(false)}
    var workLabel by remember {mutableStateOf("")}
    var workSeconds by remember {mutableStateOf(0L)}
    var workJob by remember {mutableStateOf<kotlinx.coroutines.Job?>(null)}
    LaunchedEffect(working) {if(working) {val start=android.os.SystemClock.elapsedRealtime();while(true) {workSeconds=(android.os.SystemClock.elapsedRealtime()-start)/1000;kotlinx.coroutines.delay(1000)}}}
    if(working) Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text("$workLabel · ${workSeconds}s",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={workJob?.cancel()}) {Text("Cancelar teste")}
    }
    var probeSummary by remember {mutableStateOf("")}
    var result by remember {mutableStateOf("")}
    var query by rememberSaveable {mutableStateOf("")}
    var regex by rememberSaveable {mutableStateOf(false)}
    var report by remember {mutableStateOf<SearchReport?>(null)}
    Text("Gerar tráfego de teste",style=MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {ProbeKind.entries.forEach {value -> FilterChip(selected=kind==value,onClick={kind=value;target=when(value) {ProbeKind.HTTP->"http://example.com/";ProbeKind.HTTPS->"https://example.com/";else->"example.com"};port=if(value==ProbeKind.HTTPS) "443" else if(value==ProbeKind.DNS) "53" else "80"},label={Text(value.name)})}}
    Text("Escolha um teste pronto ou edite o alvo. Os testes públicos acessam example.com; nada é enviado sem tocar em Gerar tráfego.",style=MaterialTheme.typography.bodySmall)
    val wifi by model.monitor.wifi.collectAsState()
    val session by model.session.collectAsState()
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        wifi?.gateways?.firstOrNull {it.contains('.')}?.let {gateway->TextButton(onClick={kind=ProbeKind.HTTP;target="http://$gateway/";port="80"}) {Text("HTTP do gateway")}}
        Inventory.items(session.records).filter {it.category=="Serviços"&&it.service in setOf("HTTP","HTTPS")}.take(3).forEach {service->TextButton(onClick={kind=if(service.service=="HTTPS") ProbeKind.HTTPS else ProbeKind.HTTP;target="${kind.name.lowercase()}://${if(service.target.contains(':')) "[${service.target}]" else service.target}:${service.port}/"}) {Text("${service.target}:${service.port}")}}
    }
    Text("Com a captura ativa, os diagnósticos de Rede e HTTP também passam pela VPN. HTTPS continua criptografado no PCAP.",style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(value=target,onValueChange={target=it},label={Text(if(kind in setOf(ProbeKind.HTTP,ProbeKind.HTTPS)) "URL completa" else "IP ou domínio")},singleLine=true,modifier=Modifier.fillMaxWidth())
    if(kind==ProbeKind.TCP) OutlinedTextField(value=port,onValueChange={port=it},label={Text("Porta TCP")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Button(onClick={working=true;workLabel="Aguardando ${kind.name}: ${Privacy.mask(target)}";workJob=scope.launch {
        try {
            val response=CaptureProbe.run(ProbeRequest(kind,target,port.toIntOrNull() ?: 0,headers=model.httpSettings.headers()))
            probeSummary=response.summary
            val evidence=Record(kind="probe",target=response.target,message=response.summary,details=response.details+("success" to "${response.success}"))
            model.add(evidence)
            if(response.kind==ProbeKind.DNS) {
                model.add(Record(kind="dns",target=target,message=response.summary,details=response.details+("source" to "DNS via captura"),parentId=evidence.id))
                response.details["IPs"].orEmpty().split(',').map {it.trim()}.filter {Inventory.isIp(it)}.forEach {ip->model.add(Record(kind="address",target=ip,message="Resposta DNS de $target",details=mapOf("source" to "DNS via captura","parent" to target),parentId=evidence.id))}
            }
            if(response.success&&response.kind==ProbeKind.TCP) response.details["IP"]?.let {ip->model.add(Record(kind="port",target=ip,message="${response.details["Porta"]}: OPEN",details=mapOf("port" to response.details["Porta"].orEmpty(),"status" to "OPEN","source" to "TCP via captura"),parentId=evidence.id))}
            if(response.success && response.kind in setOf(ProbeKind.HTTP,ProbeKind.HTTPS)) {
                val endpoint=runCatching {java.net.URI(target)}.getOrNull()
                val address=response.details["IP"]
                if(address!=null&&endpoint!=null) model.add(Record(kind="service",target=address,message="${response.kind}: resposta HTTP observada pelo cliente",details=mapOf("type" to response.kind.name,"protocolObserved" to "true","address" to address,"port" to "${if(endpoint.port>0) endpoint.port else if(response.kind==ProbeKind.HTTPS) 443 else 80}","source" to "Cliente do app via VPN; não é descriptografia PCAP"),parentId=evidence.id))
            }
        } catch(ex:CancellationException) {throw ex} catch(ex:Exception) {probeSummary="Falha: ${ex.message}";model.add(Record(kind="error",target="Gerador de tráfego",message=probeSummary))} finally {working=false}
    }},enabled=running&&!working&&target.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Text(if(working) "Teste em andamento" else "Gerar tráfego ${kind.name}")}
    if(probeSummary.isNotEmpty()) Text(probeSummary,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
    Text("HTTP mostra texto claro no PCAP. HTTPS mantém o conteúdo criptografado; a resposta do app aparece em Alvos > Respostas.",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("Buscar no PCAP",style=MaterialTheme.typography.titleMedium)
    Text("Encerre a captura para buscar texto nos fluxos reconstruídos. Lacunas e limites ficam indicados no resultado.",style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(value=query,onValueChange={query=it.take(256)},label={Text("Texto ou expressão regular")},singleLine=true,modifier=Modifier.fillMaxWidth())
    FilterChip(selected=regex,onClick={regex=!regex},label={Text("Expressão regular (RE2)")})
    OutlinedButton(onClick={working=true;workLabel="Reconstruindo fluxos e buscando no PCAP";report=null;workJob=scope.launch {
        try {
            val found=PcapSearch.search(File(requireNotNull(pcapPath)),SearchRequest(query,regex))
            report=found;result=found.summary
            val parent=Record(kind="capture-search",target=File(pcapPath).name,message=found.summary,details=mapOf("source" to "PCAP local encerrado","mode" to if(regex) "regex RE2" else "texto"))
            model.add(parent)
            found.matches.forEach {match -> model.add(Record(kind="pcap-match",target=match.flow,message=match.snippet,details=mapOf("source" to "PCAP texto claro","protocol" to match.protocol,"offset" to "${match.offset}"),parentId=parent.id))}
        } catch(ex:CancellationException) {throw ex} catch(ex:Exception) {result="Busca não concluída: ${ex.message}"} finally {working=false}
    }},enabled=!running&&!working&&pcapPath!=null&&query.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Text("Buscar texto no PCAP")}
    if(result.isNotEmpty()) Text(result,style=MaterialTheme.typography.bodySmall)
    report?.warnings?.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
    report?.matches?.take(20)?.forEach {match -> Text("${match.flow}\n${match.snippet}",style=MaterialTheme.typography.bodySmall)}
}
