package dev.nettool

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nettool.core.HttpProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun DebugSessionDialog(session:Investigation,appEvents:List<Record>,onClose:()->Unit) {
    var filter by rememberSaveable {mutableStateOf("Todos")}
    var query by rememberSaveable {mutableStateOf("")}
    val records=remember(session.records,appEvents,filter,query) {
        (if(filter=="App") appEvents else session.records).sortedByDescending {it.time}.filter {r->
            val category=when(filter) {
                "Erros"->r.kind in setOf("error","dns-error","cancelled") || r.details["status"] in setOf("ERROR","REFUSED","TIMEOUT")
                "Portas"->r.kind in setOf("port","port-batch")||r.kind.startsWith("scan")
                "DNS"->r.kind.startsWith("dns")||r.details["source"].orEmpty().startsWith("DNS")
                "Rede"->r.kind in setOf("network","portal","capture-summary")
                else->true
            }
            category && (query.isBlank() || "${r.target} ${r.message} ${r.details}".contains(query,true))
        }
    }
    ScreenDialog(onClose) {
        Scaffold(topBar={TopAppBar(title={Text("Debug da sessão")},navigationIcon={TextButton(onClick=onClose) {Text("Fechar")}})}) {padding->
            LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {Text("${records.size} grupos/eventos · ${session.dropped} registros antigos descartados",style=MaterialTheme.typography.bodySmall)}
                item {Text("Tentativas de portas são agrupadas por resultado. Abra um grupo para ver cada porta e tempo. Eventos do app exigem Registro debug ativado em Configurações gerais.",style=MaterialTheme.typography.bodySmall)}
                item {OutlinedTextField(value=query,onValueChange={query=it},label={Text("Buscar alvo, porta ou erro")},modifier=Modifier.fillMaxWidth())}
                item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Todos","Erros","Portas","DNS","Rede","App").forEach {value->FilterChip(selected=filter==value,onClick={filter=value},label={Text(value)})}}}
                if(records.isEmpty()) item {Text("Nenhum evento neste filtro.")}
                items(records,key={it.id}) {r->
                    var expanded by rememberSaveable(r.id) {mutableStateOf(false)}
                    OutlinedCard(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("${SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(r.time))} · ${r.kind}",style=MaterialTheme.typography.labelSmall)
                            Text(Privacy.mask(r.target),style=MaterialTheme.typography.titleSmall)
                            Text(Privacy.mask(r.message),style=MaterialTheme.typography.bodyMedium)
                            if(expanded) SelectionContainer {Text(Privacy.mask(r.details.entries.joinToString("\n") {(k,v)->if(k=="attempts") "Porta: tempo em ms\n"+v.replace(",","\n") else "$k: $v"}),style=MaterialTheme.typography.bodySmall)}
                            Text(if(expanded) "Ocultar detalhes" else "Ver detalhes",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable internal fun HttpSettingsDialog(settings:HttpSettings,onClose:()->Unit) {
    var values by remember {mutableStateOf(settings.headers())}
    var error by remember {mutableStateOf("")}
    ScreenDialog(onClose) {
        Scaffold(topBar={TopAppBar(title={Text("Requisições HTTP")},navigationIcon={TextButton(onClick=onClose) {Text("Fechar")}})}) {padding->
            LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                item {Text("Perfil usado nas requisições e no gerador da captura. Mudar cabeçalhos não resolve portas bloqueadas nem faz login no portal.")}
                item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={values=HttpProfile.defaults}) {Text("Padrão do app")}
                    OutlinedButton(onClick={values=HttpProfile.browser}) {Text("Navegador móvel")}
                }}
                items(listOf("User-Agent","Accept","Accept-Language","Cache-Control")) {name->OutlinedTextField(value=values[name].orEmpty(),onValueChange={values=values+(name to it)},label={Text(name)},modifier=Modifier.fillMaxWidth())}
                item {Text("O perfil de navegador altera apenas cabeçalhos. O app não executa JavaScript de páginas nem reproduz uma sessão do navegador.",style=MaterialTheme.typography.bodySmall)}
                if(error.isNotEmpty()) item {Text(error,color=MaterialTheme.colorScheme.error)}
                item {Button(onClick={try {settings.save(values);onClose()} catch(ex:IllegalArgumentException) {error=ex.message.orEmpty()}},modifier=Modifier.fillMaxWidth()) {Text("Salvar perfil")}}
            }
        }
    }
}
