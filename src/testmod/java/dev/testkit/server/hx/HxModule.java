package dev.testkit.server.hx;

/**
 * A group of Hx ops owned by one work package. {@link #register()} calls {@link HxBridge#register} for each op,
 * named {@code <prefix>.<verb>} with the module's own prefix (surface., menu., dungeon., boss.).
 *
 * <p>Modules are listed in {@link HxModules}, which is frozen after WP1: each WP fills in the stub it was given and
 * never edits another WP's module or the core ({@link HxPrimitives}).
 */
public interface HxModule {

    /** Short name, for the bridge's startup line and error messages. */
    String name();

    /** Register this module's ops. Called once, before the server starts. */
    void register();
}
