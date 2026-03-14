package org.example.nfs.idmap;

/**
 * Lightweight projection of a sys_user row.
 */
public record UserRecord(int userId, String userCode) {}
