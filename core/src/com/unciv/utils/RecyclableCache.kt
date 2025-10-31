package com.unciv.utils

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Helper classes for recycling larger caches
 * 
 * For single threaded CPU-intensive code that juggles caches with large internal arrays
 * This recycles the caches, therefore requiring the GC to run *far* less often. As a bonus,
 * reusing the same blocks of memory helps with CPU L1 cache usage, for additional performance.
 * 
 * Each call to RecyclableCache.get() returns the same cache over and over, until clear()
 * is called. clear() passes the cache to the CacheRecycler, which can store up to two
 * cache instances, for other calculations to use.  When RecyclableCache has no cache, and
 * get() is called, it asks the CacheRecycler for one. If the CacheRecycler has one, it uses
 * the resetCache function to reset it, and returns it. Otherwise, it uses the createCache
 * method to produce a new instance.
 * 
 * Usage:
 * 
 * class MyClass {
 *    val recyclableCache = recyclableBuffer<List<String>>(cacheRecycler)
 *    
 *    fun calculateThing() {
 *        List<String> cache = recyclableCache.get()
 *        // do things with cache.
 *        // each run uses the same cache.
 *    }
 *    
 *    fun clear() = recyclableCache.clear()
 *    
 *    companion object {
 *        val cacheRecycler = CacheRecycler({List<String>()})
 *     }
 */
class RecyclableCache<T>(
    val cacheRecycler: CacheRecycler<T>,
    val prepareCache: (cache: T, requiresReset: Boolean)->Boolean,
) {
    private val currentCache: AtomicReference<T> = AtomicReference<T>()
    
    fun peek(): T? = currentCache.get()
    
    fun get(): T {
        do {
            val local = currentCache.get()
            if (local != null && prepareCache(local, false))
                return local // Success!
            // need to fetch a cache from the recycler
            val remote = cacheRecycler.get()
            // reset the cache before storing it in currentCache to prevent other threads from accesssing it before it's ready
            if (!prepareCache(remote, true)) // Something wrong with this cache instance? Dump it and retry
                continue;
            if (currentCache.compareAndSet(null, remote)) return remote // Success!
            // If two threads prepare caches for this instance, then the second one returns its
            // buffer to the recycler, and retries, which will use the cache from the first thread.
            cacheRecycler.put(remote)
        } while (true)
    }
    
    fun clear(): Boolean {
        val oldCache = currentCache.getAndSet(null) ?: return false
        cacheRecycler.put(oldCache)
        return true
    }
}

class CacheRecycler<T>(val createCache: ()->T) {
    private val reusable = Array<AtomicReference<WeakReference<T>>>(4) {AtomicReference<WeakReference<T>>()}

    internal fun get(): T {
        for (i in 0..<reusable.size) {
            val weakRef = reusable[i].getAndSet(null) ?: continue
            val cache = weakRef.get()
            if (cache != null) return cache
            reusable[i].compareAndSet(weakRef, null)
        }
        return createCache()
    }

    internal fun put(buffer: T): Boolean {
        for (i in 0..<reusable.size) {
            val weakRef = reusable[i].get()
            val oldCache = weakRef?.get()
            if (weakRef == null || oldCache == null) {
                if (reusable[i].compareAndSet(weakRef, WeakReference<T>(buffer)))
                    return true
            }
        }
        return false
    }
    
    fun dumpAllCaches() {
        for (i in 0..<reusable.size)
            reusable[i].set(null)
    }
}
