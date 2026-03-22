package org.example.nfs.db;

import org.dcache.nfs.vfs.Inode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DbInodeMapper}.
 */
class DbInodeMapperTest {

    @Test
    void roundTripZero() {
        assertEquals(0L, DbInodeMapper.toFileId(DbInodeMapper.toInode(0L)));
    }

    @Test
    void roundTripOne() {
        assertEquals(1L, DbInodeMapper.toFileId(DbInodeMapper.toInode(1L)));
    }

    @Test
    void roundTripMaxValue() {
        assertEquals(Long.MAX_VALUE,
                DbInodeMapper.toFileId(DbInodeMapper.toInode(Long.MAX_VALUE)));
    }

    @Test
    void roundTripRandom() {
        Random rng = new Random(0xDEADBEEF);
        for (int i = 0; i < 100; i++) {
            long id = rng.nextLong();
            assertEquals(id, DbInodeMapper.toFileId(DbInodeMapper.toInode(id)),
                    "Round-trip failed for id=" + id);
        }
    }

    @Test
    void rootInode() {
        assertEquals(1L, DbInodeMapper.toFileId(DbInodeMapper.ROOT));
    }

    @Test
    void rootConstantMatchesToInode1() {
        Inode expected = DbInodeMapper.toInode(1L);
        assertArrayEquals(expected.getFileId(), DbInodeMapper.ROOT.getFileId());
    }

    @Test
    void differentIdsProduceDifferentInodes() {
        Random rng = new Random(42);
        Set<String> handles = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long id = rng.nextLong();
            Inode inode = DbInodeMapper.toInode(id);
            String key = new String(inode.getFileId());
            assertTrue(handles.add(key),
                    "Collision detected for id=" + id);
        }
    }

    @Test
    void invalidHandleLengthThrows() {
        Inode bogus = Inode.forFile(new byte[]{1, 2, 3}); // only 3 bytes
        assertThrows(IllegalArgumentException.class, () -> DbInodeMapper.toFileId(bogus));
    }
}
