# Transaction Handling Strategy

Goal: **no half-saved data and no lost updates**, even with many requests at the same time.

## 1. Where transactions are defined

Transactions are declared on the **service layer**: a service method is one business action, the unit that must succeed or fail as a whole. Controllers and repositories don't declare their own.

```java
@Service
@Transactional(readOnly = true)                 // default for every method: read
public class PostService {

    public PostResponse getById(Long id) { ... }                       // read-only transaction

    @Transactional(rollbackFor = ContentRejectedException.class)       // write: override the default
    public PostResponse create(PostRequest request) throws ContentRejectedException { ... }
}
```

| Setting | Why |
|---|---|
| `readOnly = true` at class level | reads are the common case. Hibernate skips dirty-checking and flushing, and the driver knows nothing will be written |
| `@Transactional` on each create / update / delete | a write must commit or roll back as one unit |
| `spring.jpa.open-in-view=false` | the DB session closes when the service returns. Lazy data must be loaded inside the transaction, so services return finished DTOs |

## 2. Propagation

| Propagation | Meaning | Used in | Why |
|---|---|---|---|
| `REQUIRED` (default) | join the running transaction, or start one | all service methods | e.g. `PostService.create` and the tag helper it calls share one transaction |
| `REQUIRES_NEW` | **pause** the caller's transaction, run in a new one, **commit it right away** | `AuditService.record()` | the audit row must survive when the main action fails and rolls back |
| `MANDATORY` | there must already be a transaction, otherwise `IllegalTransactionStateException` | `TagService.findOrCreate()` | new tags must be saved in the **same** transaction as the post, so a failed post leaves no orphan tags |

**Self-invocation.** Spring applies `@Transactional` through a proxy around the bean. A call from a class to its *own* method skips the proxy and its annotations, so `record()` lives in a separate bean (`AuditService`).

**Example, creating a post with new tags:**
```
PostService.create                         [TX 1 starts]
 ├─ AuditService.record("CREATE_POST")     [TX 1 paused → TX 2 starts → INSERT audit → TX 2 COMMIT → TX 1 resumes]
 ├─ load author, duplicate check           [TX 1]
 ├─ TagService.findOrCreate(["java","new"]) [joins TX 1 (MANDATORY) → INSERT tag "new"]
 ├─ INSERT post + post_tags                [TX 1]
 └─ ContentModerator.check → throws        [TX 1 ROLLBACK: post, post_tags and tag "new" are undone]
Result: no post, no orphan tag, but the audit row (TX 2) is still there.
```

**Example, deleting a user (soft delete):** a delete is an `UPDATE ... SET deleted_at`, so MySQL's `ON DELETE CASCADE` never fires. The cascade is done by the service, inside **one** transaction:
```
UserService.delete                                  [TX starts]
 ├─ find the user (404 if missing or already deleted)
 ├─ UPDATE comments the user wrote                  [deleted_at = now]
 ├─ UPDATE comments on the user's posts
 ├─ UPDATE reviews the user wrote
 ├─ UPDATE reviews of the user's posts
 ├─ UPDATE the user's posts
 └─ UPDATE the user (@SQLDelete)                    [COMMIT: all six, or none]
```
If any step fails, everything rolls back and nothing is half deleted. The children go first: Hibernate adds `deleted_at IS NULL` to the joins inside these updates, so they only find the user's posts while those are still live. `PostService.delete` works the same way (comments, reviews, then the post).

## 3. Rollback rules

By default Spring rolls back only for **unchecked** exceptions (`RuntimeException`, `Error`). For a **checked** exception it **commits**.

| Exception | Type | Rolls back? |
|---|---|---|
| `ResourceNotFoundException`, `DuplicateResourceException` | unchecked | yes (default) |
| `DataIntegrityViolationException`, `OptimisticLockingFailureException` | unchecked (Spring) | yes (default) |
| `ContentRejectedException` (banned words) | **checked** | **only because of** `rollbackFor = ContentRejectedException.class` on `PostService.create` / `update` |

**Verified:** with `rollbackFor` removed, `TransactionTest.createPost_checkedException_isRolledBack_thanksToRollbackFor` fails. The rejected post was committed ("expected 0 but was 1").

## 4. Isolation

| Level | A second SELECT in the same transaction sees... |
|---|---|
| `READ_COMMITTED` | rows other transactions committed in the meantime, so several counts can disagree |
| `REPEATABLE_READ` | the same snapshot as the first SELECT, so the counts always fit together |

- `StatsService.getStats()` runs 7 counts with `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`, so `publishedPosts + draftPosts == posts` always holds.
- **MySQL (InnoDB)** already defaults to REPEATABLE_READ. **PostgreSQL** defaults to READ_COMMITTED. Declaring it keeps the rule if the database changes.
- **Proven by tests:** inside one transaction we count posts, let *another* transaction insert and commit a post, then count again. REPEATABLE_READ gives the same number, READ_COMMITTED gives +1.

## 5. Optimistic locking (lost updates)

`Post` and `Comment` have a `@Version` column. Hibernate adds `WHERE version = ?` to every UPDATE and increments the version.

| Check | Catches | Where |
|---|---|---|
| 1. The client sends the `version` it read in `PUT` | edits based on an old copy (between HTTP requests) | `PostService.update`, `CommentService.update` → `ObjectOptimisticLockingFailureException` |
| 2. `UPDATE ... WHERE version = ?` updates 0 rows | two requests that read the same version at the same moment | Hibernate on flush/commit |

- **Result:** both cases become **409 Conflict**, *"This record was changed by someone else. Load it again and retry your change."* (`GlobalExceptionHandler` handles `OptimisticLockingFailureException`).
- **Bulk updates:** the `publish` endpoint uses a bulk JPQL UPDATE, which skips `@Version`, so the query adds `version = version + 1` itself.
- **Soft delete:** the `@SQLDelete` of `Post` and `Comment` is `UPDATE ... SET deleted_at = NOW(6) WHERE id = ? AND version = ? AND deleted_at IS NULL`. Hibernate binds the version it loaded, so a delete that races with an edit updates 0 rows. `verify = Expectation.RowCount.class` makes that an error (409). Without it Hibernate ignores the row count of custom SQL and the delete would report success (`SoftDeleteTest.deleteWithAnOldVersion_isRefused_andThePostStaysLive`).
- **Why optimistic, not pessimistic** (`SELECT ... FOR UPDATE`) **locking:** conflicts on the same post are rare in a blog, and optimistic locking holds no DB locks while a user is editing.

## 6. Transactions and the cache

`@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)` makes the cache proxy wrap the transaction proxy:
```
request → cache → transaction → method
```
- A cache hit returns without opening a transaction. `CacheTest` checks: 0 transactions on a hit, 1 on a miss.
- `@CachePut` / `@CacheEvict` run **after the commit**. Before the commit, another request could re-cache the old row.
- A failed or rolled-back write changes nothing in the cache.

## 7. Tests (`TransactionTest`)

The class is **not** `@Transactional`, so commits and rollbacks are real. Tables are emptied before and after each test.

| Test | Proves |
|---|---|
| `createPost_failsHalfway_nothingIsSaved_butAuditStays` | tag 1 inserted, tag 2 fails → nothing saved (not even tag 1); the audit row survives (REQUIRES_NEW) |
| `createPost_checkedException_isRolledBack_thanksToRollbackFor` | `rollbackFor` works |
| `createPost_success_savesPostTagsAndAudit` | the normal path commits everything |
| `findOrCreateTags_withoutTransaction_isRefused` | MANDATORY |
| `twoConcurrentUpdates_secondOneFails_firstOneIsKept` | users A and B read version 0, B commits, A's commit fails, B's change is kept |
| `repeatableRead_allCountsComeFromTheSameSnapshot` / `readCommitted_seesRowsCommittedInTheMiddle` | isolation levels |
| `stats_numbersFitTogether` | `/api/stats` consistency |

`ApiTest` checks the HTTP side: 409 for an old post/comment version, 400 when `version` is missing, 400 for a banned word.
