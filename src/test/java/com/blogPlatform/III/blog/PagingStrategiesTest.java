package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.repository.PostRepository;
import com.blogPlatform.III.blog.service.PostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// needs the seeded blog_db: mvnw test -Dtest=PagingStrategiesTest -Dperf=true
@SpringBootTest
@ActiveProfiles("perf")
@EnabledIfSystemProperty(named = "perf", matches = "true")
class PagingStrategiesTest {

    static final int PAGE_SIZE = 20;
    static final int WARM_UP = 2;
    static final int RUNS = 5;

    @Autowired PostRepository postRepository;

    @Test
    void comparePagingStrategies() throws Exception {
        long total = postRepository.count();
        assumeTrue(total >= 10_000, "Seed the database first (profile 'seed')");

        List<String> table = new ArrayList<>();
        table.add("| Start position | 1. In memory: load all + sort + subList (ms) | 2. DB OFFSET + index (ms) | 3. DB keyset + index (ms) |");
        table.add("|---:|---:|---:|---:|");

        for (int start : new int[]{0, 20_000, 49_980}) {
            Map<String, Object> keyset = start == 0 ? null : keysOfPostAt(start - 1);

            Supplier<List<Long>> inMemory = () -> inMemoryPage(start);
            Supplier<List<Long>> offset = () -> offsetPage(start);
            Supplier<List<Long>> keysetPage = () -> keysetPage(keyset);

            assertEquals(inMemory.get(), offset.get());
            assertEquals(offset.get(), keysetPage.get());

            table.add("| %,d | %.1f | %.1f | %.1f |".formatted(start,
                    medianMs(inMemory), medianMs(offset), medianMs(keysetPage)));
        }

        String result = String.join("\n", table);
        System.out.println("\n" + result + "\n");
        Files.writeString(Path.of("target", "paging-strategies.md"), result + "\n");
    }

    private List<Long> inMemoryPage(int start) {
        List<Post> all = new ArrayList<>(postRepository.findAll());
        all.sort(Comparator.comparing(Post::getCreatedAt).thenComparing(Post::getId).reversed());
        return ids(all.subList(start, start + PAGE_SIZE));
    }

    private List<Long> offsetPage(int start) {
        return ids(postRepository.findBy(Specification.unrestricted(), query -> query
                .sortBy(PostService.FEED_SORT)
                .limit(PAGE_SIZE)
                .scroll(start == 0 ? ScrollPosition.offset() : ScrollPosition.offset(start - 1)))
                .getContent());
    }

    private List<Long> keysetPage(Map<String, Object> lastSeen) {
        ScrollPosition position = lastSeen == null ? ScrollPosition.keyset() : ScrollPosition.forward(lastSeen);
        return ids(postRepository.findBy(Specification.unrestricted(), query -> query
                .sortBy(PostService.FEED_SORT)
                .limit(PAGE_SIZE)
                .scroll(position))
                .getContent());
    }

    private Map<String, Object> keysOfPostAt(int index) {
        Post post = postRepository.findAll(PageRequest.of(index, 1, PostService.FEED_SORT)).getContent().get(0);
        return Map.of("createdAt", post.getCreatedAt(), "id", post.getId());
    }

    private double medianMs(Supplier<List<Long>> strategy) {
        for (int i = 0; i < WARM_UP; i++) {
            strategy.get();
        }
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long begin = System.nanoTime();
            strategy.get();
            times[i] = System.nanoTime() - begin;
        }
        Arrays.sort(times);
        return times[RUNS / 2] / 1_000_000.0;
    }

    private static List<Long> ids(List<Post> posts) {
        return posts.stream().map(Post::getId).toList();
    }
}
