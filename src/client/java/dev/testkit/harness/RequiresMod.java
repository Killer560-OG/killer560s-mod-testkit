package dev.testkit.harness;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a scenario class that reaches into one particular mod (by reflection, through its commands, or by relying on
 * what it does), so it means nothing without that mod loaded.
 *
 * <p>When the mod is not loaded - a run with {@code -NoMod}, or with someone else's mod - the class is still started,
 * but every scenario and Session case it selects is written to the report as {@code SKIP} with the reason "needs
 * &lt;mod id&gt;" instead of running ({@link ModGate}). A scenario class WITHOUT this annotation is generic: it must
 * pass with any mod, or none.
 *
 * <p>Put it on the class registered in fabric.mod.json; a nested entrypoint class ({@code Outer$Inner}) inherits its
 * outer class's annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RequiresMod {

    /** Fabric mod id(s); every one must be loaded. */
    String[] value();
}
