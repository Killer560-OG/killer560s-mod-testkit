package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 1104-ui-univfolders (mod univfolders-1104): imported menu backgrounds and GIFs live in ONE folder for every instance
 * (the shared store's backgrounds/ and gifs/), not the instance's config. Files left in the old config folders move in
 * (identical bytes once, different bytes both), still load, are found by a second game folder's move, and the
 * path-safety rule still holds.
 */
final class UniversalFolders1104Cases {

    private static final String GIF = "gifplayer.GifPlayerFeature";
    private static final String BG = "menuthemes.ThemeBackgrounds";
    private static final String SF = "shared.SharedFolders";

    private UniversalFolders1104Cases() {
    }

    static void moved(UiCase c) throws Exception {
        Path root = (Path) Mod.staticCall("shared.SharedRecords", "root");
        Path oldGifs = (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-gifs");
        Path oldBgs = (Path) Mod.staticCall("util.ModPaths", "config", "killer560smod-menu-themebackgrounds");
        Mod.staticCall(SF, "forgetForTest");
        Path gifs = (Path) Mod.staticCall(GIF, "folder");
        Path bgs = (Path) Mod.staticCall(BG, "folder");
        c.check(gifs.equals(root.resolve("gifs")) && bgs.equals(root.resolve("backgrounds")),
                "folders are not under the shared store: " + gifs + " " + bgs);
        // Start clean (a profile test may have left files), then leave files in the OLD per-instance folders.
        ProfileDataCases.clearFolder(gifs);
        ProfileDataCases.clearFolder(bgs);
        ProfileDataCases.writeGif(oldGifs.resolve("tk-univ.gif"), 0xFF00FF, 32, 32);
        ProfileDataCases.writeGif(oldGifs.resolve("tk-same.gif"), 0x00FF00, 16, 16);
        ProfileDataCases.writeGif(gifs.resolve("tk-same.gif"), 0x00FF00, 16, 16);
        ProfileDataCases.writeGif(oldGifs.resolve("tk-clash.gif"), 0xFF0000, 16, 16);
        ProfileDataCases.writeGif(gifs.resolve("tk-clash.gif"), 0x0000FF, 16, 16);
        ProfileDataCases.writeGif(oldBgs.resolve("tk-univ-bg.gif"), 0xFFFF00, 64, 64);
        Mod.staticCall(SF, "forgetForTest");
        Path gifs2 = (Path) Mod.staticCall(GIF, "folder");
        Path bgs2 = (Path) Mod.staticCall(BG, "folder");
        c.check(gifs2.equals(gifs) && bgs2.equals(bgs), "the folders moved");
        c.check(Files.isRegularFile(gifs.resolve("tk-univ.gif")), "tk-univ.gif did not move to " + gifs);
        c.check(Files.isRegularFile(bgs.resolve("tk-univ-bg.gif")), "the background did not move to " + bgs);
        c.check(!Files.exists(oldGifs) && !Files.exists(oldBgs), "the old folders were left behind (a move, not a copy)");
        c.check(Files.isRegularFile(gifs.resolve("tk-clash.gif")) && Files.isRegularFile(gifs.resolve("tk-clash-2.gif"))
                        && Files.mismatch(gifs.resolve("tk-clash.gif"), gifs.resolve("tk-clash-2.gif")) >= 0,
                "a clash with different bytes did not keep both");
        c.check(Files.isRegularFile(gifs.resolve("tk-same.gif")) && !Files.exists(gifs.resolve("tk-same-2.gif")),
                "identical bytes were kept twice");
        // Still loads: the lists see them, the path rule holds.
        @SuppressWarnings("unchecked")
        List<String> imported = (List<String>) Mod.staticCall(BG, "imported");
        c.check(imported.contains("gif:tk-univ-bg.gif"), "the moved background is not listed: " + imported);
        @SuppressWarnings("unchecked")
        List<String> listed = (List<String>) Mod.staticCall(GIF, "listedFileNames");
        c.check(listed.contains("tk-univ.gif"), "the moved GIF is not listed: " + listed);
        c.check(Mod.staticCall(GIF, "fileInFolder", "tk-univ.gif") != null, "fileInFolder refused a file in the folder");
        c.check(Mod.staticCall(GIF, "fileInFolder", "../tk-univ.gif") == null, "fileInFolder let a .. name out");
        // A second game folder: its old files fold into the same shared folder.
        Path other = Files.createTempDirectory("tk-univ-second");
        ProfileDataCases.writeGif(other.resolve("tk-univ.gif"), 0xFF00FF, 32, 32);
        ProfileDataCases.writeGif(other.resolve("tk-second.gif"), 0x00FFFF, 24, 24);
        Mod.staticCall(SF, "moveInto", other, gifs);
        c.check(Files.isRegularFile(gifs.resolve("tk-second.gif")) && !Files.exists(gifs.resolve("tk-univ-2.gif"))
                && !Files.exists(other), "a second game folder's files were not folded in once");
        ProfileDataCases.clearFolder(gifs);
        ProfileDataCases.clearFolder(bgs);
    }
}
