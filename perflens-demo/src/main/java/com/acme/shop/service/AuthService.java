package com.acme.shop.service;

import com.acme.shop.domain.AppUserRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Shows masking: the `password` argument and the `password_hash` bind value never appear in evidence. */
@Service
public class AuthService {
    private final AppUserRepository users;

    public AuthService(AppUserRepository users) {
        this.users = users;
    }

    public boolean login(String username, String password) {
        return users.findByUsernameAndPasswordHash(username, hash(password)).isPresent();
    }

    public static String hash(String password) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
