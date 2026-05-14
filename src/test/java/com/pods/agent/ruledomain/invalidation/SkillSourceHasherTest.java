package com.pods.agent.ruledomain.invalidation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillSourceHasherTest {

    private final SkillSourceHasher hasher = new SkillSourceHasher();

    @Test
    void sameContentSameHash() {
        assertEquals(hasher.hash("hello"), hasher.hash("hello"));
    }

    @Test
    void whitespaceSensitive() {
        assertNotEquals(hasher.hash("hello"), hasher.hash("hello "));
    }

    @Test
    void emptyInputProducesEmptyHash() {
        assertEquals("", hasher.hash(null));
    }
}
