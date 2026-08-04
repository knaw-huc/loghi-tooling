package nl.knaw.huc.di.images.minions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseMinionTest {

    @Test
    void getApiKeyThrowsWhenBlank() {
        BaseMinion.setApiKey("");
        assertThrows(IllegalStateException.class, BaseMinion::getApiKey);
    }

    @Test
    void getApiKeyReturnsConfiguredValue() {
        BaseMinion.setApiKey("test-key-123");
        assertEquals("test-key-123", BaseMinion.getApiKey());
    }

    @Test
    void processArgsSetsServerAndKey() {
        BaseMinion.processArgs(new String[]{"http://example:9006/", "abc-key"});
        assertEquals("http://example:9006/", BaseMinion.getServerUri());
        assertEquals("abc-key", BaseMinion.getApiKey());
    }
}

