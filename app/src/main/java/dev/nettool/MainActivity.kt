package dev.nettool

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.nettool.capture.CaptureService
import dev.nettool.capture.CaptureState
import androidx.compose.ui.platform.LocalContext
import dev.nettool.core.ServiceDiscovery
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity: ComponentActivity() {
    private val model:InvestigationModel by viewModels()
    private var exportBytes=byteArrayOf()
    private var exportFormat="json"
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if(uri==null) model.trace("export_session_cancelled",mapOf("format" to exportFormat))
        uri?.let { runCatching { requireNotNull(contentResolver.openOutputStream(it)) {"Destino indisponível"}.use { out -> out.write(exportBytes) } }.onSuccess {model.status.value="Arquivo exportado";model.trace("export_session_success",mapOf("format" to exportFormat))}.onFailure {model.trace("export_session_failed",mapOf("format" to exportFormat,"errorType" to it.javaClass.simpleName)); model.status.value="Erro ao exportar: ${it.message}" } }
    }
    private val vpn=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.trace("capture_consent",mapOf("accepted" to "${it.resultCode==RESULT_OK}")); if(it.resultCode==RESULT_OK) startCapture() else model.status.value="Captura não autorizada" }
    private val notification=registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted -> model.trace("notification_permission",mapOf("accepted" to "$granted")); prepareCapture() }
    private fun prepareCapture() { val intent=VpnService.prepare(this); if(intent!=null) vpn.launch(intent) else startCapture() }
    private fun startCapture() { model.trace("capture_start_requested"); startForegroundService(Intent(this,CaptureService::class.java).putExtra("sessionId",model.session.value.id).putExtra("networkHandle",model.monitor.wifi.value?.network?.networkHandle ?: 0L)); model.add(Record(kind="capture",target="VPN local",message="Captura solicitada para esta sessão")) }
    override fun onStart() { super.onStart(); model.location.setVisible(true); model.trace("activity_started") }
    override fun onStop() { model.location.setVisible(false); model.trace("activity_stopped"); super.onStop() }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle=SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { MaterialTheme(colorScheme=NetToolColors, shapes=Shapes(small=RoundedCornerShape(10.dp),medium=RoundedCornerShape(14.dp),large=RoundedCornerShape(18.dp))) {
            NetToolScreen(model,onCapture={ model.trace("capture_button_start"); if(android.os.Build.VERSION.SDK_INT>=33) notification.launch(android.Manifest.permission.POST_NOTIFICATIONS) else prepareCapture() },onStopCapture={ model.trace("capture_stop_requested"); startService(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) },onExport={ csv -> exportFormat=if(csv) "csv" else "json"; model.trace("export_session_requested",mapOf("format" to exportFormat)); exportBytes=(if(csv) model.session.value.csv() else model.session.value.json().toString(2)).toByteArray(); export.launch("nettool-${model.session.value.id.take(8)}.${if(csv) "csv" else "json"}") })
        } }
    }
}
private val NetToolColors = darkColorScheme(
    primary=Color(0xFF72E6C2), onPrimary=Color(0xFF00382C), primaryContainer=Color(0xFF163D35), onPrimaryContainer=Color(0xFFB8F5E1),
    secondary=Color(0xFF93C7EF), onSecondary=Color(0xFF102C40), secondaryContainer=Color(0xFF213749), onSecondaryContainer=Color(0xFFD0E8FA),
    background=Color(0xFF0B1015), onBackground=Color(0xFFE4EBF0), surface=Color(0xFF131B23), onSurface=Color(0xFFE4EBF0),
    surfaceVariant=Color(0xFF1D2933), onSurfaceVariant=Color(0xFF9DAFBB), outline=Color(0xFF4A5C68), outlineVariant=Color(0xFF293740),
    error=Color(0xFFFFB4AB), surfaceContainer=Color(0xFF131B23), surfaceContainerLow=Color(0xFF10171E), surfaceContainerHigh=Color(0xFF1D2933)
)
private fun time(value:Long)=SimpleDateFormat("dd/MM HH:mm:ss",Locale.getDefault()).format(Date(value))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NetToolScreen(model:InvestigationModel,onCapture:()->Unit,onStopCapture:()->Unit,onExport:(Boolean)->Unit) {
    val capture by CaptureState.state.collectAsStateWithLifecycle()
    val wifi by model.monitor.wifi.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val operationStarted by model.operationStarted.collectAsStateWithLifecycle()
    var elapsedSeconds by remember {mutableStateOf(0L)}
    LaunchedEffect(busy,operationStarted) {
        while(busy) {elapsedSeconds=(android.os.SystemClock.elapsedRealtime()-operationStarted)/1000;kotlinx.coroutines.delay(1000)}
    }
    val status by model.status.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val debugEnabled by model.debugEnabled.collectAsStateWithLifecycle()
    val debugCount by model.debugCount.collectAsStateWithLifecycle()
    val debugDropped by model.debugDropped.collectAsStateWithLifecycle()
    val debugError by model.debugError.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val listState=rememberLazyListState()
    var debugPayload by remember {mutableStateOf<String?>(null)}
    var debugPreparing by remember {mutableStateOf(false)}
    var debugExportStatus by remember {mutableStateOf("")}
    val saveDebug=androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri ->
        if(uri==null) {model.trace("export_debug_cancelled"); debugPayload=null; debugPreparing=false}
        else {
            val payload=debugPayload
            scope.launch {
                try {
                    checkNotNull(payload) {"Gere novamente o arquivo debug"}
                    withContext(Dispatchers.IO) {requireNotNull(context.contentResolver.openOutputStream(uri)) {"Destino indisponível"}.use {it.write(payload.toByteArray(Charsets.UTF_8))}}
                    debugExportStatus="Registro debug exportado"
                    model.trace("export_debug_success")
                } catch(ex:CancellationException) {throw ex}
                catch(ex:Exception) {debugExportStatus="Não foi possível exportar o registro debug";model.trace("export_debug_failed",mapOf("errorType" to ex.javaClass.simpleName))}
                finally {debugPayload=null;debugPreparing=false}
            }
        }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tutorTargets=remember {mutableStateMapOf<Int,androidx.compose.ui.layout.LayoutCoordinates>()}
    var tutorPositioned by remember {mutableStateOf(false)}
    val tutorPreferences=remember {TutorPreferences(context)}
    var tutorVisible by rememberSaveable {mutableStateOf(tutorPreferences.shouldShow)}
    var tutorStep by rememberSaveable {mutableIntStateOf(tutorPreferences.step)}
    var tutorReturnTab by rememberSaveable {mutableIntStateOf(0)}
    var tutorReturnItem by rememberSaveable {mutableIntStateOf(0)}
    var tutorReturnOffset by rememberSaveable {mutableIntStateOf(0)}
    fun useTutorTarget() {if(tutorVisible) {tutorPreferences.finish();tutorVisible=false}}
    fun closeTutor(completed:Boolean) {
        tutorPreferences.finish()
        tutorVisible=false
        model.trace("tutor_closed",mapOf("outcome" to if(completed) "completed" else "skipped"))
        tab=if(completed) 0 else tutorReturnTab
        scope.launch {listState.scrollToItem(if(completed) 0 else tutorReturnItem,if(completed) 0 else tutorReturnOffset)}
    }
    LaunchedEffect(tutorVisible,tutorStep) {
        if(tutorVisible) {
            tutorPositioned=false
            val step=tutorSteps[tutorStep]
            tab=step.tab
            withFrameNanos { }
            listState.scrollToItem(step.item)
            withFrameNanos { }
            tutorTargets[tutorStep]?.takeIf {it.isAttached && tutorStep!=9}?.let { coords ->
                val y=coords.localToRoot(androidx.compose.ui.geometry.Offset.Zero).y
                val desired=with(androidx.compose.ui.unit.Density(context.resources.displayMetrics.density)) {160.dp.toPx()}
                listState.scroll {scrollBy(y-desired)}
            }
            withFrameNanos { }
            tutorPositioned=true
            tutorPreferences.progress(tutorStep)
            model.trace("tutor_step",mapOf("screen" to step.title,"processed" to "${tutorStep+1}","total" to "${tutorSteps.size}"))
        }
    }
    var cidr by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var ports by rememberSaveable { mutableStateOf("22,80,443,8080") }
    var url by rememberSaveable { mutableStateOf("https://example.com") }
    var revealHttpResults by remember {mutableStateOf(false)}

    var includeScripts by rememberSaveable { mutableStateOf(false) }
    var inventoryFilter by rememberSaveable { mutableStateOf("Todos") }
    var securityFilter by rememberSaveable {mutableStateOf("Todos")}
    var showDebug by rememberSaveable {mutableStateOf(false)}
    var showHttpSettings by rememberSaveable {mutableStateOf(false)}
    var appDebugEvents by remember {mutableStateOf(emptyList<Record>())}
    LaunchedEffect(showDebug,debugCount) {if(showDebug) {
        val root=try {org.json.JSONObject(model.debugExport())} catch(e:CancellationException) {throw e} catch(e:Exception) {org.json.JSONObject()}
        val events=root.optJSONArray("events")
        appDebugEvents=(0 until (events?.length() ?: 0)).mapNotNull {i->val e=events!!.getJSONObject(i);val d=e.optJSONObject("details") ?: org.json.JSONObject();if(d.optString("sessionId")!=session.id) null else Record(id="debug-$i",time=e.optLong("time"),kind="app",target=e.optString("action"),message=d.optString("outcome"),details=d.keys().asSequence().associateWith {d.optString(it)})}
    }}
    if(showDebug) DebugSessionDialog(session,appDebugEvents) {showDebug=false}
    if(showHttpSettings) HttpSettingsDialog(model.httpSettings) {showHttpSettings=false}
    LaunchedEffect(busy) {if(!busy&&revealHttpResults) {revealHttpResults=false;if(tab==2) {tab=1;inventoryFilter="Respostas";listState.scrollToItem(2)}}}

    var identifyAfterScan by rememberSaveable {mutableStateOf(true)}
    var portProfile by rememberSaveable { mutableStateOf("1.000 comuns") }
    var targetQuery by rememberSaveable {mutableStateOf("")}
    var serviceFilter by rememberSaveable {mutableStateOf("Todos")}
    var topPorts by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(Unit) {topPorts=withContext(Dispatchers.IO) {runCatching {dev.nettool.core.PortProfiles.topTcp(java.io.File(context.filesDir,"nmap-services").readText())}.getOrDefault(emptyList())}}
    val importPorts=androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    val bytes=requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytesLimited(4*1024*1024) }
                    val text=bytes.toString(Charsets.UTF_8)
                    val parsed=dev.nettool.core.PortProfiles.topTcp(text)
                    java.io.File(context.filesDir,"nmap-services").writeText(text)
                    parsed
                }
                topPorts=result; model.status.value="Base importada: 1.000 portas TCP"
            } catch(ex:Exception) { model.status.value=ex.message ?: "Falha na importação" }
        }
    }
    val inventory=remember(session.records) { Inventory.items(session.records) }
    val findings=remember(session.records) { Inventory.findings(session.records) }
    var dnsType by rememberSaveable { mutableStateOf("A") }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var networkExpanded by rememberSaveable { mutableStateOf(false) }
    var historyExpanded by rememberSaveable { mutableStateOf(false) }
    val ready=wifi!=null&&!busy
    var showGeneralSettings by remember {mutableStateOf(false)}
    if(showGeneralSettings) GeneralSettingsDialog(model,onClose={showGeneralSettings=false},onHttp={showGeneralSettings=false;showHttpSettings=true},onDebug={showGeneralSettings=false;showDebug=true}) {
Panel("Registro debug", "OPCIONAL") {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(if(debugEnabled) "Registro ativado" else "Registro desativado",style=MaterialTheme.typography.labelLarge); Hint("Ajuda a reproduzir problemas no aplicativo.")}
                            Switch(checked=debugEnabled,onCheckedChange={model.setDebugEnabled(it)},modifier=Modifier.semantics {contentDescription="Registro debug opcional"})
                        }
                        Hint("Registra navegação, ações, operações, resultados e mudanças de rede. Não registra digitação, corpos nem cabeçalhos de requisições.")
                        Hint("${debugCount} eventos · ${debugDropped} omitidos por limite")
                        Hint("Até 2.000 eventos, 1 MiB e 7 dias. Desativar interrompe novos registros; limpar apaga os existentes.")
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={
                                debugPreparing=true;model.trace("export_debug_requested")
                                scope.launch {
                                    try {debugPayload=model.debugExport();saveDebug.launch("sun-nettool-debug.json")}
                                    catch(ex:CancellationException) {debugPreparing=false;throw ex}
                                    catch(ex:Exception) {debugPreparing=false;debugExportStatus="Não foi possível preparar o registro debug";model.trace("export_debug_failed",mapOf("errorType" to ex.javaClass.simpleName))}
                                }
                            },enabled=!debugPreparing) {Text(if(debugPreparing) "Preparando..." else "Exportar debug JSON")}
                            TextButton(onClick={model.clearDebug();debugExportStatus="Limpeza dos registros solicitada"},enabled=!debugPreparing&&(debugCount>0||debugDropped>0)) {Text("Limpar registros")}
                        }
                        if(debugExportStatus.isNotBlank()) Hint(debugExportStatus)
                        debugError?.let {Text("Não foi possível atualizar o registro debug. Tente desativar e ativar novamente.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                    }
    }
    val destinations=listOf("Rede","Alvos","HTTP","Captura","Sessão")
    LaunchedEffect(tab) {model.trace("screen_view",mapOf("screen" to destinations[tab]))}
    LaunchedEffect(wifi?.network) { cidr=wifi?.addresses?.firstOrNull { it.contains('.') }?.let {runCatching {dev.nettool.core.NetworkMath.batches(it).first()}.getOrDefault(it)}.orEmpty() }
    if(showAbout) AlertDialog(onDismissRequest={model.trace("about_dismissed");showAbout=false},icon={Image(painterResource(R.drawable.ic_sunsec),contentDescription="Logo oficial Sunsec",modifier=Modifier.height(64.dp).aspectRatio(152.492f/157.782f))},title={Text("Sobre o Sun NetTool")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {Text("Diagnóstico e investigação de redes Wi-Fi, com evidências salvas localmente."); TextButton(onClick={model.trace("community_link_open");context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://sunsec.net")))},contentPadding=PaddingValues(0.dp)) {Text("Comunidade Sunsec · sunsec.net")}; Text("Agradecimento à comunidade Sunsec pela colaboração nas pesquisas e no desenvolvimento de aplicativos."); Hint("Versão ${BuildConfig.VERSION_NAME} · MVP Android")}},confirmButton={TextButton(onClick={model.trace("about_closed");showAbout=false}) {Text("Fechar")}})
    CompositionLocalProvider(LocalTutorTargets provides { index,coords -> tutorTargets[index]=coords },LocalTutorOnUse provides {useTutorTarget()}) {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor=MaterialTheme.colorScheme.background,
        topBar={ TopAppBar(title={ Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(10.dp)) { Box(Modifier.size(36.dp),contentAlignment=Alignment.Center) { Image(painterResource(R.drawable.ic_sunsec),contentDescription="Sunsec",modifier=Modifier.height(30.dp).aspectRatio(152.492f/157.782f)) } }
            Column { Text("Sun NetTool",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold); Text("DIAGNÓSTICO DE REDE",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        } }, actions={ Box {IconButton(onClick={useTutorTarget();model.trace("menu_open");menuOpen=true},modifier=Modifier.tutorTarget(9).semantics {contentDescription="Abrir menu"}) {MenuGlyph("menu")}; DropdownMenu(expanded=menuOpen,onDismissRequest={model.trace("menu_dismissed");menuOpen=false}) {
                DropdownMenuItem(text={Text("Configurações gerais")},leadingIcon={MenuGlyph("settings")},onClick={menuOpen=false;showGeneralSettings=true})
DropdownMenuItem(text={Text("Nova sessão")},leadingIcon={MenuGlyph("new")},onClick={menuOpen=false;model.newSession()},enabled=!busy&&!capture.running); DropdownMenuItem(text={Text("Modo tutor")},leadingIcon={MenuGlyph("info")},onClick={
                menuOpen=false;tutorReturnTab=tab;tutorReturnItem=listState.firstVisibleItemIndex;tutorReturnOffset=listState.firstVisibleItemScrollOffset
                tutorStep=0;tutorVisible=true;model.trace("tutor_opened")
            },enabled=!busy&&!capture.running); DropdownMenuItem(text={Text("Sobre e créditos")},leadingIcon={MenuGlyph("info")},onClick={model.trace("about_open");menuOpen=false;showAbout=true})}} },colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.background)) },
        bottomBar={ Column {
            if(busy) Surface(tonalElevation=3.dp) {Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(status,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text("${elapsedSeconds}s decorridos",style=MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick={model.cancel()}) {Text("Cancelar")}
                }
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }}
            NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
            destinations.forEachIndexed { index,label -> NavigationBarItem(selected=tab==index,onClick={tab=index;scope.launch {listState.scrollToItem(0)}},icon={ToolIcon(index,if(tab==index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)},label={Text(label,maxLines=1,overflow=TextOverflow.Ellipsis)},colors=NavigationBarItemDefaults.colors(indicatorColor=MaterialTheme.colorScheme.primaryContainer,selectedTextColor=MaterialTheme.colorScheme.primary)) }
        } } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),state=listState,contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { OutlinedCard(onClick={useTutorTarget();networkExpanded=!networkExpanded;model.trace("network_details",mapOf("expanded" to "$networkExpanded"))},modifier=Modifier.fillMaxWidth().tutorTarget(0),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Canvas(Modifier.size(8.dp)) {drawCircle(if(wifi!=null) Color(0xFF72E6C2) else Color(0xFFFFC56D))}
                        Column(Modifier.weight(1f)) { Text(if(wifi!=null) "Wi-Fi conectado" else "Aguardando Wi-Fi",style=MaterialTheme.typography.labelLarge); Text(wifi?.addresses?.firstOrNull {it.contains('.')} ?: "Conecte-se para iniciar",style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(if(networkExpanded) "Ocultar" else "Detalhes",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    }
                    if(networkExpanded) { HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant); Text(wifi?.let {"Interface: ${it.interfaceName}\nIP: ${it.addresses.joinToString()}\nGateway: ${it.gateways.joinToString()}\nDNS: ${it.dnsServers.joinToString()}"} ?: "Os diagnósticos usam a conexão Wi-Fi selecionada.",style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace); Text(wifi?.let {if(it.captivePortal) "Portal indicado pelo Android" else if(it.validated) "Internet validada pelo Android" else "Internet não validada. Rede local disponível."} ?: "Nenhuma rede selecionada",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } }
            item { Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("SESSÃO ${session.id.take(8).uppercase()}",style=MaterialTheme.typography.labelSmall,fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.onSurfaceVariant); Text(status,style=MaterialTheme.typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis) }
                if(busy) TextButton(onClick={model.cancel()}) {Text("Cancelar")} else Text("${session.records.size} registros",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }; if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp)) }
            when(tab) {
                0 -> {
                    item { SectionTitle("Explorar a rede","Meu aparelho: ${wifi?.addresses?.firstOrNull {it.contains('.')} ?: "sem IPv4"}")
                        Text("Gateway: ${wifi?.gateways?.joinToString() ?: "não informado"}\nDNS: ${wifi?.dnsServers?.joinToString() ?: "não informado"}",style=MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick={host=wifi?.gateways?.firstOrNull {it.contains('.')} ?: wifi?.gateways?.firstOrNull().orEmpty();scope.launch {listState.scrollToItem(4)}},enabled=ready) {Text("Usar gateway")}
                            TextButton(onClick={showDebug=true}) {ActionGlyph();Text("Ver tentativas")}
                        }
                        Button(onClick={val targets=inventory.filter {it.category=="IPs"&&it.present}.map {it.target};model.run("DNS da rede") {inspectDns(targets)}},enabled=ready,modifier=Modifier.fillMaxWidth()) {Text("Investigar DNS da rede")}
                        Text("Consulta nomes do gateway, DNS e até 32 IPs observados. DNS não fornece uma lista completa de equipamentos.",style=MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={model.run("Portal e conectividade") {checkPortal()}},enabled=ready) {Text("Verificar portal")}
                            TextButton(onClick={context.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))},enabled=ready) {Text("Abrir Wi-Fi / login")}
                        }
                        Text("Verificar portal consulta um endereço público de conectividade. Redirects são indícios, não confirmação de portal.",style=MaterialTheme.typography.bodySmall) }
                    item { Panel("Descoberta", "01") {
                        Field(cidr,{cidr=it},"Faixa IPv4 CIDR", "192.168.1.0/24")
                        val networkRange=wifi?.addresses?.firstOrNull {it.contains('.')}.orEmpty()
                        val batches=runCatching {dev.nettool.core.NetworkMath.batches(networkRange)}.getOrDefault(emptyList())
                        if(batches.size>1) {Hint("Rede maior que 256 alvos. Selecione um lote; até 16 lotes sugeridos.");FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {batches.forEach {block->FilterChip(selected=cidr==block,onClick={cidr=block},label={Text(block)})}}}
                        val routes=wifi?.routes.orEmpty().filter {it.contains('.')&&!it.startsWith("0.0.0.0/")}.distinct()
                        if(routes.isNotEmpty()) Hint("Rotas informadas pela rede: ${routes.joinToString()}")
                        Button(onClick={useTutorTarget();model.run("Descoberta $cidr") {discover(cidr)}},enabled=ready,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).tutorTarget(1)) {Text("Descobrir hosts")}
                        OutlinedButton(onClick={model.run("Descoberta ampliada $cidr") {discover(cidr,topPorts)}},enabled=ready&&topPorts.size==1000,modifier=Modifier.fillMaxWidth()) {Text("Descoberta ampliada: 1.000 portas")}
                        Hint("A rápida tenta 3 portas. A ampliada tenta até 1.000 por IP e para ao detectar presença. Depois faça o scan completo dos alvos. Pode demorar vários minutos.")
                        TextButton(onClick={val connection=wifi; if(connection!=null) model.run("DNS-SD") {ServiceDiscovery(context).discover(connection) {ev -> model.status.value="${ev.message}: ${ev.target}";if(ev.kind!="stage-progress") model.add(Record(time=ev.timeMillis,kind=ev.kind,target=ev.target,message=ev.message,details=ev.details))}}},enabled=ready,modifier=Modifier.fillMaxWidth()) {Text("mDNS: anúncios locais")}
                        Hint("Até 256 alvos e 16 conexões simultâneas. Sem resposta significa resultado inconclusivo.")
                    } }
                    item { Panel("Investigar um alvo", "02") {
                        Field(host,{host=it},"IP ou domínio", "192.168.1.1")
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("A","AAAA","PTR").forEach {t -> FilterChip(selected=dnsType==t,onClick={dnsType=t;model.trace("dns_type_selected",mapOf("type" to t))},label={Text(t)})}; Spacer(Modifier.weight(1f)); TextButton(onClick={model.run("DNS $host") {dns(host,dnsType)}},enabled=host.isNotBlank()&&ready) {Text("DNS")}}
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf("1.000 comuns","Todas TCP","Personalizadas").forEach {profile->FilterChip(selected=portProfile==profile,onClick={portProfile=profile},label={Text(profile)})}
                        }
                        if(portProfile=="1.000 comuns") {
                            Hint(if(topPorts.isEmpty()) "Importe nmap-services para usar as 1.000 portas TCP mais frequentes." else "Perfil pronto: 1.000 portas TCP, ordenadas pela frequência da base importada.")
                            TextButton(onClick={importPorts.launch(arrayOf("*/*"))}) {Text(if(topPorts.isEmpty()) "Importar nmap-services" else "Trocar base de portas")}
                        } else if(portProfile=="Personalizadas") Field(ports,{ports=it},"Portas ou intervalos TCP", "22,80,443,8000-8100")
                        if(portProfile=="Todas TCP") Hint("Todas as 65.535 portas TCP de um alvo. Pode demorar bastante; cancelar preserva as tentativas concluídas. Não inclui UDP.")
                        val selectedPorts=remember(portProfile,topPorts,ports) {when(portProfile) {"1.000 comuns"->topPorts;"Todas TCP"->dev.nettool.core.PortProfiles.allTcp();else->runCatching {dev.nettool.core.PortProfiles.custom(ports)}.getOrDefault(emptyList())}}
                        Hint("1 alvo × ${selectedPorts.size} portas. Até 16 conexões simultâneas. Domínios: primeiro IP resolvido; os demais ficam no inventário.")
                        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(checked=identifyAfterScan,onCheckedChange={identifyAfterScan=it});Text("Identificar até 16 serviços após o scan",style=MaterialTheme.typography.bodySmall)}
                        Button(onClick={useTutorTarget();model.run("Portas $host",mapOf("profile" to portProfile)) {val found=scanPorts(host,selectedPorts);if(identifyAfterScan) found.filter {it.status==dev.nettool.core.PortStatus.OPEN}.take(16).forEach {identifyService(it.host,it.port)}}},enabled=host.isNotBlank()&&ready&&selectedPorts.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).tutorTarget(2)) {Text("Testar portas")}
                        val completedScan=session.records.lastOrNull {it.kind=="scan-complete"&&(it.target==host||it.details["parent"]==host)}
                        completedScan?.let {Text(it.message,style=MaterialTheme.typography.bodyMedium);TextButton(onClick={showDebug=true}) {ActionGlyph();Text("Detalhes do scan")}}
                        val openPorts=inventory.filter {it.category=="Serviços"&&it.target==host}.mapNotNull {it.port}.distinct()
                        if(openPorts.isNotEmpty()) OutlinedButton(onClick={model.run("Identificar serviços $host") {openPorts.take(16).forEach {identifyService(host,it)}}},enabled=ready) {Text("Identificar ${minOf(16,openPorts.size)} serviços")}
                        val remaining=Inventory.remaining(session.records,host,selectedPorts)
                        val previousScan=Inventory.latestScan(session.records,host)
                        if(Inventory.isIp(host)&&remaining.isNotEmpty()&&remaining.size<selectedPorts.size&&previousScan?.details?.get("network")==wifi.toString()) TextButton(onClick={model.run("Portas $host",mapOf("resumeOf" to previousScan.id)) {scanPorts(host,remaining)}},enabled=ready) {Text("Retomar ${remaining.size} portas pendentes")}
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedButton(onClick={model.run("SSH $host") {ssh(host)}},enabled=host.isNotBlank()&&ready) {Text("SSH :22")}; OutlinedButton(onClick={model.run("TLS $host") {tls(host)}},enabled=host.isNotBlank()&&ready) {Text("TLS :443")}}
                    } }
                }
                1 -> {
                    item {SectionTitle("Alvos e achados","Endereços, serviços e evidências em um só lugar.")}
                    item {FlowRow(modifier=Modifier.tutorTarget(3),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Todos","IPs","Domínios","Serviços","Respostas","Referências","Indícios","DNS","Portal","Segurança").forEach { filter -> FilterChip(modifier=if(filter=="Segurança") Modifier.tutorTarget(7) else Modifier,selected=inventoryFilter==filter,onClick={useTutorTarget();inventoryFilter=filter},label={Text(filter)}) }}}
                    if(inventoryFilter in setOf("Todos","IPs","Domínios","Serviços")) item {Field(targetQuery,{targetQuery=it},"Buscar IP, nome, porta ou protocolo")}
                    if(inventoryFilter=="Serviços") item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Todos","Reconhecidos","Tipo desconhecido").forEach {f->FilterChip(selected=serviceFilter==f,onClick={serviceFilter=f},label={Text(f)})}}}
                    if(inventoryFilter=="Segurança") item {FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf("Todos","Versões","Headers","CVEs","Consultas").forEach {f->FilterChip(selected=securityFilter==f,onClick={securityFilter=f},label={Text(f)})}};Hint("Versões anunciadas e headers são analisados nas novas respostas HTTP e banners SSH. CVEs exigem consulta explícita à NVD; são possíveis correspondências, não falhas confirmadas.")}
                    val selected=inventory.filter {entry->
                        (inventoryFilter=="Todos" || entry.category==inventoryFilter) &&
                        (targetQuery.isBlank() || listOf(entry.target,entry.port?.toString().orEmpty(),entry.service.orEmpty(),entry.records.filter {it.kind=="domain"}.joinToString {it.target}).any {it.contains(targetQuery,true)}) &&
                        (inventoryFilter!="Serviços" || serviceFilter=="Todos" || (entry.service!="TCP aberto, tipo não identificado")== (serviceFilter=="Reconhecidos"))
                    }
                    if(selected.isEmpty()&&inventoryFilter in setOf("Todos","IPs","Domínios","Serviços")) item {Hint("Nenhum item nesta categoria. Descubra hosts ou consulte uma resposta HTTP.")}
                    items(selected,key={it.key}) { entry -> InventoryCard(entry,ready, onTarget={host=entry.target;tab=0;scope.launch {listState.scrollToItem(4)}},onHttp={url=endpointUrl(entry.target,entry.port ?: 80,entry.service=="HTTPS"||entry.service=="TLS");tab=2;scope.launch {listState.scrollToItem(0)}},onSsh={model.run("SSH ${entry.target}") {ssh(entry.target,entry.port ?: 22)}}) }
                }
                2 -> {
                    item {SectionTitle("Requisições HTTP","Configure e execute. Consulte os resultados em Alvos.")}
                    item {Panel("Requisição HTTP", "GET") {
                        Field(url,{url=it},"URL completa", "https://example.com")
                        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(checked=includeScripts,onCheckedChange={includeScripts=it}); Text("Analisar até 5 scripts da mesma origem",style=MaterialTheme.typography.bodySmall)}
                        Button(onClick={useTutorTarget();revealHttpResults=true;model.run("HTTP") {http(url,includeScripts)}},enabled=ready&&url.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).tutorTarget(4)) {Text("Executar requisição")}
                        Hint("Validação TLS ativa, até 8 redirects e 64 KiB de conteúdo. Cookies não são reutilizados.")
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        Text("O que observar",style=MaterialTheme.typography.labelLarge)
                        Hint("Tempos de DNS, TCP e TLS; status e cabeçalhos; referências e indícios no HTML. Conteúdo público não comprova vulnerabilidade.")
                        Hint("Autenticação interativa ainda não disponível.")
                        TextButton(onClick={showHttpSettings=true}) {MenuGlyph("http");Spacer(Modifier.width(8.dp));Text("Perfil e cabeçalhos HTTP")}
                        TextButton(onClick={tab=1;inventoryFilter="Respostas";scope.launch {listState.scrollToItem(2)}}) {Text("Ver respostas em Alvos")}
                        if(capture.running) Hint("Captura ativa: esta requisição será enviada pela VPN local e entrará no PCAP.")
                    }}
                }
                3 -> {
                    item {SectionTitle("Captura de tráfego","Fluxos e pacotes da VPN local.")}
                    item {Panel("Captura integrada", "VPN") {CapturePanel(model,onCapture,onStopCapture)}}
                }
                4 -> {
                    item {SectionTitle("Evidências e histórico","Resultados ligados a uma única investigação.")}
                    item {Panel("Esta sessão", "LOCAL") {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Metric("${session.records.size}","registros"); Metric("${session.dropped}","omitidos"); Metric("${history.size}","sessões")}
                        val positions=session.records.filter {it.kind=="location"}
                        if(positions.isNotEmpty()) {
                            var showPositions by remember {mutableStateOf(false)}
                            TextButton(onClick={showPositions=true}) {MenuGlyph("info");Spacer(Modifier.width(8.dp));Text("Ver posições desta sessão (${positions.size})")}
                            if(showPositions) RelatedEvidenceDialog(positions) {showPositions=false}
                        }
                        Box(Modifier.tutorTarget(8)) {CopyAction("Copiar sessão para análise") {AnalysisCopy.session(session)}}
                        Hint("Copia os registros preservados com contexto e evidências. Sessões grandes são divididas em partes numeradas: copie e cole todas em ordem no agente. Para um único arquivo, use Exportar JSON.")
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedButton(onClick={useTutorTarget();onExport(false)},modifier=Modifier.tutorTarget(6)) {Text("Exportar JSON")}; OutlinedButton(onClick={onExport(true)}) {Text("Exportar CSV")}}
                        TextButton(onClick={historyExpanded=!historyExpanded;model.trace("history_details",mapOf("expanded" to "$historyExpanded"))}) {Text(if(historyExpanded) "Ocultar histórico" else "Ver histórico de sessões")}
                        if(historyExpanded) history.take(20).forEach {s -> TextButton(onClick={model.trace("history_session_selected");model.select(s)},enabled=!busy&&!capture.running) {Text("${time(s.created)} · ${s.id.take(8)} · ${s.records.size} registros")}}
                    }}
                }
            }
            if(tab==0) {
                item {SectionTitle("Alvos descobertos","Selecione um IP ou domínio para continuar o recon.")}
                val targets=inventory.filter {it.category!="Serviços"}
                if(targets.isEmpty()) item {Hint("Os alvos aparecerão aqui após a descoberta, DNS ou análise HTTP.")}
                items(targets.take(256),key={"main-${it.key}"}) { entry -> InventoryCard(entry,ready,onTarget={host=entry.target;scope.launch {listState.scrollToItem(4)}},onHttp={url=endpointUrl(entry.target,80,false);tab=2;scope.launch {listState.scrollToItem(0)}},onSsh={model.run("SSH ${entry.target}") {ssh(entry.target)}}) }
            }
            val visibleRecords=when(tab) {
                1 -> if(inventoryFilter in setOf("Todos","IPs","Domínios","Serviços")) emptyList() else findings.filter {when(inventoryFilter) {"Respostas" -> it.kind in setOf("http","error","probe","scan-complete"); "Referências" -> it.kind in setOf("reference","html-redirect"); "Indícios" -> it.kind in setOf("evidence","pcap-match","capture-search"); "DNS" -> it.kind in setOf("dns","dns-error"); "Portal" -> it.kind in setOf("portal","network"); "Segurança" -> it.kind in when(securityFilter) {"Versões"->setOf("version");"Headers"->setOf("security-header");"CVEs"->setOf("cve");"Consultas"->setOf("cve-check");else->setOf("version","security-header","cve","cve-check")}; else -> false}}
                4 -> session.records.asReversed().take(300)
                else -> emptyList()
            }
            if(tab==1&&inventoryFilter !in setOf("Todos","IPs","Domínios","Serviços")||tab==4) item {Text("${visibleRecords.size} registros",style=MaterialTheme.typography.titleMedium)}
            items(visibleRecords,key={it.id}) {r ->
                var expanded by remember(r.id) {mutableStateOf(false)}
                OutlinedCard(onClick={expanded=!expanded;model.trace("evidence_details",mapOf("expanded" to "$expanded"))},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(evidenceLabel(r.kind),style=MaterialTheme.typography.labelSmall,color=if(r.kind.contains("error")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary); Text(time(r.time),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    SelectionContainer {Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(Privacy.mask(r.target),style=MaterialTheme.typography.bodyMedium,fontFamily=FontFamily.Monospace,maxLines=if(expanded) 20 else 2,overflow=TextOverflow.Ellipsis); Text(Privacy.mask(r.message),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                    CopyAction("Copiar achado") {AnalysisCopy.records("Achado",listOf(r))}
                    Text(if(expanded) "▴ Ocultar detalhes" else "▾ Ver detalhes",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                    if(expanded) {
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        EvidenceDetails(r)
                        var related by remember(r.id) {mutableStateOf(false)}
                        val linked=session.records.filter {it.id==r.id || r.parentId!=null && it.parentId==r.parentId || it.details["parent"]==r.target || it.id==r.details["sourceRecordId"] || it.details["sourceRecordId"]==r.id || it.id==r.details["locationRecordId"] || it.details["locationRecordId"]==r.id}
                        TextButton(onClick={related=true}) {ActionGlyph();Text("Ver investigação relacionada (${linked.size})")}
                        if(related) RelatedEvidenceDialog(linked) {related=false}
                    }
                    if(r.kind=="version"&&r.details["cpe"]!=null) {
                        Hint("Consultar a NVD envia somente produto/versão (CPE), sem endereço do alvo nem headers coletados. Requer internet.")
                        Button(onClick={model.run("Consultar CVEs",mapOf("sourceRecordId" to r.id)) {lookupCves(r.target,r.details.getValue("cpe"),r.id,r.details["evidence"].orEmpty())}},enabled=ready) {ActionGlyph();Text("Consultar possíveis CVEs")}
                    }
                    if(r.kind=="reference"&&r.target.startsWith("http")&&!r.target.substringBefore('?').contains("mascarado",true)) TextButton(onClick={url=r.target.substringBefore('?').substringBefore('#');model.status.value="Referência preparada sem parâmetros de consulta";tab=2;scope.launch {listState.scrollToItem(0)}}) {Text("Preparar requisição desta referência")}
                    if(r.kind in setOf("address","domain","host","port","service","tls","mdns-resolved")&&r.target.matches(Regex("[a-zA-Z0-9.:-]+"))) TextButton(onClick={model.trace("evidence_use_as_target");host=r.target;tab=0;scope.launch {listState.scrollToItem(4)}},contentPadding=PaddingValues(horizontal=0.dp)) {Text("Usar como alvo")}
                }}
            }
        }
    }
    if(tutorVisible&&tutorPositioned) tutorTargets[tutorStep]?.takeIf {it.isAttached}?.let { coords ->
        key(tutorStep) {TutorShowcase(tutorStep,coords,onBack={tutorPositioned=false;if(tutorStep>0) tutorStep--},onNext={tutorPositioned=false;if(tutorStep==tutorSteps.lastIndex) closeTutor(true) else tutorStep++},onSkip={closeTutor(false)})}
    }
    }
    }
}
@Composable private fun SectionTitle(title:String,subtitle:String) {Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold); Hint(subtitle)}}
@Composable private fun Hint(text:String) {Text(text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable private fun Panel(title:String,badge:String="",content:@Composable ColumnScope.()->Unit) {Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface) {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {if(badge.isNotEmpty()) Text(badge,style=MaterialTheme.typography.labelSmall,fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.primary); Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}; content()}}}
@Composable private fun Field(value:String,onValue:(String)->Unit,label:String,placeholder:String="") {OutlinedTextField(value=value,onValueChange=onValue,label={Text(label)},placeholder={Text(placeholder)},singleLine=true,textStyle=MaterialTheme.typography.bodyMedium.copy(fontFamily=FontFamily.Monospace),shape=RoundedCornerShape(10.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth())}
@Composable private fun Metric(value:String,label:String) {Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {Text(value,style=MaterialTheme.typography.titleLarge,fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.primary); Hint(label)}}

@Composable private fun ToolIcon(index:Int,color:Color) {
    Canvas(Modifier.size(24.dp)) {
        val unit=size.width/24f; val stroke=1.8f*unit
        fun line(x1:Float,y1:Float,x2:Float,y2:Float)=drawLine(color,Offset(x1*unit,y1*unit),Offset(x2*unit,y2*unit),stroke)
        when(index) {
            0 -> {line(12f,7f,12f,12f); line(5f,12f,19f,12f); line(5f,12f,5f,16f); line(19f,12f,19f,16f); listOf(Offset(9f,2f),Offset(2f,16f),Offset(16f,16f)).forEach {drawRect(color,Offset(it.x*unit,it.y*unit),Size(6*unit,5*unit),style=Stroke(stroke))}}
            1 -> {listOf(5f,12f,19f).forEach {y -> line(3f,y,5f,y);line(9f,y,21f,y)}}
            2 -> {line(8f,5f,2f,12f);line(2f,12f,8f,19f);line(16f,5f,22f,12f);line(22f,12f,16f,19f);line(14f,3f,10f,21f)}
            3 -> {val p=Path().apply {moveTo(1*unit,12*unit);lineTo(6*unit,12*unit);lineTo(9*unit,4*unit);lineTo(14*unit,20*unit);lineTo(18*unit,12*unit);lineTo(23*unit,12*unit)};drawPath(p,color,style=Stroke(stroke))}
            4 -> {drawRect(color,Offset(4*unit,3*unit),Size(16*unit,18*unit),style=Stroke(stroke));line(8f,8f,16f,8f);line(8f,12f,16f,12f);line(8f,16f,13f,16f)}
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CapturePanel(model:InvestigationModel,onCapture:()->Unit,onStop:()->Unit) {
    val onTutorUse=LocalTutorOnUse.current
    val state by CaptureState.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var probeStatus by remember {mutableStateOf("")}
    var probing by remember {mutableStateOf(false)}
    var pendingPath by remember {mutableStateOf<String?>(null)}
    var exportStatus by remember {mutableStateOf("")}
    var captureExportFormat by remember {mutableStateOf("")}
    val save=androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri ->
        if(uri==null) model.trace("export_capture_cancelled",mapOf("format" to captureExportFormat))
        if(uri!=null) pendingPath?.let {path -> runCatching {requireNotNull(context.contentResolver.openOutputStream(uri)) {"Destino indisponível"}.use {out -> java.io.File(path).inputStream().use {it.copyTo(out)}}}.onSuccess {exportStatus="Arquivo exportado";model.trace("export_capture_success",mapOf("format" to captureExportFormat))}.onFailure {exportStatus="Falha ao exportar: ${it.message}";model.trace("export_capture_failed",mapOf("format" to captureExportFormat,"errorType" to it.javaClass.simpleName))}}
    }
    Text(state.status,style=MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Metric("${state.packets}","pacotes");Metric("${state.bytes/1024}","KiB");Metric("${state.flows.size}","fluxos")}
    if(state.running) Button(onClick=onStop,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).tutorTarget(5),colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=Color(0xFF3F0804))) {Text("Parar captura")} else Button(onClick={onTutorUse();onCapture()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).tutorTarget(5)) {Text("Iniciar captura")}
    if(exportStatus.isNotEmpty()) Hint(exportStatus)
    CaptureToolsPanel(model,state.running,state.pcapPath)
    Hint("${state.forwardingErrors} erros de encaminhamento · ${state.omittedFlows} fluxos omitidos · ${state.unsupportedPackets} pacotes não suportados")
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
    Hint("A VPN captura o Sun NetTool e encaminha pela rede Wi-Fi. O consentimento do Android pode substituir outra VPN.")
    Hint("Com a captura ativa, os diagnósticos do app também entram no PCAP. Use os testes prontos para gerar uma resposta fácil de pesquisar.")
    Hint("Limites: 10 minutos, 25 MiB e 256 conexões. Sem descriptografia TLS. O PCAP inclui payload e pode conter dados sensíveis.")
    if(!state.running) FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("PCAP" to state.pcapPath,"JSON" to state.jsonPath,"CSV" to state.csvPath).forEach {(label,path) -> if(path!=null) OutlinedButton(onClick={pendingPath=path;captureExportFormat=label.lowercase();model.trace("export_capture_requested",mapOf("format" to captureExportFormat));save.launch(java.io.File(path).name)}) {Text("Exportar $label")}}}
    if(state.flows.isNotEmpty()) {Text("Fluxos recentes",style=MaterialTheme.typography.labelLarge); state.flows.takeLast(20).forEach {flow -> Text("${flow.protocol} ${flow.destination}:${flow.destinationPort} · ${flow.sentBytes + flow.receivedBytes} B",style=MaterialTheme.typography.bodySmall,fontFamily=FontFamily.Monospace)}}
}

private fun java.io.InputStream.readBytesLimited(limit:Int):ByteArray {
    val output=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    while(true) {val count=read(buffer);if(count<0) break;require(output.size()+count<=limit) {"Arquivo maior que 4 MiB"};output.write(buffer,0,count)}
    return output.toByteArray()
}
private fun endpointUrl(host:String,port:Int,secure:Boolean):String = "${if(secure) "https" else "http"}://${if(':' in host) "[$host]" else host}:$port/"

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun InventoryCard(entry:InventoryItem,ready:Boolean,onTarget:()->Unit,onHttp:()->Unit,onSsh:()->Unit) {
    var expanded by remember(entry.key) {mutableStateOf(false)}
    OutlinedCard(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth(),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(entry.category.uppercase()+if(expanded) "  ▴ Ocultar detalhes" else "  ▾ Ver detalhes",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
            val name=entry.records.lastOrNull {it.kind=="domain"&&it.details["source"]=="DNS PTR"}?.target
            val page=entry.records.lastOrNull {it.kind=="http"}?.details?.get("pageTitle")?.takeIf {entry.port!=null && it.isNotBlank() && !it.matches(Regex("(?i)(?:HTTP Status )?[1-5][0-9]{2}.*"))}
            Text(page ?: name ?: entry.service ?: entry.target,style=MaterialTheme.typography.titleMedium)
            if(page!=null) Hint("Título informado pela página; produto não verificado") else if(name!=null) Hint("Nome informado pelo DNS; produto não verificado")
            if(entry.port==null&&name!=null) Hint("IP: ${entry.target}")
            if(entry.port!=null) Hint("IP: ${entry.target} · Porta TCP: ${entry.port}")
            if(entry.records.lastOrNull {it.details["protocolObserved"]=="true"}?.details?.get("certificateValid")=="false") Text("Certificado TLS não validado",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
            Text(entry.service ?: if(entry.present) "Resposta observada" else "Referência ou presença inconclusiva",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            entry.records.lastOrNull {it.kind=="version"}?.let {Hint(it.message)}
            val latest=entry.records.last()
            Hint("${entry.records.size} evidências · origem: ${latest.details["source"] ?: latest.kind}")
            if(expanded) {
                var all by remember(entry.key) {mutableStateOf(false)}
                entry.records.takeLast(2).forEach {r -> EvidenceDetails(r)}
                TextButton(onClick={all=true}) {ActionGlyph();Text("Ver todas as evidências (${entry.records.size})")}
                if(all) RelatedEvidenceDialog(entry.records) {all=false}
            }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                CopyAction("Copiar item") {AnalysisCopy.records("${entry.category}: ${entry.target}"+(entry.port?.let {" | Porta TCP: $it"} ?: ""),entry.records)}
                TextButton(onClick=onTarget) {ToolIcon(0,MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)); Text("Recon")}
                TextButton(onClick=onHttp) {ToolIcon(2,MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)); Text("Preparar HTTP")}
                if(entry.service=="SSH"||entry.port==22||entry.port==2222) TextButton(onClick=onSsh,enabled=ready) {Text("Ler banner SSH")}
            }
        }
    }
}
