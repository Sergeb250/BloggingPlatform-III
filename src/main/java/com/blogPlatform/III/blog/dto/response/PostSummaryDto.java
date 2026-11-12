package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.entity.PostStatus;

public record PostSummaryDto(Long postId, String title, String author, PostStatus status, Long commentCount) {
}
