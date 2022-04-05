package com.tyron.builder.cache.internal;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.tyron.builder.cache.AsyncCacheAccess;
import com.tyron.builder.cache.CacheDecorator;
import com.tyron.builder.cache.CrossProcessCacheAccess;
import com.tyron.builder.cache.FileLock;
import com.tyron.builder.cache.MultiProcessSafePersistentIndexedCache;

import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * A {@link CacheDecorator} that wraps each cache with an in-memory cache that is used to short-circuit reads from the backing cache.
 * The in-memory cache is invalidated when the backing cache is changed by another process.
 *
 * Also decorates each cache so that updates to the backing cache are made asynchronously.
 */
public class DefaultInMemoryCacheDecoratorFactory implements InMemoryCacheDecoratorFactory {
    private final static Logger LOG = Logger.getLogger(DefaultInMemoryCacheDecoratorFactory.class.getSimpleName());
    private final boolean longLivingProcess;
    private final HeapProportionalCacheSizer cacheSizer = new HeapProportionalCacheSizer();
    private final CrossBuildInMemoryCache<String, CacheDetails> caches;

    public DefaultInMemoryCacheDecoratorFactory(boolean longLivingProcess, CrossBuildInMemoryCacheFactory cacheFactory) {
        this.longLivingProcess = longLivingProcess;
        caches = cacheFactory.newCache();
    }

    @Override
    public CacheDecorator decorator(final int maxEntriesToKeepInMemory, final boolean cacheInMemoryForShortLivedProcesses) {
        return new InMemoryCacheDecorator(maxEntriesToKeepInMemory, cacheInMemoryForShortLivedProcesses);
    }

    protected <K, V> MultiProcessSafeAsyncPersistentIndexedCache<K, V> applyInMemoryCaching(String cacheId, MultiProcessSafeAsyncPersistentIndexedCache<K, V> backingCache, int maxEntriesToKeepInMemory, boolean cacheInMemoryForShortLivedProcesses) {
        if (!longLivingProcess && !cacheInMemoryForShortLivedProcesses) {
            // Short lived process, don't cache in memory
            LOG.info("Creating cache " + cacheId + " without in-memory store.");
            return backingCache;
        }
        int targetSize = cacheSizer.scaleCacheSize(maxEntriesToKeepInMemory);
        CacheDetails cacheDetails = getCache(cacheId, targetSize);
        return new InMemoryDecoratedCache<>(backingCache, cacheDetails.entries, cacheId, cacheDetails.lockState);
    }

    private CacheDetails getCache(final String cacheId, final int maxSize) {
        CacheDetails cacheDetails = caches.get(cacheId, () -> {
            Cache<Object, Object> entries = createInMemoryCache(cacheId, maxSize);
            CacheDetails details = new CacheDetails(cacheId, maxSize, entries, new AtomicReference<>(null));
            LOG.info("Creating in-memory store for cache " + cacheId + " (max size: "  + maxSize + ")");
            return details;
        });
        if (cacheDetails.maxEntries != maxSize) {
            throw new IllegalStateException("Mismatched in-memory store size for cache " + cacheId + ", expected: " + maxSize + ", found: " + cacheDetails.maxEntries);
        }
        return cacheDetails;
    }

    private Cache<Object, Object> createInMemoryCache(String cacheId, int maxSize) {
        LoggingEvictionListener evictionListener = new LoggingEvictionListener(cacheId, maxSize);
        final CacheBuilder<Object, Object>
                cacheBuilder = CacheBuilder.newBuilder().maximumSize(maxSize).recordStats().removalListener(evictionListener);
        Cache<Object, Object> inMemoryCache = cacheBuilder.build();
        evictionListener.setCache(inMemoryCache);
        return inMemoryCache;
    }

    private class InMemoryCacheDecorator implements CacheDecorator {
        private final int maxEntriesToKeepInMemory;
        private final boolean cacheInMemoryForShortLivedProcesses;

        InMemoryCacheDecorator(int maxEntriesToKeepInMemory, boolean cacheInMemoryForShortLivedProcesses) {
            this.maxEntriesToKeepInMemory = maxEntriesToKeepInMemory;
            this.cacheInMemoryForShortLivedProcesses = cacheInMemoryForShortLivedProcesses;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null || obj.getClass() != getClass()) {
                return false;
            }
            InMemoryCacheDecorator other = (InMemoryCacheDecorator) obj;
            return maxEntriesToKeepInMemory == other.maxEntriesToKeepInMemory && cacheInMemoryForShortLivedProcesses == other.cacheInMemoryForShortLivedProcesses;
        }

        @Override
        public int hashCode() {
            return maxEntriesToKeepInMemory ^ (cacheInMemoryForShortLivedProcesses ? 1 : 0);
        }

        @Override
        public <K, V> MultiProcessSafePersistentIndexedCache<K, V> decorate(String cacheId, String cacheName, MultiProcessSafePersistentIndexedCache<K, V> persistentCache, CrossProcessCacheAccess crossProcessCacheAccess, AsyncCacheAccess asyncCacheAccess) {
            MultiProcessSafeAsyncPersistentIndexedCache<K, V> asyncCache = new AsyncCacheAccessDecoratedCache<>(asyncCacheAccess, persistentCache);
            MultiProcessSafeAsyncPersistentIndexedCache<K, V> memCache = applyInMemoryCaching(cacheId, asyncCache, maxEntriesToKeepInMemory, cacheInMemoryForShortLivedProcesses);
            return new CrossProcessSynchronizingCache<>(memCache, crossProcessCacheAccess);
        }
    }

    private static class CacheDetails {
        private final String cacheId;
        private final int maxEntries;
        private final Cache<Object, Object> entries;
        private final AtomicReference<FileLock.State> lockState;

        CacheDetails(String cacheId, int maxEntries, Cache<Object, Object> entries, AtomicReference<FileLock.State> lockState) {
            this.cacheId = cacheId;
            this.maxEntries = maxEntries;
            this.entries = entries;
            this.lockState = lockState;
        }
    }
}