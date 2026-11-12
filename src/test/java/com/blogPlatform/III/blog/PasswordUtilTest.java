package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.util.PasswordUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordUtilTest {

    @Test
    void hash_isNotThePlainPassword_andMatchesIt() {
        String hash = PasswordUtil.hash("secret123");

        assertNotEquals("secret123", hash);
        assertTrue(hash.startsWith("$2a$"));
        assertTrue(PasswordUtil.matches("secret123", hash));
        assertFalse(PasswordUtil.matches("wrong-password1", hash));
    }

    @Test
    void samePassword_givesADifferentHashEveryTime() {
        String first = PasswordUtil.hash("secret123");
        String second = PasswordUtil.hash("secret123");

        assertNotEquals(first, second);
        assertTrue(PasswordUtil.matches("secret123", first));
        assertTrue(PasswordUtil.matches("secret123", second));
    }
}
