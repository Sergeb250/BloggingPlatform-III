package com.blogPlatform.III.blog.dto.response;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> content,
        int size,
        boolean hasNext,
        String nextCursor
) {
}
