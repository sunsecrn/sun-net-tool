package dev.nettool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

internal data class GeoFix(val latitude:Double,val longitude:Double,val accuracy:Float,val time:Long,val elapsedNanos:Long,val provider:String,val mock:Boolean) {
    fun usable(nowNanos:Long)=latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 && accuracy.isFinite() && accuracy>=0 && nowNanos>=elapsedNanos && nowNanos-elapsedNanos<=60_000_000_000L
    fun details()=mapOf("latitude" to "$latitude","longitude" to "$longitude","accuracyMeters" to "$accuracy","fixTime" to java.time.Instant.ofEpochMilli(time).toString(),"provider" to provider,"mock" to "$mock")
}

/** Foreground-only, explicit opt-in. No last-known fix is silently reused. */
class SessionLocation(private val context:Context,private val onFix:(Record)->Unit) {
    private val prefs=context.getSharedPreferences("general-settings",Context.MODE_PRIVATE)
    val enabled=MutableStateFlow(prefs.getBoolean("location-enabled",false))
    val status=MutableStateFlow("Localização desativada")
    private val manager=context.getSystemService(LocationManager::class.java)
    private var visible=false
    private var listening=false
    private var latest:Pair<String,GeoFix>?=null
    private var lastRecordedNanos=0L
    private val listener=object:LocationListener {
        override fun onLocationChanged(location:Location) {
            if(!enabled.value || !visible || !listening)return
            val fix=GeoFix(location.latitude,location.longitude,location.accuracy,location.time,location.elapsedRealtimeNanos,location.provider.orEmpty(),location.isFromMockProvider)
            val now=android.os.SystemClock.elapsedRealtimeNanos()
            if(!location.hasAccuracy() || !fix.usable(now)) {status.value="Aguardando posição recente";return}
            if(lastRecordedNanos>0 && now-lastRecordedNanos<30_000_000_000L)return
            lastRecordedNanos=now
            val record=Record(kind="location",target="Posição do aparelho",message="Localização observada, precisão aproximada ${fix.accuracy.toInt()} m",details=fix.details())
            latest=record.id to fix;status.value="Posição registrada, precisão aproximada ${fix.accuracy.toInt()} m"
            onFix(record)
        }
        override fun onProviderDisabled(provider:String) {latest=null;status.value="Localização do Android indisponível"}
        override fun onProviderEnabled(provider:String) {status.value="Aguardando posição recente"}
        @Deprecated("Legacy Android callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
    }
    fun permitted()=context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    fun setEnabled(value:Boolean) {enabled.value=value;prefs.edit().putBoolean("location-enabled",value).apply();refresh()}
    fun setVisible(value:Boolean) {visible=value;refresh()}
    fun resetSession() {latest=null;lastRecordedNanos=0}
    fun association():Map<String,String> {
        val (id,fix)=latest ?: return emptyMap()
        return if(enabled.value && visible && permitted() && fix.usable(android.os.SystemClock.elapsedRealtimeNanos())) mapOf("locationRecordId" to id,"locationMeaning" to "Posição recente do aparelho, não do serviço remoto") else emptyMap()
    }
    @Suppress("MissingPermission")
    private fun refresh() {
        close();latest=null
        if(!enabled.value) {status.value="Localização desativada";return}
        if(!visible) {status.value="Localização pausada: app fora da tela";return}
        if(!permitted()) {status.value="Permissão de localização não concedida";return}
        if(!manager.isLocationEnabled) {status.value="Ative a localização nas configurações do Android";return}
        val fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val providers=listOfNotNull(LocationManager.GPS_PROVIDER.takeIf {fine},LocationManager.NETWORK_PROVIDER).filter {manager.isProviderEnabled(it)}
        listening=true
        var registered=0
        providers.forEach {provider->runCatching {manager.requestLocationUpdates(provider,30_000L,10f,listener,Looper.getMainLooper());registered++}.onFailure {status.value="Não foi possível iniciar a localização"}}
        if(registered==0) {listening=false;status.value="Nenhum provedor de localização disponível ou autorizado"} else status.value="Aguardando posição recente (${if(fine) "precisa" else "aproximada"})"
    }
    fun close() {manager.removeUpdates(listener);listening=false;latest=null}
}
