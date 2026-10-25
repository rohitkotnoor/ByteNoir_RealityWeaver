package com.realityweaver.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Registers and verifies accounts saved on this device. */
public final class AuthService {
    private static final String HASH_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int HASH_ITERATIONS = 600_000;
    private static final int HASH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final long MAX_STORE_BYTES = 1_048_576;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final Path accountFile;
    private final Gson gson = new Gson();
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService() {
        this(Path.of(System.getProperty("user.home"), ".realityweaver", "accounts.json"));
    }

    AuthService(Path accountFile) {
        this.accountFile = accountFile.toAbsolutePath().normalize();
    }

    public synchronized boolean hasAccounts() throws IOException {
        return !readAccounts().isEmpty();
    }

    /** Creates an account, then clears the supplied password array. */
    public synchronized LocalAccount register(String name, String email, char[] password)
            throws IOException, DuplicateEmailException {
        try {
            String displayName = validateName(name);
            String cleanEmail = validateEmail(email);
            validatePassword(password);

            Map<String, Credential> accounts = readAccounts();
            String emailKey = normalizeEmail(cleanEmail);
            if (accounts.containsKey(emailKey)) {
                throw new DuplicateEmailException();
            }

            byte[] salt = new byte[SALT_BYTES];
            secureRandom.nextBytes(salt);
            byte[] hash = deriveHash(password, salt, HASH_ITERATIONS);
            accounts.put(emailKey, new Credential(displayName, cleanEmail, salt, hash, HASH_ITERATIONS));
            writeAccounts(accounts);
            return new LocalAccount(displayName, cleanEmail);
        } finally {
            clear(password);
        }
    }

    /** Verifies an account and clears the supplied password array. */
    public synchronized Optional<LocalAccount> authenticate(String email, char[] password) throws IOException {
        try {
            String cleanEmail = validateEmail(email);
            validatePassword(password);
            Credential account = readAccounts().get(normalizeEmail(cleanEmail));
            if (account == null) {
                return Optional.empty();
            }

            byte[] candidateHash = deriveHash(password, account.salt(), account.iterations());
            if (!MessageDigest.isEqual(candidateHash, account.hash())) {
                return Optional.empty();
            }
            return Optional.of(new LocalAccount(account.displayName(), account.email()));
        } finally {
            clear(password);
        }
    }

    private Map<String, Credential> readAccounts() throws IOException {
        if (!Files.exists(accountFile)) {
            return new LinkedHashMap<>();
        }
        if (Files.size(accountFile) > MAX_STORE_BYTES) {
            throw new IOException("Local account storage is larger than expected.");
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(accountFile, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new IOException("Local account storage has an invalid format.");
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.get("formatVersion") == null || root.get("formatVersion").getAsInt() != 1
                    || root.get("accounts") == null || !root.get("accounts").isJsonArray()) {
                throw new IOException("Local account storage has an unsupported format.");
            }

            Map<String, Credential> accounts = new LinkedHashMap<>();
            for (JsonElement element : root.getAsJsonArray("accounts")) {
                if (!element.isJsonObject()) {
                    throw new IOException("Local account storage has an invalid account record.");
                }
                JsonObject value = element.getAsJsonObject();
                if (!HASH_ALGORITHM.equals(value.get("algorithm").getAsString())) {
                    throw new IOException("Local account storage uses an unsupported password hash.");
                }
                String displayName = value.get("displayName").getAsString();
                String email = validateEmail(value.get("email").getAsString());
                int iterations = value.get("iterations").getAsInt();
                if (iterations < HASH_ITERATIONS || iterations > 2_000_000) {
                    throw new IOException("Local account storage has invalid password hash settings.");
                }
                byte[] salt = Base64.getDecoder().decode(value.get("salt").getAsString());
                byte[] hash = Base64.getDecoder().decode(value.get("hash").getAsString());
                if (salt.length != SALT_BYTES || hash.length != HASH_BITS / Byte.SIZE) {
                    throw new IOException("Local account storage has an invalid password verifier.");
                }
                Credential credential = new Credential(displayName, email, salt, hash, iterations);
                if (accounts.putIfAbsent(normalizeEmail(email), credential) != null) {
                    throw new IOException("Local account storage contains duplicate accounts.");
                }
            }
            return accounts;
        } catch (RuntimeException ex) {
            throw new IOException("Local account storage could not be read.", ex);
        }
    }

    private void writeAccounts(Map<String, Credential> accounts) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        JsonArray values = new JsonArray();
        for (Credential credential : accounts.values()) {
            JsonObject value = new JsonObject();
            value.addProperty("algorithm", HASH_ALGORITHM);
            value.addProperty("displayName", credential.displayName());
            value.addProperty("email", credential.email());
            value.addProperty("salt", Base64.getEncoder().encodeToString(credential.salt()));
            value.addProperty("hash", Base64.getEncoder().encodeToString(credential.hash()));
            value.addProperty("iterations", credential.iterations());
            values.add(value);
        }
        root.add("accounts", values);

        Path parent = accountFile.getParent();
        Files.createDirectories(parent);
        Path temporaryFile = Files.createTempFile(parent, "accounts-", ".tmp");
        try {
            Files.writeString(temporaryFile, gson.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, accountFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporaryFile, accountFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private static String validateName(String name) {
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isBlank() || cleanName.length() > 60) {
            throw new IllegalArgumentException("Enter a name up to 60 characters.");
        }
        return cleanName;
    }

    private static String validateEmail(String email) {
        String cleanEmail = email == null ? "" : email.strip();
        if (cleanEmail.length() > 254 || !EMAIL_PATTERN.matcher(cleanEmail).matches()) {
            throw new IllegalArgumentException("Enter a valid email address.");
        }
        return cleanEmail;
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 10 || password.length > 1024) {
            throw new IllegalArgumentException("Use a password between 10 and 1024 characters.");
        }
    }

    private static String normalizeEmail(String email) {
        return email.toLowerCase(Locale.ROOT);
    }

    private static byte[] deriveHash(char[] password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("The required password hashing algorithm is unavailable.", ex);
        } finally {
            specification.clearPassword();
        }
    }

    private static void clear(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    private record Credential(String displayName, String email, byte[] salt, byte[] hash, int iterations) {
    }

    public static final class DuplicateEmailException extends Exception {
        private DuplicateEmailException() {
            super("An account with this email already exists.");
        }
    }
}
