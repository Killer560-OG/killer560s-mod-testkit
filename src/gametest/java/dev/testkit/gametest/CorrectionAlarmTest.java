package dev.testkit.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * The mod's correction alarm (mod rule 2026-10-06: a server correction posts a chat line and plays
 * {@code killer560smod:correction_alarm}, never stops anything). Checks, on whichever Minecraft this runs:
 * the sound manager knows the event from the mod's {@code sounds.json}, the event's sound file is in the resources,
 * {@code ModSounds.playCorrectionAlarm()} plays it (its counter rises, and its own failure line stays out of the log),
 * and a second call inside 3 s is rate-limited. (Vanilla's "unknown soundEvent" warning is not on a logger LogTap reads,
 * so the known-event check above stands in for it.) The client is muted, so the counter and the log are the proof.
 */
@dev.testkit.harness.RequiresMod("killer560smod")
public class CorrectionAlarmTest implements FabricClientGameTest {

    private static final String SOUNDS = "com.killer560.hub.util.ModSounds";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        Scenario.run(ctx, "64-correction-alarm",
                (server, scenario) -> TestMap.on(server)
                        .platform(8)
                        .catchFloor(140)
                        .survival()
                        .spawn(0.5, 0.5, 0f)
                        .build(),
                (server, scenario) -> {
                    LogTap.install();
                    long mark = LogTap.mark();
                    Identifier id = Identifier.fromNamespaceAndPath("killer560smod", "correction_alarm");
                    String[] found = ctx.computeOnClient(mc -> {
                        WeighedSoundEvents ev = mc.getSoundManager().getSoundEvent(id);
                        if (ev == null) {
                            return new String[]{"no event", "-"};
                        }
                        Sound sound = ev.getSound(RandomSource.create());
                        Identifier path = sound.getPath();
                        return new String[]{String.valueOf(path),
                                String.valueOf(mc.getResourceManager().getResource(path).isPresent())};
                    });
                    int before = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SOUNDS, "correctionAlarmsPlayed"));
                    ctx.runOnClient(mc -> ModUnderTest.staticCall(SOUNDS, "playCorrectionAlarm"));
                    ctx.waitTicks(5);
                    int afterFirst = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SOUNDS, "correctionAlarmsPlayed"));
                    ctx.runOnClient(mc -> ModUnderTest.staticCall(SOUNDS, "playCorrectionAlarm"));
                    ctx.waitTicks(5);
                    int afterSecond = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SOUNDS, "correctionAlarmsPlayed"));
                    int suppressed = ctx.computeOnClient(mc -> (Integer) ModUnderTest.staticCall(SOUNDS, "correctionAlarmsSuppressed"));
                    List<String> log = LogTap.since(mark);
                    boolean failed = log.stream().anyMatch(l -> l.contains("correction alarm failed to play"));
                    scenario.log(String.format("event file %s (in resources: %s); played %d then %d (suppressed %d);"
                            + " play failure=%s", found[0], found[1], afterFirst - before,
                            afterSecond - afterFirst, suppressed, failed));
                    if ("no event".equals(found[0])) {
                        throw new AssertionError("the sound manager does not know killer560smod:correction_alarm (sounds.json)");
                    }
                    if (!"true".equals(found[1])) {
                        throw new AssertionError("the alarm's sound file " + found[0] + " is not in the resources");
                    }
                    if (afterFirst - before != 1 || failed) {
                        throw new AssertionError("the alarm did not play cleanly (played " + (afterFirst - before)
                                + ", failure " + failed + ")");
                    }
                    if (afterSecond != afterFirst) {
                        throw new AssertionError("a second alarm inside 3 s was not rate-limited");
                    }
                });
    }
}
