package com.yuyay.attachment;

import com.yuyay.attachment.exception.FileStorageException;
import com.yuyay.attachment.storage.LocalFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalFileStorageTest {

    @TempDir Path root;

    @Test
    void storesLoadsAndDeletes() {
        LocalFileStorage storage = new LocalFileStorage(root.toString());
        byte[] content = {1, 2, 3};

        storage.store("care-subjects/1/a.m4a", content, "audio/mp4");
        assertArrayEquals(content, storage.load("care-subjects/1/a.m4a"));

        storage.delete("care-subjects/1/a.m4a");
        assertFalse(Files.exists(root.resolve("care-subjects/1/a.m4a")));
    }

    @Test
    void missingFileIsAStorageError() {
        LocalFileStorage storage = new LocalFileStorage(root.toString());
        assertThrows(FileStorageException.class, () -> storage.load("no-existe.jpg"));
    }

    @Test
    void keyCannotEscapeTheRoot() {
        LocalFileStorage storage = new LocalFileStorage(root.toString());
        assertThrows(FileStorageException.class, () -> storage.store("../fuera.jpg", new byte[]{1}, "image/jpeg"));
    }
}
