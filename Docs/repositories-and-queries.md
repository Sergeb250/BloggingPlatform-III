# Repositories and Query Logic

All database access goes through **Spring Data JPA repositories** in `repository/`. Services never write SQL themselves. Every query lives in one of these interfaces.

```
Controller ──> Service ──> Repository (interface) ──> Spring Data builds the implementation ──> Hibernate ──> MySQL
```

## 1. Repository overview

Every repository `extends JpaRepository<Entity, Long>`, so it gets `save`, `saveAndFlush`, `findById`, `findAll`, `findAll(Pageable)`, `findAll(Sort)`, `existsById`, `count`, `deleteById` and more for free.

| Repository | Entity / table | Extra interfaces | Custom methods |
|---|---|---|---:|
| `UserRepository` | `User` / `users` | – | 3 |
| `PostRepository` | `Post` / `posts` (+ `post_tags`) | `JpaSpecificationExecutor<Post>` | 12 |
| `TagRepository` | `Tag` / `tags` | – | 3 |
| `CommentRepository` | `Comment` / `comments` | – | 6 |
| `ReviewRepository` | `Review` / `reviews` | – | 7 |
| `AuditLogRepository` | `AuditLog` / `audit_logs` | – | 1 |

**Soft delete.** `User`, `Post`, `Comment`, `Review` and `Tag` are never really deleted (§10). `deleteById` / `delete` run an `UPDATE ... SET deleted_at`, and every query that Hibernate writes for these entities gets `deleted_at IS NULL` added automatically. Only the native queries (§4) carry it by hand.

There are 6 kinds of queries, explained below:

| Kind | How it is written | Example |
|---|---|---|
| A. Derived query | method name only | `findByUsername(String)` |
| B. JPQL | `@Query("SELECT ... FROM Post p ...")` (entity + field names) | `findTopRated` |
| C. Native SQL | `@Query(value = "...", nativeQuery = true)` (table + column names) | `findPopular`, `searchFullText` |
| D. Modifying | `@Modifying @Query("UPDATE ...")` | `updateStatus` |
| E. Specification | `findAll(Specification, Pageable)` built at runtime | post filters |
| F. Keyset scroll | `findBy(spec, q -> q.sortBy(..).limit(n).scroll(position))` | post feed |

---

## 2. A. Derived queries (Spring writes the query from the method name)

| Repository | Method | Generated query (simplified) | Used by |
|---|---|---|---|
| User | `Optional<User> findByUsername(String)` | `WHERE username = ?` | `GET /api/users/by-username/{username}` |
| User | `boolean existsByUsername(String)` | `SELECT 1 ... WHERE username = ? LIMIT 1` | duplicate check on create/update |
| User | `boolean existsByEmail(String)` | `... WHERE email = ?` | duplicate check |
| Post | `List<Post> findByAuthorUsername(String)` | `JOIN users u ... WHERE u.username = ?` | tests (the API uses the Specification filter) |
| Post | `List<Post> findByTagsName(String)` | `JOIN post_tags JOIN tags t ... WHERE t.name = ?` | tests (API: Specification) |
| Post | `List<Post> findByStatus(PostStatus)` | `WHERE status = ?` | tests (API: Specification) |
| Post | `long countByStatus(PostStatus)` | `SELECT COUNT(*) ... WHERE status = ?` | `GET /api/stats` |
| Post | `boolean existsByAuthorIdAndTitle(Long, String)` | `WHERE author_id = ? AND title = ?` | unique (author, title) check |
| Tag | `Optional<Tag> findByName(String)` | `WHERE name = ?` | reuse existing tags |
| Tag | `boolean existsByName(String)` | | duplicate check |
| Comment | `Page<Comment> findByPostId(Long, Pageable)` | `WHERE post_id = ? ORDER BY ... LIMIT ? OFFSET ?` + `COUNT(*)` | paged comment list |
| Comment | `long countByPostId(Long)` | `SELECT COUNT(*) ... WHERE post_id = ?` | `/comments/count` |
| Comment | `Optional<Comment> findByIdAndPostId(Long, Long)` | `WHERE comment_id = ? AND post_id = ?` | edit/delete: the comment must belong to that post |
| Review | `Page<Review> findByPostId(Long, Pageable)` + `@EntityGraph("user")` | `WHERE post_id = ? ORDER BY ... LIMIT ? OFFSET ?` + `COUNT(*)` | paged review list (reviewer JOINed in) |
| Review | `boolean existsByPostIdAndUserId(Long, Long)` | | one review per user per post |
| Review | `Optional<Review> findByIdAndPostId(Long, Long)` | | delete |
| AuditLog | `List<AuditLog> findByAction(String)` | `WHERE action = ?` | tests |

**How a name is read:**
- `findBy` + property path + optional `OrderBy...`
- `AuthorUsername` = the `author` field, then its `username`
- `TagsName` = the `tags` collection, then its `name` (a JOIN)
- A `Pageable` parameter adds `LIMIT/OFFSET`, `ORDER BY` and a count query.

---

## 3. B. JPQL queries (`@Query`)

JPQL uses **entity and field names** (`Post p`, `p.createdAt`), not table names. Hibernate translates it to MySQL SQL.

### `findTopRated(double minRating, Pageable)` → `GET /api/posts/top-rated`
```sql
SELECT new ...PostRatingDto(p.id, p.title, t.averageRating, t.reviewCount)
FROM (SELECT r.postId AS postId, AVG(r.rating) AS averageRating, COUNT(r) AS reviewCount
      FROM Review r
      GROUP BY r.postId
      HAVING AVG(r.rating) >= :minRating) t
JOIN Post p ON p.id = t.postId
ORDER BY t.averageRating DESC, p.id DESC
-- countQuery: SELECT COUNT(p) FROM Post p
--             WHERE p.id IN (SELECT r.postId FROM Review r GROUP BY r.postId HAVING AVG(r.rating) >= :minRating)
```
- **Constructor expression** (`SELECT new ...`): the query fills a record directly. No entities are loaded, only 4 values per row.
- **Subquery in `FROM`** (Hibernate 6+ HQL): the live reviews are averaged per post **first**, from the covering index `reviews (post_id, deleted_at, rating)` alone, and only the qualifying posts are joined for their titles. Joining `posts` first made MySQL read every post and every review row (~1.7 s); this shape takes ~0.17 s.
- **`r.postId`:** a read-only copy of the `post_id` column in `Review`. `r.post.id` would make Hibernate join `posts` (to apply its soft-delete restriction).
- **Paged** (`Pageable`, no client sort: the ranking order is fixed). The `countQuery` is written by hand: Spring Data can't count a `GROUP BY` query by itself.
- **Cached** for 60 s per (minRating, page, size).

### `findSummaries(Pageable)` → `GET /api/posts/summaries`
```sql
SELECT new ...PostSummaryDto(p.id, p.title, a.username, p.status,
       (SELECT COUNT(c) FROM Comment c WHERE c.post = p))
FROM Post p JOIN p.author a
-- countQuery: SELECT COUNT(p) FROM Post p
```
- **DTO projection:** 5 small columns, no `content` text, no tags.
- **Optimized:** the first version used `LEFT JOIN p.comments c ... GROUP BY` and counted the comments of **all** posts before picking 20 (~1.1 s). The subquery only runs for the rows of the current page (< 10 ms).
- **`countQuery`:** written by hand. Spring can't derive a correct count from a query with a subquery in the SELECT.

### `ReviewRepository.findAverageRating()` → `GET /api/stats`
`SELECT AVG(r.rating) FROM Review r`. It returns `null` when there are no reviews, and the service turns that into 0.

---

## 4. C. Native SQL queries (`nativeQuery = true`)

Used when the SQL needs MySQL features JPQL doesn't have, or a hand-tuned shape.

> **Soft delete:** Hibernate passes native SQL to MySQL unchanged, so `@SQLRestriction` does not reach it. Each native query below skips soft-deleted rows itself (`deleted_at IS NULL`). Any new native query on users, posts, comments, reviews or tags needs the same.

### `searchFullText(String keyword, Pageable)` → `GET /api/posts/search`
```sql
SELECT * FROM posts
WHERE MATCH(title, content) AGAINST (:keyword IN NATURAL LANGUAGE MODE)
  AND deleted_at IS NULL
-- + countQuery with the same WHERE
```
- **Index:** uses the FULLTEXT index `ft_posts_title_content` (created by `config/FullTextIndexInitializer`). `EXPLAIN` shows `type = fulltext`.
- **Order:** results come **best match first**, so a client `?sort=` is rejected.
- **Why not `LIKE`:** it replaced `LOWER(title) LIKE '%word%' OR LOWER(content) LIKE '%word%'`. A leading `%` can't use an index, so that was a full table scan (793 ms → 32 ms).
- **Matching:** FULLTEXT matches whole words of 3+ letters.

### `findPopular(Pageable)` → `GET /api/posts/popular`
```sql
SELECT p.post_id AS postId, p.title AS title,
       COALESCE(c.comment_count, 0) AS commentCount, COALESCE(r.review_count, 0) AS reviewCount,
       COALESCE(r.average_rating, 0) AS averageRating, (...) AS score
FROM posts p
LEFT JOIN (SELECT post_id, COUNT(*) comment_count FROM comments
           WHERE deleted_at IS NULL GROUP BY post_id) c ON c.post_id = p.post_id
LEFT JOIN (SELECT post_id, COUNT(*) review_count, AVG(rating) average_rating FROM reviews
           WHERE deleted_at IS NULL GROUP BY post_id) r ON ...
WHERE p.status = 'PUBLISHED' AND p.deleted_at IS NULL
ORDER BY score DESC, p.post_id DESC
-- + LIMIT/OFFSET from the Pageable (Hibernate appends them to native SQL)
-- countQuery: SELECT COUNT(*) FROM posts WHERE status = 'PUBLISHED' AND deleted_at IS NULL
```
- **Result type:** an **interface projection** (`PopularPostView`). Each getter matches a column alias (`getPostId()` ← `AS postId`). The service copies it into the record `PopularPostResponse` before caching.
- **Optimized:** the first version ran 3 correlated subqueries for each of the 40,000 published posts. Grouping once and joining is ~2× faster, and the result is **cached** (60 s per page, evicted on every new comment/review).
- **Covering indexes** `comments (post_id, deleted_at)` and `reviews (post_id, deleted_at, rating)`: the two `GROUP BY` subqueries read only the indexes, not the rows.
- **Paged:** a native query with a `Pageable` needs an explicit `countQuery`. No client sort (fixed ranking).

### `TagRepository.findTagUsage(Pageable)` → `GET /api/tags/usage`
```sql
SELECT t.tag_id AS tagId, t.name AS name, COUNT(p.post_id) AS postCount
FROM tags t
LEFT JOIN post_tags pt ON pt.tag_id = t.tag_id
LEFT JOIN posts p ON p.post_id = pt.post_id AND p.deleted_at IS NULL
WHERE t.deleted_at IS NULL
GROUP BY t.tag_id, t.name
```
Tags with 0 posts are included because of the LEFT JOINs. Only live posts are counted (`COUNT(p.post_id)` ignores the NULLs of a deleted post), and deleted tags are left out. Paged (most used first, no client sort); `countQuery`: `SELECT COUNT(*) FROM tags WHERE deleted_at IS NULL`.

---

## 5. D. `@Modifying` update

### `updateStatus(id, status, now)` → `PATCH /api/posts/{id}/publish`
```java
@Modifying(clearAutomatically = true)
@Query("UPDATE Post p SET p.status = :status, p.updatedAt = :now, p.version = p.version + 1 WHERE p.id = :id")
int updateStatus(...);
```
- **One SQL `UPDATE`:** the post is not loaded first.
- **Bulk updates skip Hibernate's entity logic** (`@UpdateTimestamp`, `@Version`), so the query sets `updatedAt` and increments `version` itself. Optimistic locking keeps working.
- **`clearAutomatically = true`:** clears the persistence context, so the next read sees the new values and not an old cached copy.
- **Return value:** the number of changed rows. `0` → 404.

### Soft-delete updates (the cascade of a delete)
A soft delete is an `UPDATE`, so MySQL's `ON DELETE CASCADE` never fires. `PostService.delete` and `UserService.delete` soft-delete the dependent rows with these bulk updates, in the same transaction:

| Repository | Method | JPQL |
|---|---|---|
| Post | `softDeleteByAuthor(authorId)` | `UPDATE Post p SET p.deletedAt = CURRENT_TIMESTAMP WHERE p.author.id = :authorId AND p.deletedAt IS NULL` |
| Comment / Review | `softDeleteByPost(postId)` | `... WHERE c.post.id = :postId AND c.deletedAt IS NULL` |
| Comment / Review | `softDeleteByUser(userId)` | `... WHERE c.user.id = :userId AND c.deletedAt IS NULL` |
| Comment / Review | `softDeleteOnPostsOf(authorId)` | `... WHERE c.post.author.id = :authorId AND c.deletedAt IS NULL` |

- **`UPDATE`, never JPQL `DELETE`:** `@SQLDelete` only replaces `entityManager.remove()`. A bulk `DELETE FROM Comment ...` would remove the rows for real.
- **Children before the parent:** Hibernate adds `deleted_at IS NULL` to the joins inside these updates too (`update comments c join posts p on ... and (p.deleted_at IS NULL)`), so they only find the parent while it is still live.
- **Two statements instead of one `OR`:** `user_id = ? OR post_id IN (...)` could not use an index and read every comment and review (~1.8 s per user delete on the seeded data). Split, each one follows an index (~25 ms).

---

## 6. E. Specifications: the filters of `GET /api/posts`

**Problem:** 6 optional filters (`status, author, tag, keyword, from, to`). One derived method per combination would be 64 methods.

**Solution:** `PostRepository extends JpaSpecificationExecutor<Post>`, plus one small condition per filter in `specification/PostSpecifications`:

| Method | Condition |
|---|---|
| `hasStatus(status)` | `status = ?` |
| `hasAuthor(username)` | `JOIN users ... username = ?` |
| `hasTag(name)` | `JOIN post_tags JOIN tags ... name = ?` |
| `matchesKeyword(word)` | `MATCH(title, content) AGAINST (?) > 0` (FULLTEXT index) |
| `createdFrom(date)` / `createdTo(date)` | `created_at >= ?` / `created_at < next day` |

`PostService.search` combines only the filters the client sent:
```java
Specification<Post> spec = Specification.unrestricted();                  // no WHERE yet
if (filter.status() != null)          spec = spec.and(hasStatus(filter.status()));
if (StringUtils.hasText(filter.tag())) spec = spec.and(hasTag(filter.tag()));
...
Page<Post> page = postRepository.findAll(spec, pageable);                  // page query + COUNT(*)
```
- **Full-text inside a Specification:** JPA doesn't know `MATCH ... AGAINST`. `config/MySqlFunctionContributor` registers a Hibernate function `match_against(title, content, keyword)` that renders that SQL. It is registered through `META-INF/services/org.hibernate.boot.model.FunctionContributor`.

---

## 7. Pagination, sorting and keyset scrolling

### Page-based (`Pageable` → `Page<T>` → `PageResponse<T>`)
- **Controllers:** `@ParameterObject @PageableDefault(size = 10, sort = "createdAt", direction = DESC) Pageable`.
- **Max size:** 100, checked by `@ValidPageable(maxSize = 100)` on the controller parameter: `?size=500` is a **400** (strict), not silently cut down. Spring's own limit (`max-page-size`) is set to 1000 so the validator sees the size the client really sent.
- **Sort whitelist:** `@ValidPageable(sortFields = {...})` on every `Pageable` controller parameter, a Bean Validation constraint that Spring runs before the method (`validation/PageableValidator`). Unknown, slow or sensitive fields (`content`, `author.passwordHash`) → 400. `sortFields = {}` (search) means no sorting at all.
- **SQL:** a `Page` costs **2 queries**, the rows (`LIMIT ? OFFSET ?`) and a `COUNT(*)`.

**Every list endpoint is paged** (default page, size and order when the client sends nothing):

| Paged endpoint | Repository method |
|---|---|
| `GET /api/posts` | `findAll(Specification, Pageable)` |
| `GET /api/posts/search` | `searchFullText(keyword, Pageable)` |
| `GET /api/posts/summaries` | `findSummaries(Pageable)` |
| `GET /api/posts/top-rated` | `findTopRated(minRating, Pageable)` (JPQL + `countQuery`) |
| `GET /api/posts/popular` | `findPopular(Pageable)` (native + `countQuery`) |
| `GET /api/posts/{id}/comments` | `CommentRepository.findByPostId(postId, Pageable)` |
| `GET /api/posts/{id}/reviews` | `ReviewRepository.findByPostId(postId, Pageable)` |
| `GET /api/users` | `UserRepository.findAll(Pageable)` |
| `GET /api/tags` | `TagRepository.findAll(Pageable)` |
| `GET /api/tags/usage` | `findTagUsage(Pageable)` (native + `countQuery`) |
| `GET /api/audit-logs` | `findAll(Pageable)` |

Rankings (`top-rated`, `popular`, `tags/usage`) and search have a fixed order, so `@ValidPageable(sortFields = {})` refuses any `?sort=`.

### Keyset (cursor) scrolling: `GET /api/posts/feed`
```java
Window<Post> window = postRepository.findBy(Specification.unrestricted(), q -> q
        .sortBy(Sort.by(DESC, "createdAt").and(Sort.by(DESC, "id")))
        .limit(size)
        .scroll(ScrollPosition.forward(Map.of("createdAt", lastCreatedAt, "id", lastId))));
```
Spring Data writes:
```sql
WHERE created_at < ? OR (created_at = ? AND post_id < ?)
ORDER BY created_at DESC, post_id DESC
LIMIT size + 1
```
(The `+1` row tells it whether `hasNext` is true.)

- **The cursor** is `"<createdAt>,<id>"` of the last post sent.
- **Cost:** O(log n + k) at any depth (16 ms at position 49,980). OFFSET is O(offset + k): 316 ms there.

---

## 8. Loading related data (N+1)

| Where | Technique | SQL per page of 20 |
|---|---|---:|
| post list / search | `hibernate.default_batch_fetch_size=50`: authors and tag lists of all rows loaded with `WHERE id IN (...)` | 4 (was 39–41) |
| comment list | `@EntityGraph(attributePaths = "user")` on `findByPostId` (JOIN) | 3 (was 23) |

`@EntityGraph("author")` is **not** used on the post list. With OFFSET paging MySQL runs the JOIN for every skipped row, which made deep pages ~40% slower. Collections (tags) are never JOIN-fetched together with paging, because the duplicated rows would break `LIMIT`. `QueryOptimizationTest` fails if the post list needs more than 4 SQL statements.

## 9. Indexes behind the queries

| Index | Serves |
|---|---|
| `posts(created_at)` | default post order, keyset feed |
| `posts(status, created_at)` | status filter + newest first |
| `posts(title)` | `sort=title` |
| `posts(author_id, title, not_deleted)` (unique among live posts, ERD) | author filter, duplicate-title check |
| FULLTEXT `posts(title, content)` | search + keyword filter |
| `comments(post_id, created_at)` | comment pages without filesort |
| `comments(post_id, deleted_at)` | live comments per post (popular), index only |
| `reviews(post_id, deleted_at, rating)` | live reviews and their average per post (popular, top rated), index only |
| `reviews(post_id, user_id, not_deleted)` (unique among live reviews, ERD) | one review per user per post, review lookups |
| `users(username, not_deleted)`, `users(email, not_deleted)`, `tags(name, not_deleted)` (unique among live rows) | lookups and duplicate checks |

Before/after `EXPLAIN` plans and timings: [`performance-report.md`](performance-report.md).

## 10. Soft delete

| Piece | Where |
|---|---|
| `deleted_at` column (NULL = live) | `deletedAt` field in `User`, `Post`, `Comment`, `Review`, `Tag` |
| delete → `UPDATE ... SET deleted_at = NOW(6)` | `@SQLDelete` on each entity (`AND version = ?` for `Post` and `Comment`). `verify = Expectation.RowCount.class`: Hibernate does not check the row count of custom SQL by default, so without it a stale delete would silently change 0 rows |
| reads skip deleted rows | `@SQLRestriction("deleted_at IS NULL")` on each entity: Hibernate adds it to `findById`, derived queries, JPQL, Specifications, joins and collections |
| native queries | `deleted_at IS NULL` by hand (§4) |
| cascade | bulk updates (§5), called by `PostService.delete` and `UserService.delete` |
| unique keys | `config/SoftDeleteSchemaInitializer`: MySQL has no partial unique index, so each unique key ends with the generated column `not_deleted` (`IF(deleted_at IS NULL, 1, NULL)`). A unique index ignores NULLs, so deleted rows never clash and live rows must still be unique |

**Why not Hibernate's `@SoftDelete`:** it rejects `@ManyToOne(fetch = LAZY)` pointing at a soft-deletable entity ("cannot be mapped as LAZY as its associated entity is defined with @SoftDelete"). All our relations are LAZY on purpose (§8, N+1), so `@SQLDelete` + `@SQLRestriction` are used instead.

Tests: `SoftDeleteTest` checks with plain SQL that the rows are still there, and that no endpoint, search, ranking or count returns them.
