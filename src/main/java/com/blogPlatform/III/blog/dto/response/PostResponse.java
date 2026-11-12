package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.entity.Tag;

import java.time.LocalDateTime;
import java.util.List;

public record PostResponse(
        Long id,
        String title,
        String content,
        PostStatus status,
        Long authorId,
        String authorUsername,
        List<String> tags,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version
) {
    public static PostResponse from(Post post) {
        List<String> tagNames = post.getTags().stream()
                .map(Tag::getName)
                .sorted()
                .toList();

        return new PostResponse(post.getId(), post.getTitle(), post.getContent(), post.getStatus(),
                post.getAuthor().getId(), post.getAuthor().getUsername(), tagNames,
                post.getCreatedAt(), post.getUpdatedAt(), post.getVersion());
    }
}
