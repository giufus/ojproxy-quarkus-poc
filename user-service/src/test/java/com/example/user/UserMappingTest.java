package com.example.user;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserMappingTest {

    @Test
    void newUserHoldsAssignedFieldsAndDeferreCreatedAt() {
        User u = new User();
        u.name = "alice";
        u.email = "alice@example.com";

        assertEquals("alice", u.name);
        assertEquals("alice@example.com", u.email);
        assertNull(u.createdAt, "createdAt is set by @PrePersist, not by the constructor");
    }
}
