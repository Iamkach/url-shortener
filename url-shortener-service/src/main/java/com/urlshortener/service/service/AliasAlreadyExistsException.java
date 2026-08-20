package com.urlshortener.service.service;

public class AliasAlreadyExistsException extends RuntimeException {
    public AliasAlreadyExistsException(String message) {
        super(message);
    }
}
