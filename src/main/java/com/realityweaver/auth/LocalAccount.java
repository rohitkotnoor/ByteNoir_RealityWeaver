package com.realityweaver.auth;

/** The non-secret identity details for a signed-in local account. */
public record LocalAccount(String displayName, String email) {
}
