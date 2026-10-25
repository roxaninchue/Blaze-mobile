package com.blaze.kiln.runtime

import com.blaze.kiln.route.IdentityRewriter

// Content providers in the guest process (ENGINE_DESIGN.md 1.4). A guest's ContentResolver sends its context's
// AttributionSource (package = the guest) with every IContentProvider call; the provider side (AOSP
// ContentProvider.getCallingAttributionSource -> AppOpsManager.checkPackage) then throws "Package <guest> does not
// belong to <uid>". First seen on the API 34 emulator: Settings.Global.getInt from PhoneWindow.<init> during
// Activity.attach. Fix: every IContentProvider this process holds is wrapped in a ServiceShim, whose AttributionSource
// and package-name adapters rewrite the guest identity to Blaze's before the binder call.
//
// Where providers come from (AOSP ActivityThread / Settings):
//  - IActivityManager.getContentProvider(...) returns a ContentProviderHolder (field `provider`): wrapped as returned;
//  - ActivityThread.mProviderMap (ProviderClientRecord.mProvider) and mProviderRefCountMap (ProviderRefCount.holder)
//    for providers acquired before the hooks: wrapped by [sweep];
//  - Settings.{Global,Secure,System,Config}.sNameValueCache.mProviderHolder.mContentProvider (static caches).

internal object ProviderShim {
    val RESULT_METHODS = listOf("getContentProvider", "getContentProviderExternal")

    private fun isWrapped(p: Any?): Boolean = p != null && java.lang.reflect.Proxy.isProxyClass(p.javaClass)

    fun wrapProvider(provider: Any?, rewriter: IdentityRewriter): Any? {
        if (provider == null || isWrapped(provider)) return provider
        return ServiceShim.wrap("content provider", provider, rewriter, AndroidArgAdapters.all())
    }

    /** android.app.ContentProviderHolder: wraps its `provider` in place and returns the holder. */
    fun wrapHolder(holder: Any?, rewriter: IdentityRewriter): Any? {
        if (holder == null) return null
        val f = HiddenApi.field(holder.javaClass, "provider") ?: return holder
        val p = HiddenApi.get(f, holder)
        if (p != null && !isWrapped(p)) HiddenApi.set(f, holder, wrapProvider(p, rewriter))
        return holder
    }

    /** Wraps providers this process already holds. Returns how many were wrapped. */
    fun sweep(rewriter: IdentityRewriter): Int {
        var n = 0
        val at = ActivityThreadRef.current()
        if (at != null) {
            (HiddenApi.get(HiddenApi.field(at.javaClass, "mProviderMap"), at) as? Map<*, *>)?.values?.toList()?.forEach { rec ->
                if (rec == null) return@forEach
                val f = HiddenApi.field(rec.javaClass, "mProvider") ?: return@forEach
                val p = HiddenApi.get(f, rec)
                if (p != null && !isWrapped(p) && HiddenApi.set(f, rec, wrapProvider(p, rewriter))) n++
            }
            (HiddenApi.get(HiddenApi.field(at.javaClass, "mProviderRefCountMap"), at) as? Map<*, *>)?.values?.toList()?.forEach { rc ->
                if (rc == null) return@forEach
                val holder = HiddenApi.get(HiddenApi.field(rc.javaClass, "holder"), rc) ?: return@forEach
                val before = HiddenApi.get(HiddenApi.field(holder.javaClass, "provider"), holder)
                wrapHolder(holder, rewriter)
                if (before != null && !isWrapped(before)) n++
            }
        }
        for (name in listOf("Global", "Secure", "System", "Config")) {
            val cls = HiddenApi.classOrNull("android.provider.Settings\$$name") ?: continue
            val cache = HiddenApi.get(HiddenApi.field(cls, "sNameValueCache"), null) ?: continue
            val holder = HiddenApi.get(HiddenApi.field(cache.javaClass, "mProviderHolder"), cache) ?: continue
            val f = HiddenApi.field(holder.javaClass, "mContentProvider") ?: continue
            synchronized(holder) {
                val p = HiddenApi.get(f, holder)
                if (p != null && !isWrapped(p) && HiddenApi.set(f, holder, wrapProvider(p, rewriter))) n++
            }
        }
        return n
    }
}
