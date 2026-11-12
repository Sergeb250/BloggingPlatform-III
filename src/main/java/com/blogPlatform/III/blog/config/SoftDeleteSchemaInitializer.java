package com.blogPlatform.III.blog.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Unique keys that ignore soft-deleted rows.
 * <p>
 * A soft-deleted row stays in its table. A plain {@code UNIQUE (username)} would still see it, so a deleted
 * user's name, a deleted post's title or a deleted tag's name could never be used again, and a user could
 * never review a post again after deleting their review.
 * <p>
 * MySQL has no partial unique index ({@code ... WHERE deleted_at IS NULL}). The usual workaround:
 * <ol>
 *     <li>a generated column {@code not_deleted}: 1 while {@code deleted_at} is NULL, NULL once the row is deleted</li>
 *     <li>every unique key ends with that column, e.g. {@code UNIQUE (username, not_deleted)}</li>
 * </ol>
 * A unique index never compares NULLs, so live rows (all 1) must differ, and deleted rows (NULL) never clash.
 * <p>
 * {@code @Index} / {@code @UniqueConstraint} can't express this, so it is done here with SQL, once.
 * Every step checks first, so a restart changes nothing.
 */
@Slf4j
@Component
// runs after Hibernate has created/updated the tables (and their deleted_at columns),
// and before the web server takes requests
@DependsOn("entityManagerFactory")
@RequiredArgsConstructor
public class SoftDeleteSchemaInitializer implements InitializingBean {

    static final String LIVE_COLUMN = "not_deleted";

    record LiveUniqueKey(String table, String name, String columns) {
    }

    static final List<LiveUniqueKey> UNIQUE_KEYS = List.of(
            new LiveUniqueKey("users", "uk_users_username_live", "username"),
            new LiveUniqueKey("users", "uk_users_email_live", "email"),
            new LiveUniqueKey("posts", "uk_posts_author_title_live", "author_id, title"),
            new LiveUniqueKey("reviews", "uk_reviews_post_user_live", "post_id, user_id"),
            new LiveUniqueKey("tags", "uk_tags_name_live", "name"));

    private final JdbcTemplate jdbc;

    @Override
    public void afterPropertiesSet() {
        List<String> tables = UNIQUE_KEYS.stream().map(LiveUniqueKey::table).distinct().toList();

        tables.forEach(this::addLiveColumnIfMissing);
        // new keys first: the old (post_id, user_id) / (author_id, title) keys also serve a foreign key,
        // and MySQL only lets us drop them once another index starts with the same column
        UNIQUE_KEYS.forEach(this::createUniqueKeyIfMissing);
        tables.forEach(this::dropUniqueKeysThatCountDeletedRows);
    }

    private void addLiveColumnIfMissing(String table) {
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, Integer.class, table, LIVE_COLUMN);
        if (existing != null && existing == 0) {
            log.info("Adding generated column {}.{}", table, LIVE_COLUMN);
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + LIVE_COLUMN
                    + " TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) STORED");
        }
    }

    private void createUniqueKeyIfMissing(LiveUniqueKey key) {
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                """, Integer.class, key.table(), key.name());
        if (existing != null && existing == 0) {
            log.info("Creating unique key {} on {} ({}, {})", key.name(), key.table(), key.columns(), LIVE_COLUMN);
            jdbc.execute("CREATE UNIQUE INDEX " + key.name() + " ON " + key.table()
                    + " (" + key.columns() + ", " + LIVE_COLUMN + ")");
        }
    }

    // the old keys from before soft delete, e.g. UNIQUE (username): they would still count deleted rows
    private void dropUniqueKeysThatCountDeletedRows(String table) {
        List<String> oldKeys = jdbc.queryForList("""
                SELECT INDEX_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
                  AND NON_UNIQUE = 0 AND INDEX_NAME <> 'PRIMARY'
                GROUP BY INDEX_NAME
                HAVING SUM(COLUMN_NAME = ?) = 0
                """, String.class, table, LIVE_COLUMN);
        for (String index : oldKeys) {
            log.info("Dropping unique key {} on {}: it would also count soft-deleted rows", index, table);
            jdbc.execute("ALTER TABLE " + table + " DROP INDEX `" + index + "`");
        }
    }
}
