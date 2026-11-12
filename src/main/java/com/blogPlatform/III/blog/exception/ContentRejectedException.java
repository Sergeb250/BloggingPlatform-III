package com.blogPlatform.III.blog.exception;

// checked on purpose: Spring commits on checked exceptions unless the method declares rollbackFor
public class ContentRejectedException extends Exception {

    public ContentRejectedException(String message) {
        super(message);
    }
}
