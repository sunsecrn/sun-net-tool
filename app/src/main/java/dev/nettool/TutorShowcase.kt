package dev.nettool

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.joco.showcaseview.ShowcaseView
import com.joco.showcaseview.ShowcaseAlignment

internal data class TutorStep(val tab:Int,val item:Int,val title:String,val description:String)
internal val tutorSteps=listOf(
    TutorStep(0,0,"Confira sua conexão","Toque em Detalhes para ver IP, gateway e DNS do Wi-Fi."),
    TutorStep(0,3,"Descubra dispositivos","Descobrir hosts procura respostas na faixa IPv4 informada acima."),
    TutorStep(0,4,"Teste as portas","Escolha 1.000 portas comuns, todas TCP ou um intervalo. A faixa de progresso mostra etapa, alvo, tempo e Cancelar. Porta testada não significa porta aberta."),
    TutorStep(1,3,"Explore os alvos","Filtre IPs, domínios e serviços. Toque em Ver detalhes para abrir evidências, use Recon ou Preparar HTTP e o ícone de cópia para levar um item a outro agente."),
    TutorStep(2,3,"Investigue HTTP","Informe a URL e execute. Em Alvos, veja resposta, headers, referências e medições separados. O perfil HTTP fica no menu de três pontos > Configurações gerais."),
    TutorStep(3,3,"Capture o tráfego","Iniciar captura solicita a VPN local. Depois, simule HTTP, HTTPS, DNS ou TCP e pare para buscar texto ou regex no PCAP."),
    TutorStep(4,3,"Exporte a investigação","Exportar JSON salva as evidências e as posições registradas. CSV abre os dados em planilha. O PCAP é exportado separadamente pela Captura."),
    TutorStep(1,3,"Versões, headers e possíveis CVEs","Segurança reúne versões anunciadas e headers. Abra uma versão para consultar possíveis CVEs na NVD. A evidência e a fonte aparecem nos detalhes; uma correspondência não confirma vulnerabilidade."),
    TutorStep(4,3,"Continue a análise com outro agente","Copiar sessão inclui resumo, evidências, erros e vínculos. Sessões grandes abrem partes numeradas: cole todas em ordem. Credenciais são mascaradas; posições registradas são incluídas."),
    TutorStep(0,0,"Configurações, debug e GPS","O menu de três pontos abre Configurações gerais: perfil HTTP, debug e localização. GPS vem desligado e pede permissão ao ativar. Com o app visível, registra posição e precisão na sessão; o JSON permite comparar horários com o PCAP.")
)
internal class TutorPreferences(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("tutor_spotlight",Context.MODE_PRIVATE)
    val shouldShow get() = !prefs.getBoolean("dismissed",false)
    val step get() = prefs.getInt("step",0).coerceIn(tutorSteps.indices)
    fun progress(step:Int) {prefs.edit().putInt("step",step.coerceIn(tutorSteps.indices)).apply()}
    fun finish() {prefs.edit().putBoolean("dismissed",true).putInt("step",0).apply()}
}
internal val LocalTutorOnUse=staticCompositionLocalOf<()->Unit> {{}}
internal val LocalTutorTargets=staticCompositionLocalOf<((Int,LayoutCoordinates)->Unit)> { {_,_->} }
@Composable internal fun Modifier.tutorTarget(index:Int):Modifier {
    val register=LocalTutorTargets.current
    return onGloballyPositioned {register(index,it)}
}
@Composable internal fun TutorShowcase(index:Int,coordinates:LayoutCoordinates,onBack:()->Unit,onNext:()->Unit,onSkip:()->Unit) {
    BackHandler(onBack=onSkip)
    // Only the highlighted control accepts touches beneath the guide.
    val density=LocalDensity.current
    val rect=coordinates.boundsInRoot()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val left=with(density) {rect.left.toDp()}.coerceIn(0.dp,maxWidth)
        val top=with(density) {rect.top.toDp()}.coerceIn(0.dp,maxHeight)
        val right=with(density) {rect.right.toDp()}.coerceIn(left,maxWidth)
        val bottom=with(density) {rect.bottom.toDp()}.coerceIn(top,maxHeight)
        Box(Modifier.fillMaxWidth().height(top).clickable(onClick={}))
        Box(Modifier.offset(y=bottom).fillMaxWidth().height(maxHeight-bottom).clickable(onClick={}))
        Box(Modifier.offset(y=top).width(left).height(bottom-top).clickable(onClick={}))
        Box(Modifier.offset(x=right,y=top).width(maxWidth-right).height(bottom-top).clickable(onClick={}))
    }
    ShowcaseView(visible=true,targetCoordinates=coordinates,alignment=if(index==9) ShowcaseAlignment.End else ShowcaseAlignment.Start) {
        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surface,shadowElevation=8.dp) {
            Column(Modifier.widthIn(max=320.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("GUIA · ${index+1}/${tutorSteps.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                Text(tutorSteps[index].title,style=MaterialTheme.typography.titleMedium)
                Text(tutorSteps[index].description,style=MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick=onSkip) {Text("Pular")}
                    TextButton(onClick=onBack,enabled=index>0) {Text("Voltar")}
                    Button(onClick=onNext) {Text(if(index==tutorSteps.lastIndex) "Concluir" else "Próximo")}
                }
            }
        }
    }
}
