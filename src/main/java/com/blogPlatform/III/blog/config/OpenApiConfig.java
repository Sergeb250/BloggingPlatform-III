package com.blogPlatform.III.blog.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI blogPlatformOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Blogging Platform API")
                .version("1.0")
                .description("""
                        REST API for users, posts, tags, comments and reviews.

                        **Every response has the same shape:**
                        ```
                        { "success": true,  "status": 200, "message": "...", "data": { ... }, "timestamp": "..." }
                        { "success": false, "status": 404, "message": "...", "errors": { ... }, "path": "...", "timestamp": "..." }
                        ```
                        **Every list is paged** (`page`, `size`, `sort`) and returns `data.content` + `page`, `size`,
                        `totalElements`, `totalPages`. Without parameters you get page 0 with a default size and order.
                        `size` must be 1-100, and only the listed sort fields are allowed (otherwise 400).
                        The feed (`/api/posts/feed`) pages with a cursor instead.

                        **Strict validation:** every body field, path id and query parameter is checked (Bean Validation);
                        unknown JSON fields, wrong JSON types ("5" for a number, 4.5 for a whole number) and duplicate
                        fields are refused. A 400 lists every problem: `errors: { "field": "what is wrong" }`.

                        **Optimistic locking:** `PUT` requests must send the `version` they read. An old version -> 409.

                        **Caching:** posts, users, tags, popular and top-rated posts are cached in memory (Caffeine);
                        see `GET /api/cache-stats`.
                        """));
    }
}
