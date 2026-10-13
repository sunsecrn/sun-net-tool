package dev.nettool

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun MenuGlyph(kind:String) {
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(24.dp)) {
        val u=size.width/24
        when(kind) {
            "menu" -> listOf(5f,12f,19f).forEach {drawCircle(color,1.8f*u,Offset(12*u,it*u))}
            "new" -> {drawLine(color,Offset(4*u,12*u),Offset(20*u,12*u),2*u);drawLine(color,Offset(12*u,4*u),Offset(12*u,20*u),2*u)}
            "settings","location" -> {drawCircle(color,7*u,style=Stroke(2*u));drawCircle(color,2*u,style=Stroke(2*u));(0 until 360 step if(kind=="settings") 45 else 90).forEach {angle->val r=Math.toRadians(angle.toDouble());drawLine(color,center+Offset(kotlin.math.cos(r).toFloat()*7*u,kotlin.math.sin(r).toFloat()*7*u),center+Offset(kotlin.math.cos(r).toFloat()*11*u,kotlin.math.sin(r).toFloat()*11*u),3*u)}}
            "http" -> {
                val page=androidx.compose.ui.graphics.Path().apply {moveTo(5*u,3*u);lineTo(14*u,3*u);lineTo(19*u,8*u);lineTo(19*u,21*u);lineTo(5*u,21*u);close()}
                drawPath(page,color,style=Stroke(2*u))
                drawLine(color,Offset(14*u,3*u),Offset(14*u,8*u),2*u)
                drawLine(color,Offset(14*u,8*u),Offset(19*u,8*u),2*u)
                listOf(12f,16f).forEach {drawLine(color,Offset(8*u,it*u),Offset(16*u,it*u),2*u)}
            }
            "debug" -> {drawRect(color,Offset(4*u,4*u),androidx.compose.ui.geometry.Size(16*u,16*u),style=Stroke(2*u));listOf(8f,12f,16f).forEach {drawLine(color,Offset(7*u,it*u),Offset(17*u,it*u),u)}}
            else -> {drawCircle(color,9*u,style=Stroke(2*u));drawCircle(color,u,Offset(12*u,7*u));drawLine(color,Offset(12*u,11*u),Offset(12*u,17*u),2*u)}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun GeneralSettingsDialog(model:InvestigationModel,onClose:()->Unit,onHttp:()->Unit,onDebug:()->Unit,debugContent:@Composable ()->Unit) {
    val context=LocalContext.current
    val enabled by model.location.enabled.collectAsState()
    val status by model.location.status.collectAsState()
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {grants ->
        val allowed=grants.values.any {it}
        model.location.setEnabled(allowed)
        if(!allowed) model.location.status.value="Permissão não concedida. Nenhuma posição será registrada."
    }
    ScreenDialog(onClose) {
        Scaffold(
            modifier=Modifier.fillMaxSize(),
            topBar={TopAppBar(title={Text("Configurações gerais")},navigationIcon={TextButton(onClick=onClose) {Text("Voltar")}})},
            bottomBar={Surface(tonalElevation=3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp,vertical=8.dp)) {
                    OutlinedButton(onClick=onDebug,modifier=Modifier.fillMaxWidth()) {MenuGlyph("debug");Spacer(Modifier.width(8.dp));Text("Abrir debug da sessão")}
                }
            }}
        ) {padding->
            LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(start=16.dp,end=16.dp,top=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                item { Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Localização da sessão",style=MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Anotar GPS / localização",Modifier.weight(1f))
                        Switch(checked=enabled,onCheckedChange={value->
                            if(!value) model.location.setEnabled(false)
                            else if(model.location.permitted()) model.location.setEnabled(true)
                            else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
                        },modifier=Modifier.semantics {contentDescription="Anotar localização nas sessões"})
                    }
                    Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                    Text("Desligado por padrão. Com sua permissão, registra posição, horário e precisão enquanto o app está visível. A localização aproximada também funciona. Pode demorar para obter GPS em ambientes internos.",style=MaterialTheme.typography.bodySmall)
                    Text("A posição é do telefone, não do alvo. Evidências usam a referência de uma posição de até 60 s. Durante a captura, os registros de localização são marcados para comparação por horário. O PCAP não incorpora GPS: exporte também o JSON da sessão. Coordenadas entram nas cópias e exportações locais.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))}) {MenuGlyph("location");Spacer(Modifier.width(8.dp));Text("Localização do Android")}
                }}
                item {Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {HorizontalDivider();TextButton(onClick=onHttp) {MenuGlyph("http");Spacer(Modifier.width(8.dp));Text("Perfil e cabeçalhos HTTP")}}}
                item {Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {HorizontalDivider();debugContent()}}
            }
        }
    }
}
