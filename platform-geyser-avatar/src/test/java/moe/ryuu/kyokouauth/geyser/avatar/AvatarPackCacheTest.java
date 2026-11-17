package moe.ryuu.kyokouauth.geyser.avatar;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AvatarPackCacheTest {
    @TempDir Path directory;

    @Test void baseAndOverlayUseOnlyOwnedPathsManifestAndContentUuidAndNeverReplaceSkullOrIntegratedResources() throws Exception {
        try (AvatarPackCache cache = new AvatarPackCache(directory, 4);
             AvatarPackCache.Lease base = cache.base();
             AvatarPackCache.Lease overlay = cache.acquire(AvatarTestData.snapshot(0xFF123456))) {
            assertNotNull(overlay);
            Map<String, byte[]> baseFiles = AvatarTestData.entries(Files.readAllBytes(base.pack().path()));
            Map<String, byte[]> overlayFiles = AvatarTestData.entries(Files.readAllBytes(overlay.pack().path()));
            assertEquals(Set.of("manifest.json", "textures/item_texture.json", AvatarPackWriter.U_PATH, AvatarPackWriter.J_PATH), baseFiles.keySet());
            assertEquals(Set.of("manifest.json", AvatarPackWriter.U_PATH, AvatarPackWriter.J_PATH), overlayFiles.keySet());
            var manifest = JsonParser.parseString(new String(overlayFiles.get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(2, manifest.get("format_version").getAsInt());
            assertEquals(overlay.pack().uuid().toString(), manifest.getAsJsonObject("header").get("uuid").getAsString());
            assertEquals("[1,0,0]", manifest.getAsJsonObject("header").get("version").toString());
            assertNotEquals(manifest.getAsJsonObject("header").get("uuid").getAsString(),
                    manifest.getAsJsonArray("modules").get(0).getAsJsonObject().get("uuid").getAsString());
            var atlas = JsonParser.parseString(new String(baseFiles.get("textures/item_texture.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(Set.of("kyokouauth_role_u", "kyokouauth_role_j"), atlas.getAsJsonObject("texture_data").keySet());
            assertTrue(baseFiles.keySet().stream().noneMatch(name -> name.contains("skull") || name.contains("integrated") || name.contains("..")));
            AvatarPng.validate(overlayFiles.get(AvatarPackWriter.U_PATH));
            assertTrue(base.pack().bytes() <= 32768 && overlay.pack().bytes() <= 32768);
        }
    }

    @Test void sameContentSharesImmutableUuidWhileNextSessionSkinContentGetsANewUuidAndFile() throws Exception {
        try (AvatarPackCache cache = new AvatarPackCache(directory, 4);
             AvatarPackCache.Lease first = cache.acquire(AvatarTestData.snapshot(0xFF123456));
             AvatarPackCache.Lease same = cache.acquire(AvatarTestData.snapshot(0xFF123456));
             AvatarPackCache.Lease changed = cache.acquire(AvatarTestData.snapshot(0xFF654321))) {
            assertEquals(first.pack().uuid(), same.pack().uuid());
            assertEquals(first.pack().path(), same.pack().path());
            assertNotEquals(first.pack().uuid(), changed.pack().uuid());
            assertNotEquals(first.pack().path(), changed.pack().path());
            assertEquals(3, cache.packCount());
        }
    }

    @Test void activeLeasesPinFilesAcrossEvictionCloseAndCompetingGenerationUntilExactRelease() throws Exception {
        AvatarPackCache cache = new AvatarPackCache(directory, 3);
        AvatarPackCache.Lease first = cache.acquire(AvatarTestData.snapshot(0xFF000001));
        AvatarPackCache.Lease second = cache.acquire(AvatarTestData.snapshot(0xFF000002));
        Path firstPath = first.pack().path(), secondPath = second.pack().path();
        byte[] stable = Files.readAllBytes(firstPath);
        assertNull(cache.acquire(AvatarTestData.snapshot(0xFF000003)), "No active reference may be evicted");
        assertArrayEquals(stable, Files.readAllBytes(firstPath));
        first.close();
        AvatarPackCache.Lease third = cache.acquire(AvatarTestData.snapshot(0xFF000003));
        Path thirdPath = third.pack().path();
        assertFalse(Files.exists(firstPath));
        assertTrue(Files.exists(secondPath));
        cache.close();
        assertTrue(Files.exists(secondPath));
        assertTrue(Files.exists(thirdPath));
        assertThrows(Exception.class, () -> new AvatarPackCache(directory, 3));
        second.close(); second.close();
        assertFalse(Files.exists(secondPath));
        third.close();
        assertFalse(Files.exists(thirdPath));
        cache.close();
        try (AvatarPackCache next = new AvatarPackCache(directory, 3)) { assertEquals(1, next.packCount()); }
    }

    @Test void sixtyFourPackBudgetIncludesBaseAndUnknownFilesAreNeverDeleted() throws Exception {
        AvatarPackCache cache = new AvatarPackCache(directory, 64);
        List<AvatarPackCache.Lease> leases = new ArrayList<>();
        try {
            for (int i = 0; i < 63; i++) leases.add(cache.acquire(AvatarTestData.snapshot(0xFF000000 + i)));
            assertTrue(leases.stream().allMatch(java.util.Objects::nonNull));
            assertEquals(64, cache.packCount());
            assertTrue(cache.totalBytes() <= 2L * 1024 * 1024);
            assertNull(cache.acquire(AvatarTestData.snapshot(0xFF0000FE)));
        } finally {
            for (var lease : leases) if (lease != null) lease.close();
            cache.close();
        }
        Path unknown = directory.resolve("unrelated-user-file");
        Files.writeString(unknown, "not-owned");
        assertThrows(Exception.class, () -> new AvatarPackCache(directory, 4));
        assertEquals("not-owned", Files.readString(unknown));
    }
}
