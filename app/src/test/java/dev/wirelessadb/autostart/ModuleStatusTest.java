package dev.wirelessadb.autostart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ModuleStatusTest {
    @Test
    public void defaultSelfHookIsInactiveWithoutXposed() {
        assertFalse(ModuleStatus.isSelfHooked());
        assertEquals(-1, ModuleStatus.getXposedApiVersion());
    }

    @Test
    public void evaluateInactiveWhenNeitherSelfNorSystemHookResponds() {
        ModuleStatus.Result res = ModuleStatus.evaluate(
                false, -1, false, false, false, false, -1, 12L);
        assertEquals(ModuleStatus.State.INACTIVE, res.state);
        assertFalse(res.isActive());
        assertTrue(res.isInactive());
        assertTrue(res.diagnosticSummary().contains("未注入"));
        assertTrue(res.diagnosticSummary().contains("无响应"));
    }

    @Test
    public void evaluateScopeOrRebootWhenOnlySelfHookIsActive() {
        ModuleStatus.Result res = ModuleStatus.evaluate(
                true, 93, false, false, false, false, -1, 18L);
        assertEquals(ModuleStatus.State.SCOPE_OR_REBOOT_REQUIRED, res.state);
        assertFalse(res.isActive());
        assertTrue(res.isInactive());
        assertTrue(res.diagnosticSummary().contains("API 93"));
        assertTrue(res.diagnosticSummary().contains("无响应"));
    }

    @Test
    public void evaluateActiveWhenSystemPingRespondsEvenWithoutSelfHook() {
        ModuleStatus.Result res = ModuleStatus.evaluate(
                false, -1, true, true, false, true, ModuleStatus.HOOK_PROTOCOL_VERSION, 25L);
        assertEquals(ModuleStatus.State.ACTIVE, res.state);
        assertTrue(res.isActive());
        assertFalse(res.isInactive());
        assertTrue(res.diagnosticSummary().contains("正常响应 (25ms)"));
    }

    @Test
    public void evaluateActiveWhenPingTimesOutButCurrentBootMarkerMatches() {
        ModuleStatus.Result res = ModuleStatus.evaluate(
                true, 93, false, true, true, false, -1, ModuleStatus.PING_TIMEOUT_MS);
        assertEquals(ModuleStatus.State.ACTIVE, res.state);
        assertTrue(res.isActive());
        assertTrue(res.diagnosticSummary().contains("已加载（探测超时）"));
    }

    @Test
    public void evaluateInactiveWhenPingTimesOutAndBootMarkerIsStale() {
        ModuleStatus.Result res = ModuleStatus.evaluate(
                false, -1, false, false, true, false, -1, ModuleStatus.PING_TIMEOUT_MS);
        assertEquals(ModuleStatus.State.INACTIVE, res.state);
        assertFalse(res.isActive());
        assertTrue(res.diagnosticSummary().contains("响应超时"));
    }

    @Test
    public void bootMarkerMatchingRejectsStaleOrMalformedValues() {
        assertTrue(ModuleStatus.matchesBootMarker("42:2", 42));
        assertTrue(ModuleStatus.matchesBootMarker("42", 42));
        assertFalse(ModuleStatus.matchesBootMarker("41:2", 42));
        assertFalse(ModuleStatus.matchesBootMarker("", 42));
        assertFalse(ModuleStatus.matchesBootMarker(null, 42));
        assertFalse(ModuleStatus.matchesBootMarker("abc:2", 42));
        assertFalse(ModuleStatus.matchesBootMarker("0:2", 0));
    }
}
