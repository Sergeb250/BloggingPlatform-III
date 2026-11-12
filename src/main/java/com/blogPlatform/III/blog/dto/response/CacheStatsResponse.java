package com.blogPlatform.III.blog.dto.response;

public record CacheStatsResponse(
        String name,
        long size,
        long hits,
        long misses,
        double hitRate,
        long evictions
) {
}
