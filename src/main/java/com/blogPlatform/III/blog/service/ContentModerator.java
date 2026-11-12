package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.exception.ContentRejectedException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ContentModerator {

    private static final List<String> BANNED_WORDS = List.of("spam", "scam", "casino");

    public void check(String title, String content) throws ContentRejectedException {
        String text = (title + " " + content).toLowerCase();
        for (String word : BANNED_WORDS) {
            if (text.contains(word)) {
                throw new ContentRejectedException("Post rejected: it contains the banned word '" + word + "'");
            }
        }
    }
}
