package dev.testkit.gametest.ui;

import dev.testkit.gametest.mod.Mod;
import net.minecraft.client.gui.components.AbstractWidget;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * killer560 2026-11-18 (tttwalkout-rm-1118): Auto Tic Tac Toe's "Walk Out When Done" is gone. The tab has no such row
 * (Grab Secret stays), an old file carrying its key loads without error and is rewritten without it. The solve itself
 * (clicks sent, nothing moves him afterwards) is asserted by 93-solve-tictactoe-grab's verdict.
 */
final class TttWalkOut1118Cases {

    private TttWalkOut1118Cases() {
    }

    private static Object newTab() {
        try {
            return Mod.cls("gui.tab.AutoPuzzlesTab").getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void gone(UiCase c) throws Exception {
        if (!Mod.isCheat()) {
            c.note("Auto Puzzles is cheat-only: nothing to check on a legit jar");
            return;
        }
        String cls = "autopuzzles.AutoPuzzlesConfig";
        Map<Path, byte[]> saved = DungeonB2Cases.snapshotConfig(c, cls);
        boolean[] gateOn = {true};
        try {
            Path p = saved.keySet().iterator().next();
            Files.createDirectories(p.getParent());
            Files.writeString(p, "{\"autoPuzzlesMasterEnabled\":true,\"autoTicTacToeEnabled\":true,\"ticTacToeAuraChestEnabled\":true,"
                    + "\"ticTacToeWalkOutEnabled\":true}", StandardCharsets.UTF_8);
            gateOn[0] = c.onClient(mc -> (Boolean) Mod.staticCall("util.SkyblockGate", "isEnabled"));
            c.onClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", false));
            List<String> rows = c.onClient(mc -> {
                Mod.staticCall(cls, "load");
                Mod.call(Mod.cfg(cls), "save");
                List<String> out = new ArrayList<>();
                Object tab = newTab();
                for (AbstractWidget w : (List<AbstractWidget>) Mod.call(tab, "searchScanWidgets")) {
                    out.add(ModScreenDriver.label(w));
                }
                return out;
            });
            c.note("Auto Puzzles rows: " + rows);
            c.check(rows.stream().anyMatch(l -> l.startsWith("Grab Secret")), "Grab Secret row missing: " + rows);
            c.check(rows.stream().noneMatch(l -> l.toLowerCase().contains("walk out")), "a Walk Out row is still there: " + rows);
            String json = Files.readString(p, StandardCharsets.UTF_8);
            c.check(json.contains("ticTacToeAuraChestEnabled\": true") || json.contains("ticTacToeAuraChestEnabled\":true"),
                    "the other Tic Tac Toe key did not survive the load: " + json);
            c.check(!json.contains("ticTacToeWalkOutEnabled"), "the old key was written back: " + json);
            c.check(java.util.Arrays.stream(Mod.cls(cls).getMethods())
                    .noneMatch(m -> m.getName().toLowerCase().contains("walkout")), "a walk out method is still on the config");
        } finally {
            c.onClient(mc -> Mod.staticCall("util.SkyblockGate", "setEnabled", gateOn[0]));
            DungeonB2Cases.restoreConfig(c, saved, cls);
        }
    }
}
