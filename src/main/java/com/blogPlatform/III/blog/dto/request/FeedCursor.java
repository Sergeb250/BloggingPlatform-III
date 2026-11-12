package com.blogPlatform.III.blog.dto.request;

import com.blogPlatform.III.blog.entity.Post;

import java.time.LocalDateTime;
import java.util.Map;

public record FeedCursor(LocalDateTime createdAt, long id) {

    public static FeedCursor parse(String text) {
        int comma = text.lastIndexOf(',');
        if (comma < 0) {
            throw new IllegalArgumentException("a cursor looks like '<createdAt>,<id>'");
        }
        return new FeedCursor(LocalDateTime.parse(text.substring(0, comma)),
                Long.parseLong(text.substring(comma + 1)));
    }

    public static FeedCursor of(Post post) {
        return new FeedCursor(post.getCreatedAt(), post.getId());
    }

    public String asText() {
        return createdAt + "," + id;
    }

    public Map<String, Object> keys() {
        return Map.of("createdAt", createdAt, "id", id);
    }
}
