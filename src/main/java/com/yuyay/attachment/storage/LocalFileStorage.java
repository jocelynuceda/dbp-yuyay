package com.yuyay.attachment.storage;

import com.yuyay.attachment.dto.AttachmentAccessResponse;
import com.yuyay.attachment.exception.FileStorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Guarda los archivos en disco para desarrollar sin AWS. Textract no puede leerlos: el OCR queda en FAILED. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.type", havingValue = "local")
public class LocalFileStorage implements FileStorage {
    private final Path root;

    public LocalFileStorage(@Value("${app.storage.local-dir}") String rootDir) {
        this.root = Path.of(rootDir).toAbsolutePath().normalize();
    }

    @Override
    public void store(String key, byte[] content, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            log.error("Fallo guardando {} en disco", key, e);
            throw new FileStorageException("No se pudo guardar el archivo");
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            log.error("Fallo leyendo {} de disco", key, e);
            throw new FileStorageException("No se pudo leer el archivo");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.error("No se pudo borrar {} de disco", key, e);
        }
    }

    @Override
    public void deleteRequired(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            log.warn("Fallo borrando el adjunto {} de disco", key, e);
            throw new FileStorageException("No se pudo eliminar el archivo");
        }
    }

    @Override
    public AttachmentAccessResponse createAccess(String key, String contentType, Duration ttl) {
        throw new FileStorageException("El enlace temporal requiere S3; en desarrollo usa /content");
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new FileStorageException("Ruta de archivo inválida");
        }
        return target;
    }
}
