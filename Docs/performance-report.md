# Performance Report

This report tracks how fast the main read endpoints are, **before** and **after** optimization.

| Stage | Phase | Status |
|---|---|---|
| Baseline ("before") | 3: Pagination and sorting | ✅ measured |
| Query optimization ("after" #1) | 5: indexes, N+1 fixes, full-text search, keyset paging, query rewrites | ✅ measured |
| Caching ("after" #2) | 6: Spring Cache + Caffeine | ✅ measured |

---

## 1. Test setup

| Item | Value |
|---|---|
| Machine | local Windows 11 PC |
| Java / Spring Boot / Hibernate | JDK 27 / Spring Boot 4.1.2 / Hibernate 7.4 |
| Database | MySQL 8.0.46 (local, default settings) |
| Measured through | full Spring MVC stack with MockMvc: controller → service → repository → MySQL → JSON (no network) |
| Runs per scenario | 3 warm-up calls (ignored) + 10 timed calls → average and median |
| SQL count | Hibernate statistics (`generate_statistics=true`): SQL statements sent for **one** request |

### Dataset
Created by `seed/DataSeeder` (profile `seed`). It uses a fixed random seed, so the data is identical on every run.

| Table | Rows |
|---|---:|
| users | 1,000 (200 authors) |
| posts | 50,000 (40,018 published) |
| tags | 30 |
| post_tags | 99,999 |
| comments | 200,000 (posts 1–10 have ~2,000 each) |
| reviews | 100,214 |

### How to reproduce
```bash
# 1. once, on an empty blog_db
java -jar target/blog-0.0.1-SNAPSHOT.jar --spring.profiles.active=seed
# 2. measure (both are skipped in a normal "mvnw test")
mvnw test -Dtest=PerformanceTest -Dperf=true        # -> target/performance-results.md + target/caching-results.md
mvnw test -Dtest=PagingStrategiesTest -Dperf=true   # -> target/paging-strategies.md
```

---

## 2. Before vs after (same 11 scenarios, same data, same test)

Page size 20. Times are averages in ms. "Before" = Phase 3 baseline, "After" = Phase 5.

| # | Scenario | Before (ms) | After (ms) | Change | SQL / request before → after |
|---|---|---:|---:|---:|---:|
| 1 | Posts, first page (newest first) | 167.4 | **58.7** | **2.9× faster** | 39 → 4 |
| 2 | Posts, deep page (OFFSET 20,000) | 237.6 | 243.7 | ≈ same (+3%) | 41 → 4 |
| 3 | Posts, last page (OFFSET 49,980) | 252.9 | 315.9 | **25% slower** (see §5) | 41 → 4 |
| 4 | Posts, sort by title | 132.3 | **38.9** | **3.4× faster** | 41 → 4 |
| 5 | Filter: status + tag | 60.9 | 57.1 | ≈ same (−6%) | 41 → 4 |
| 6 | Filter: author | 28.9 | **17.9** | 1.6× faster | 23 → 4 |
| 7 | Filter: keyword (Specification) | 726.8 | **92.8** | **7.8× faster** | 41 → 4 |
| 8 | Search: keyword (`/search`) | 792.7 | **31.8** | **25× faster** | 41 → 4 |
| 9 | Comments of a busy post, first page | 36.1 | **17.0** | 2.1× faster | 23 → 3 |
| 10 | Comments of a busy post, deep page | 31.1 | 29.0 | ≈ same (−7%) | 23 → 3 |
| 11 | Popular posts (native SQL) | 1249.6 | **706.6** | 1.8× faster | 1 → 1 |

### New endpoints in Phase 5

| # | Scenario | URL | Avg (ms) | SQL |
|---|---|---|---:|---:|
| 12 | Feed (keyset), first window | `/api/posts/feed?size=20` | 13.0 | 3 |
| 13 | Feed (keyset), after 20,000 posts | `/api/posts/feed?size=20&cursor=…` | **16.0** | 3 |
| 14 | Feed (keyset), after 49,980 posts | `/api/posts/feed?size=20&cursor=…` | **15.8** | 3 |
| 15 | Summaries (DTO projection), paged | `/api/posts/summaries?page=0&size=20` | 30.3 | 2 |
| 16 | Top rated (JPQL, limit 10) | `/api/posts/top-rated?minRating=4.5&limit=10` | 803.8 | 1 |

Compare #13/#14 with #2/#3: the same position in the list costs **16 ms with keyset vs 244–316 ms with OFFSET**.

---

## 3. What changed (fix ↔ Phase 3 finding)

| Phase 3 finding | Fix | Result |
|---|---|---|
| 1. N+1: ~40 SQL per page | `hibernate.default_batch_fetch_size=50` (authors + tags of a page in 1 query each); `@EntityGraph("user")` for comments | 39–41 → **3–4** SQL |
| 2. no index for `created_at` sort | `idx_posts_created_at`, `idx_posts_status_created_at` | #1: full scan + filesort → reads 20 index entries |
| 3. deep OFFSET | new keyset feed `/api/posts/feed` (Spring Data `ScrollPosition` / `Window`) | 16 ms at any depth |
| 4. `LIKE '%word%'` search | FULLTEXT index + `MATCH … AGAINST` (native query and Specification) | #8: 793 → 32 ms, #7: 727 → 93 ms |
| 5. sort by title | `idx_posts_title` | #4: 132 → 39 ms |
| 7. comments filesort | `idx_comments_post_created (post_id, created_at)` | no filesort, #9: 36 → 17 ms |
| 8. popular posts | correlated subqueries → aggregate once + JOIN | #11: 1250 → 707 ms (caching in Phase 6) |
| (unpaged lists) | `/summaries` paged with a per-row count subquery; `/top-rated` gets `?limit` | summaries query ~1.1 s → <10 ms in MySQL |

---

## 4. Query plans: `EXPLAIN` before → after

| Query | Before | After |
|---|---|---|
| posts `ORDER BY created_at DESC LIMIT 20` | `ALL`, 48,816 rows, Using filesort | `index` **idx_posts_created_at**, **20 rows**, Backward index scan |
| posts keyset `WHERE (created_at, id) < (…) ORDER BY … LIMIT 21` | – | `range` **idx_posts_created_at**, Backward index scan (stops after 21 rows) |
| posts `ORDER BY title LIMIT 20` | `ALL`, 48,816 rows, Using filesort | `index` **idx_posts_title**, **20 rows** |
| search `LIKE '%docker%'` | `ALL`, 48,816 rows, Using where; Using filesort | – (replaced) |
| search `MATCH(title, content) AGAINST ('docker')` | – | `fulltext` **ft_posts_title_content**, Ft_hints: sorted, limit = 20 |
| keyword filter `match_against(...) > 0` (Specification) | – | `fulltext` **ft_posts_title_content**, Ft_hints: rank > 0; Using filesort (by date) |
| comments `WHERE post_id = 1 ORDER BY created_at LIMIT 20 OFFSET 1800` | `ref` FK(post_id), 1,979 rows, **Using filesort** | `ref` **idx_comments_post_created**, no filesort |
| posts ⨝ post_tags ⨝ tags (status + tag) | tags const → post_tags ref (3,350) → posts; Using temporary; Using filesort | unchanged: MySQL still starts from the tag (see §6) |

### Rewritten "complex" queries (timed directly in MySQL, 3 runs)

| Query | Before | After |
|---|---:|---:|
| Popular posts: 3 correlated subqueries per post → `GROUP BY post_id` once + JOIN | 0.97–1.07 s | 0.49–0.66 s |
| Summaries page: `LEFT JOIN comments GROUP BY` all posts → count subquery for the 20 page rows | 1.04–1.66 s | < 0.01 s |

---

## 5. Honest note: deep OFFSET pages (scenarios 2 and 3)

The indexes did **not** make deep OFFSET pages faster, and the last page got 25% slower. `EXPLAIN ANALYZE` of `LIMIT 20 OFFSET 20000`, actual MySQL time:

| Variant | MySQL time |
|---|---:|
| author `LEFT JOIN` (`@EntityGraph`) + index | 255 ms |
| no join + index (**what we ship**) | 213 ms |
| no join, no index (the Phase 3 query) | 211 ms |
| deferred join: `SELECT post_id … LIMIT 20000, 20` first, then 20 full rows | **11 ms** |

- **OFFSET always has to step over the skipped rows.** With the index, MySQL walks the index backwards and looks up each skipped row (random reads). Without it, it reads the table once in order and sorts in memory. At OFFSET 49,980 that makes the index path a bit slower.
- **We first used `@EntityGraph("author")` on the post list.** That made page 1 faster (44 ms) but deep pages ~40% slower (349 ms), because the JOIN also ran for all 20,020 skipped rows. So the post list uses batch fetching instead (4 SQL statements instead of 3, but no JOIN on skipped rows).
- **The real fix is keyset paging (#13/#14: 16 ms).** A deferred join would also work (11 ms in MySQL), but it needs a two-step query. It's not implemented, to keep the code simple.

---

## 6. DSA comparison: three ways to get one page (`PagingStrategiesTest`)

"20 posts, newest first, starting at position X" out of n = 50,000. All three strategies were checked to return **the same 20 posts**. Median of 5 runs, repository level (no HTTP, no JSON).

| Start position | 1. In memory: load all + `sort` + `subList` | 2. DB `ORDER BY` + index + `OFFSET` | 3. DB keyset + index |
|---:|---:|---:|---:|
| 0 | 933.2 ms | 6.5 ms | 6.2 ms |
| 20,000 | 951.5 ms | 217.2 ms | 7.7 ms |
| 49,980 | 849.3 ms | 307.9 ms | 8.0 ms |

| Strategy | Time | Memory | Seen in the numbers |
|---|---|---|---|
| 1. In memory (`findAll()` + TimSort + `subList`) | O(n) to transfer every row + **O(n log n)** sort, at **every** request | O(n) | ~0.9 s everywhere: the cost doesn't depend on the page, it's always "all rows" |
| 2. OFFSET + B-tree index | O(log n) to find the start of the index + **O(offset)** rows skipped + O(k) | O(k) | fast at the start, grows linearly with the offset (6 → 217 → 308 ms) |
| 3. Keyset + B-tree index | **O(log n + k)**: jump straight to the last seen key, read k rows | O(k) | flat (~6–8 ms) at any position |

(k = page size = 20.)

**Takeaways:**
- Let the database sort with an index, not Java.
- For deep or "infinite" scrolling, use keyset paging.
- Keep OFFSET where users need page numbers and stay near the start.

---

## 7. Measuring with AOP (`aop/PerformanceAspect`) and the `dev` profile

`@Around` advice times every service and repository method. Calls over 300 ms are logged as WARN. With `--spring.profiles.active=dev`, every call is logged at DEBUG. Sample from the running app (first requests after start, with SQL logging on):

```
DEBUG  JpaSpecificationExecutor.findAll(..) took 258 ms
WARN   SLOW PostService.search(..) took 319 ms
DEBUG  JpaSpecificationExecutor.findBy(..) took 36 ms
DEBUG  PostService.feed(..) took 61 ms
WARN   SLOW PostRepository.findPopular(..) took 843 ms
WARN   SLOW PostService.getPopular(..) took 844 ms
```
The aspect shows how much of a service call is spent in the repository (the database), and it points straight at the next targets: `findPopular` and `findTopRated`.

With the `dev` profile, Hibernate also logs how many SQL statements each request ran. This is how N+1 problems get spotted. For `GET /api/posts?page=0&size=20` after Phase 5:
```
DEBUG org.hibernate.session.metrics : HHH000401: Logging session metrics:
	1674200 ns preparing 4 JDBC statements
	24455300 ns executing 4 JDBC statements
```
(Before Phase 5 the same request executed 39.)

---

## 8. Phase 6: caching (Spring Cache + Caffeine)

After Phase 5, two endpoints were still slow, and SQL can't make them much faster: popular posts (~707 ms) has to aggregate 200k comments + 100k reviews, and top rated (~804 ms) has to average all 100k reviews. Their answer is the same for every user, so they're cached. Small but very frequently read objects are cached too.

### Cache configuration (`config/CacheConfig`)

| Cache | Holds | Max entries | TTL | Cleared by |
|---|---|---:|---|---|
| `posts` | post by id (`PostResponse`) | 10,000 | 10 min | `@CachePut` on update/publish, evict on delete, user update/delete, tag delete |
| `users` | user by id (`UserResponse`) | 5,000 | 30 min | `@CachePut` on update, evict on delete |
| `tags` | all tags | 10 | 1 h | tag create/delete, post create/update (may create tags) |
| `popularPosts` | popular list, key = limit | 20 | 60 s | any comment/review add/delete, post create/update/publish/delete |
| `topRatedPosts` | top-rated list | 50 | 60 s | any review add/delete, post update/delete |

All caches use `recordStats()`, and the numbers are at `GET /api/cache-stats`. The cache advice runs **outside** the transaction (`@EnableCaching(order = HIGHEST_PRECEDENCE)`): a hit needs no transaction, and puts/evictions happen after the commit.

### Miss vs hit (`PerformanceTest.measureCaching`, 10 runs each)
"Miss" = the cache is emptied before every call. "Hit" = the entry is in the cache.

| Endpoint | Miss: avg ms (SQL) | Hit: avg ms (SQL) | Faster |
|---|---:|---:|---:|
| Popular posts `/api/posts/popular?limit=10` | 972.6 (1) | **3.25 (0)** | **299×** |
| Top rated `/api/posts/top-rated?minRating=4.5&limit=10` | 1097.7 (1) | **2.59 (0)** | **423×** |
| Post by id `/api/posts/12345` | 18.3 (3) | 4.01 (0) | 5× |
| User by id `/api/users/7` | 10.8 (1) | 3.30 (0) | 3× |
| All tags `/api/tags` | 19.4 (1) | 3.45 (0) | 6× |

A hit runs **0 SQL statements** (and, as `CacheTest` checks, 0 transactions). The remaining ~3 ms is MockMvc + JSON.

### Live check on the running app
```
popular call 1: 1.239941s     <- miss: MySQL
popular call 2: 0.006686s     <- hit
popular call 3: 0.004894s     <- hit
add a comment to the #1 popular post -> next call shows commentCount 2077 -> 2078 (evicted, not stale)
GET /api/cache-stats -> {"name":"posts","size":1,"hits":2,"misses":1,"hitRate":0.667, ...}
```

### Note on run-to-run variance
In the same run, the **uncached** scenarios (#1–#10, #12–#15) measured 5–35% slower than in the Phase 5 run, although their code didn't change. That's noise on this PC (other programs running, MySQL buffer state). Compare numbers **within one run**. The cached scenarios changed far beyond that noise: #11 popular **707 → 2.1 ms**, #16 top rated **804 → 2.4 ms** (cache hits after warm-up).

## 9. Summary: baseline → Phase 5 → Phase 6

| Scenario | Phase 3 baseline | Phase 5 (queries) | Phase 6 (cache hit) |
|---|---:|---:|---:|
| Posts, first page | 167 ms, 39 SQL | 59 ms, 4 SQL | (not cached) |
| Keyword search | 793 ms | 32 ms | (not cached) |
| Deep page via keyset feed | 238 ms (OFFSET) | 16 ms | (not cached) |
| Popular posts | 1250 ms | 707 ms | **2–3 ms, 0 SQL** |
| Top rated (limit 10) | – | 804 ms | **2–3 ms, 0 SQL** |
| Post by id | – | 18 ms | **4 ms, 0 SQL** |

## 10. Later: soft delete + every list paged

Two later features changed the ranking queries:
- **Soft delete:** every query must skip rows with `deleted_at` set. Live comments and reviews could no longer be counted from an index alone (`deleted_at` was in none), and the extra `posts.deleted_at IS NULL` condition made MySQL drive top rated from `posts` instead of from the reviews index.
- **Paging every list:** top rated and popular became pages (`?page=&size=` instead of `?limit=`). A `Page` needs the total, so each one got a **count query**.

Measured on the same seeded `blog_db`. "MySQL" = the SQL alone, best of 3 runs (JDBC). "HTTP" = first, uncached call through the running app (curl); cache hits stay **4–8 ms**.

| Query (MySQL) | Before soft delete | Soft delete, no fix | Now |
|---|---:|---:|---:|
| top rated, page of 10 | 401 ms | 1,729 ms | **170 ms** |
| top rated, count (new) | – | 1,395 ms | **317 ms** |
| popular, page of 10 | 803 ms | 1,466 ms | 1,424 ms |
| popular, count (new) | – | – | 253 ms |
| tag usage | 254 ms | 875 ms | 860 ms |
| live comments per post (inside popular) | index only | full rows | **206 ms, index only** |
| live reviews per post (inside popular) | index only | full rows | **173 ms, index only** |

| Endpoint (HTTP, cache miss) | Now |
|---|---:|
| `/api/posts/top-rated?minRating=4.5` | 0.5–0.75 s |
| `/api/posts/popular` | ~1.6 s |
| `/api/tags/usage` (not cached) | ~0.8 s |
| `/api/users`, `/api/tags`, `/api/posts/{id}/reviews` | 20–150 ms |

**Fixes:**
1. **Covering indexes** `comments (post_id, deleted_at)` and `reviews (post_id, deleted_at, rating)`: "live comments/reviews per post" is read from the index alone again (`EXPLAIN`: `Using index`).
2. **Top rated, aggregate first:** the live reviews are averaged per post in a subquery in `FROM` (index only), then only the ~3,800 qualifying posts are joined for their titles. The JPQL stays a DTO projection; `Review` got a read-only `postId` field so the subquery needs no join to `posts`. 1,729 → 170 ms, faster than before soft delete.
3. **Count queries written by hand** (`countQuery`), since Spring Data can't count a `GROUP BY` query: top rated counts from `reviews` only (1,395 → 317 ms).

**Still slower than before soft delete:** popular (+0.6 s) and tag usage (+0.6 s). Both have to check `deleted_at` on every published post or every post–tag link, and no query shape tried (`NOT IN`, `NOT EXISTS`, join order) avoided that. Popular is cached for 60 s; tag usage is not cached.
