package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.entity.Comment;

import java.time.LocalDateTime;

public record CommentResponse(Long id, Long postId, Long userId, String username,
                              String content, LocalDateTime createdAt,
                              Long version) {
    public static CommentResponse from(Comment comment) {
        return new CommentResponse(comment.getId(), comment.getPost().getId(),
                comment.getUser().getId(), comment.getUser().getUsername(),
                comment.getContent(), comment.getCreatedAt(), comment.getVersion());
    }
}
