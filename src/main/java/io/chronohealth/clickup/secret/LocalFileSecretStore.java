package io.chronohealth.clickup.secret;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Local development store: one AES-GCM encrypted file per secret.
 */
public class LocalFileSecretStore implements SecretStore {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private final Path directory;
    private final SecretCipher cipher;

    public LocalFileSecretStore(Path directory, SecretCipher cipher) {
        this.directory = directory;
        this.cipher = cipher;
    }

    @Override
    public Optional<String> get(String name) {
        Path file = fileFor(name);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(cipher.decrypt(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void put(String name, String value) {
        try {
            Files.createDirectories(directory);
            Path file = fileFor(name);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, cipher.encrypt(value), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void remove(String name) {
        try {
            Files.deleteIfExists(fileFor(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(String name) {
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid secret name");
        }
        return directory.resolve(name + ".enc");
    }
}
