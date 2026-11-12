package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.config.CacheConfig;
import com.blogPlatform.III.blog.dto.request.FeedCursor;
import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.repository.PostRepository;
import com.blogPlatform.III.blog.service.PostService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// needs the seeded blog_db: mvnw test -Dtest=PerformanceTest -Dperf=true
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("perf")
@EnabledIfSystemProperty(named = "perf", matches = "true")
class PerformanceTest {

    static final int WARM_UP = 3;
    static final int RUNS = 10;

    @Autowired MockMvc mockMvc;
    @Autowired PostRepository postRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired CacheManager cacheManager;

    @Test
    void measureEndpoints() throws Exception {
        assumeTrue(postRepository.count() >= 10_000, "Seed the database first (profile 'seed')");

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        String cursorAfter20000 = cursorAfter(20_000);
        String cursorAfter49980 = cursorAfter(49_980);

        List<String> table = new ArrayList<>();
        table.add("| # | Scenario | URL | Avg (ms) | Median (ms) | SQL per request |");
        table.add("|---|---|---|---:|---:|---:|");

        String[][] scenarios = {
                {"Posts, first page (newest first)", "/api/posts?page=0&size=20", null},
                {"Posts, deep page (OFFSET 20,000)", "/api/posts?page=1000&size=20", null},
                {"Posts, last page (OFFSET 49,980)", "/api/posts?page=2499&size=20", null},
                {"Posts, sort by title", "/api/posts?page=0&size=20&sort=title,asc", null},
                {"Filter: status + tag (Specification)", "/api/posts?status=PUBLISHED&tag=java&size=20", null},
                {"Filter: author (Specification)", "/api/posts?author=user7&size=20", null},
                {"Filter: keyword (Specification)", "/api/posts?keyword=docker&size=20", null},
                {"Search: keyword", "/api/posts/search?keyword=docker&size=20", null},
                {"Comments of a busy post, first page", "/api/posts/1/comments?page=0&size=20", null},
                {"Comments of a busy post, deep page", "/api/posts/1/comments?page=90&size=20", null},
                {"Popular posts (native SQL)", "/api/posts/popular?size=10", null},
                {"Feed (keyset), first window", "/api/posts/feed?size=20", null},
                {"Feed (keyset), after 20,000 posts", "/api/posts/feed?size=20&cursor=" + encode(cursorAfter20000),
                        "/api/posts/feed?size=20&cursor=<after 20,000>"},
                {"Feed (keyset), after 49,980 posts", "/api/posts/feed?size=20&cursor=" + encode(cursorAfter49980),
                        "/api/posts/feed?size=20&cursor=<after 49,980>"},
                {"Summaries (DTO projection), first page", "/api/posts/summaries?page=0&size=20", null},
                {"Top rated (JPQL, page of 10)", "/api/posts/top-rated?minRating=4.5&size=10", null},
        };

        for (int i = 0; i < scenarios.length; i++) {
            String[] s = scenarios[i];
            String shownUrl = s[2] != null ? s[2] : s[1];
            table.add(measure(i + 1, s[0], s[1], shownUrl, statistics));
        }

        String result = String.join("\n", table);
        System.out.println("\n" + result + "\n");
        Files.writeString(Path.of("target", "performance-results.md"), result + "\n");
    }

    private String measure(int number, String name, String url, String shownUrl, Statistics statistics)
            throws Exception {
        URI uri = URI.create(url);
        for (int i = 0; i < WARM_UP; i++) {
            mockMvc.perform(get(uri)).andExpect(status().isOk());
        }

        statistics.clear();
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            mockMvc.perform(get(uri)).andExpect(status().isOk());
            times[i] = System.nanoTime() - start;
        }
        long sqlPerRequest = statistics.getPrepareStatementCount() / RUNS;

        Arrays.sort(times);
        double average = Arrays.stream(times).average().orElse(0) / 1_000_000;
        double median = times[RUNS / 2] / 1_000_000.0;

        return "| %d | %s | `%s` | %.1f | %.1f | %d |".formatted(number, name, shownUrl, average, median, sqlPerRequest);
    }

    @Test
    void measureCaching() throws Exception {
        assumeTrue(postRepository.count() >= 10_000, "Seed the database first (profile 'seed')");
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        List<String> table = new ArrayList<>();
        table.add("| Endpoint | Cache | Miss: avg ms (SQL) | Hit: avg ms (SQL) | Faster |");
        table.add("|---|---|---:|---:|---:|");

        String[][] endpoints = {
                {"Post by id", "/api/posts/12345", CacheConfig.POSTS},
                {"User by id", "/api/users/7", CacheConfig.USERS},
                {"All tags", "/api/tags", CacheConfig.TAGS},
                {"Popular posts (native SQL)", "/api/posts/popular?size=10", CacheConfig.POPULAR_POSTS},
                {"Top rated (JPQL)", "/api/posts/top-rated?minRating=4.5&size=10", CacheConfig.TOP_RATED_POSTS},
        };

        for (String[] e : endpoints) {
            URI uri = URI.create(e[1]);
            Cache cache = cacheManager.getCache(e[2]);
            mockMvc.perform(get(uri)).andExpect(status().isOk());

            statistics.clear();
            long[] misses = new long[RUNS];
            for (int i = 0; i < RUNS; i++) {
                cache.clear();
                long start = System.nanoTime();
                mockMvc.perform(get(uri)).andExpect(status().isOk());
                misses[i] = System.nanoTime() - start;
            }
            long sqlMiss = statistics.getPrepareStatementCount() / RUNS;

            statistics.clear();
            long[] hits = new long[RUNS];
            for (int i = 0; i < RUNS; i++) {
                long start = System.nanoTime();
                mockMvc.perform(get(uri)).andExpect(status().isOk());
                hits[i] = System.nanoTime() - start;
            }
            long sqlHit = statistics.getPrepareStatementCount() / RUNS;

            double missMs = Arrays.stream(misses).average().orElse(0) / 1_000_000;
            double hitMs = Arrays.stream(hits).average().orElse(0) / 1_000_000;
            table.add("| %s `%s` | %s | %.1f (%d) | %.2f (%d) | %.0f× |"
                    .formatted(e[0], e[1], e[2], missMs, sqlMiss, hitMs, sqlHit, missMs / hitMs));
        }

        String result = String.join("\n", table);
        System.out.println("\n" + result + "\n");
        Files.writeString(Path.of("target", "caching-results.md"), result + "\n");
    }

    private String cursorAfter(int skip) {
        List<Post> page = postRepository.findAll(PageRequest.of(skip / 20 - 1, 20, PostService.FEED_SORT)).getContent();
        return FeedCursor.of(page.get(page.size() - 1)).asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
