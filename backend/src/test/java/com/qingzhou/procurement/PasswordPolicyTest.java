package com.qingzhou.procurement;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class PasswordPolicyTest {
    @Test void eightCharacterRecoveryPasswordUsesCompatibleHash() {
        String hash=Application.hashPassword("Test123!");
        assertTrue(new BCryptPasswordEncoder().matches("Test123!",hash));
        assertFalse(hash.contains("Test123!"));
    }
    @Test void rejectsShortBlankAndOverlongUtf8RecoveryPasswords() {
        assertThrows(IllegalArgumentException.class,()->Application.hashPassword("Test12!"));
        assertThrows(IllegalArgumentException.class,()->Application.hashPassword("        "));
        assertThrows(IllegalArgumentException.class,()->Application.hashPassword("密".repeat(25)));
    }
}
