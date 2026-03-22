package org.example.nfs.db.blob;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract contract test for {@link BlobHandle}.
 * Subclasses implement {@link #createHandle()} and {@link #openHandle()}
 * to exercise any concrete implementation.
 */
public abstract class BlobHandleContractTest {

    /** Create a new, empty BlobHandle for testing. */
    protected abstract BlobHandle createHandle() throws Exception;

    /**
     * Re-open the same blob that was created/written by {@link #createHandle()}.
     * Used for persistence tests.
     */
    protected abstract BlobHandle openHandle() throws Exception;

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void writeAndReadBack() throws Exception {
        byte[] data = new byte[1000];
        new Random(42).nextBytes(data);

        try (BlobHandle h = createHandle()) {
            h.write(data);
            h.seek(0);
            byte[] result = h.read(1000);
            assertArrayEquals(data, result);
        }
    }

    @Test
    void multipleWrites() throws Exception {
        byte[] hello = "hello".getBytes();
        byte[] world = "world".getBytes();

        try (BlobHandle h = createHandle()) {
            h.write(hello);
            h.write(world);
            h.seek(0);
            byte[] result = h.read(10);
            assertArrayEquals("helloworld".getBytes(), result);
        }
    }

    @Test
    void seekAndWrite() throws Exception {
        byte[] initial = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        try (BlobHandle h = createHandle()) {
            h.write(initial);
            assertEquals(10, h.size());

            // Overwrite bytes at offset 5
            h.seek(5);
            h.write(new byte[]{20, 21, 22});

            // Total size should still be 10
            assertEquals(10, h.size());

            // Verify overwritten region
            h.seek(5);
            byte[] result = h.read(3);
            assertArrayEquals(new byte[]{20, 21, 22}, result);
        }
    }

    @Test
    void readAtEof() throws Exception {
        try (BlobHandle h = createHandle()) {
            h.write(new byte[]{1, 2, 3, 4, 5});
            h.seek(5);
            byte[] result = h.read(10);
            assertEquals(0, result.length);
        }
    }

    @Test
    void readBeyondEnd() throws Exception {
        byte[] data = new byte[]{1, 2, 3, 4, 5};

        try (BlobHandle h = createHandle()) {
            h.write(data);
            h.seek(0);
            byte[] result = h.read(100);
            assertEquals(5, result.length);
            assertArrayEquals(data, result);
        }
    }

    @Test
    void truncate() throws Exception {
        try (BlobHandle h = createHandle()) {
            h.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
            h.truncate(5);
            assertEquals(5, h.size());
            // Position should be clamped to new size
            assertTrue(h.tell() <= 5);
        }
    }

    @Test
    void closeThenOperationThrows() throws Exception {
        BlobHandle h = createHandle();
        h.close();
        assertThrows(IOException.class, () -> h.read(1));
        assertThrows(IOException.class, () -> h.write(new byte[]{1}));
        assertThrows(IOException.class, () -> h.seek(0));
        assertThrows(IOException.class, () -> h.tell());
        assertThrows(IOException.class, () -> h.size());
        assertThrows(IOException.class, () -> h.truncate(0));
    }

    @Test
    void closeIsIdempotent() throws Exception {
        BlobHandle h = createHandle();
        h.close();
        assertDoesNotThrow(h::close);
    }

    @Test
    void persistenceAcrossHandles() throws Exception {
        byte[] data = "Hello, persistence!".getBytes();

        try (BlobHandle h = createHandle()) {
            h.write(data);
        }

        try (BlobHandle h = openHandle()) {
            h.seek(0);
            byte[] result = h.read(data.length);
            assertArrayEquals(data, result);
        }
    }

    @Test
    void writeAtOffsetBeyondCurrentSize() throws Exception {
        // Behavior is driver-dependent: some expand with zero bytes, some fail.
        // We document behavior rather than assert a specific outcome.
        try (BlobHandle h = createHandle()) {
            h.write(new byte[]{1, 2, 3});
            assertEquals(3, h.size());

            // Seek past end and write
            h.seek(10);
            try {
                h.write(new byte[]{99});
                // If driver supports it, size should be at least 11
                assertTrue(h.size() >= 11,
                        "After writing at offset 10, size should be >= 11; was " + h.size());
            } catch (IOException e) {
                // Some drivers may not support seeking past end — that's also acceptable
            }
        }
    }

    @Test
    void tellReflectsPosition() throws Exception {
        try (BlobHandle h = createHandle()) {
            assertEquals(0L, h.tell());
            h.write(new byte[]{1, 2, 3});
            assertEquals(3L, h.tell());
            h.seek(1);
            assertEquals(1L, h.tell());
        }
    }
}
