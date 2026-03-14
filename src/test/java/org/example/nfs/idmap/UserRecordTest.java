package org.example.nfs.idmap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserRecordTest {

    @Test
    void recordAccessors() {
        var r = new UserRecord(42, "alice");
        assertEquals(42, r.userId());
        assertEquals("alice", r.userCode());
    }

    @Test
    void equalityAndHashCode() {
        var a = new UserRecord(1, "bob");
        var b = new UserRecord(1, "bob");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void toStringContainsFields() {
        var r = new UserRecord(7, "carol");
        var s = r.toString();
        assertTrue(s.contains("7"));
        assertTrue(s.contains("carol"));
    }
}
