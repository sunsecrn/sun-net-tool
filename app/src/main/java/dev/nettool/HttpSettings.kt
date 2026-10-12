package dev.nettool
import android.content.Context
import dev.nettool.core.HttpProfile
class HttpSettings(context:Context) {
    private val prefs=context.getSharedPreferences("http-profile",Context.MODE_PRIVATE)
    fun headers()=HttpProfile.validate((HttpProfile.defaults+("Cache-Control" to "")).mapValues {(key,value)->prefs.getString(key,value).orEmpty()})
    fun save(values:Map<String,String>) {val safe=HttpProfile.validate(values);prefs.edit().clear().also {edit->safe.forEach {(k,v)->edit.putString(k,v)}}.apply()}
}
