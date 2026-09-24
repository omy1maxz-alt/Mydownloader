import sys

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "r") as f:
    content = f.read()

# Fix cache flags to make sure spans complete securely
old_cache_factory = """            .setCache(getUnifiedCache(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, getDataSourceFactory(context)))
            // DO NOT STRIP QUERY FROM CACHE KEY if it's the primary content identifier, or at least
            // ensure the query is not mistakenly stripped from the URI itself by some Exoplayer bug.
            .setCacheKeyFactory(customCacheKeyFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (readOnly) f.setCacheWriteDataSinkFactory(null)"""

new_cache_factory = """            .setCache(getUnifiedCache(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, getDataSourceFactory(context)))
            // DO NOT STRIP QUERY FROM CACHE KEY if it's the primary content identifier, or at least
            // ensure the query is not mistakenly stripped from the URI itself by some Exoplayer bug.
            .setCacheKeyFactory(customCacheKeyFactory)
            .setFlags(if (readOnly) androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR else androidx.media3.datasource.cache.CacheDataSource.FLAG_BLOCK_ON_CACHE)
        if (readOnly) f.setCacheWriteDataSinkFactory(null)"""

content = content.replace(old_cache_factory, new_cache_factory)

with open("app/src/main/java/com/omymaxz/download/HlsDownloadHelper.kt", "w") as f:
    f.write(content)

print("Done")
