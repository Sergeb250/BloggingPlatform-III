package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.entity.Tag;

public record TagResponse(Long id, String name) {

    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName());
    }
}
