package com.kano.launcher.core.games;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SteamSourceTest {
    @Test
    void parsesAppManifest() {
        String acf = """
            "AppState"
            {
              "appid"  "440"
              "name"  "Team Fortress 2"
              "StateFlags"  "4"
              "installdir"  "Team Fortress 2"
              "LastUpdated"  "1700000000"
            }
            """;
        SteamSource.AppManifest m = SteamSource.parseAppManifest(acf);
        assertNotNull(m);
        assertEquals("440", m.appId());
        assertEquals("Team Fortress 2", m.name());
        assertEquals("Team Fortress 2", m.installDir());
    }

    @Test
    void parseAppManifestReturnsNullWhenNoAppId() {
        assertNull(SteamSource.parseAppManifest("\"AppState\" { \"name\" \"x\" }"));
    }

    @Test
    void parsesLibraryPathsAndUnescapes() {
        String vdf = """
            "libraryfolders"
            {
              "0"
              {
                "path"  "C:\\\\Program Files (x86)\\\\Steam"
              }
              "1"
              {
                "path"  "D:\\\\SteamLibrary"
              }
            }
            """;
        List<String> paths = SteamSource.parseLibraryPaths(vdf);
        assertEquals(2, paths.size());
        assertEquals("C:\\Program Files (x86)\\Steam", paths.get(0));
        assertEquals("D:\\SteamLibrary", paths.get(1));
    }

    @Test
    void filtersKnownSteamTooling() {
        assertFalse(SteamSource.isRealGame("228980", "Steamworks Common Redistributables"));
        assertTrue(SteamSource.isRealGame("440", "Team Fortress 2"));
    }
}
