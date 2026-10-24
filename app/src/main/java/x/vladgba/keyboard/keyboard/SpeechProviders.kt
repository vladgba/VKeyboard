package x.vladgba.keyboard.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognitionService

object SpeechProviders {
    class Provider(val component: ComponentName, val label: String)
    fun available(ctx: Context): List<Provider> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        return pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).mapNotNull { match ->
            val service = match.serviceInfo ?: return@mapNotNull null
            if (!service.enabled || !service.exported) return@mapNotNull null
            Provider(ComponentName(service.packageName, service.name), service.loadLabel(pm).toString())
        }.distinctBy { it.component }.sortedBy { it.label.lowercase() }
    }

    fun resolve(enabled: Boolean, saved: String, providers: List<Provider>): ComponentName? {
        if (!enabled || saved.isBlank()) return null
        val component = ComponentName.unflattenFromString(saved) ?: return null
        return providers.firstOrNull { it.component == component }?.component
    }
}
