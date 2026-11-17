package moe.ryuu.kyokouauth.geyser.avatar;

import java.io.IOException;

/** Read an already-prepared presentation snapshot; null is the explicit not-ready result. */
@FunctionalInterface
public interface AvatarSnapshotSource extends AutoCloseable {
    AvatarSnapshot fetch(String xuid, long absoluteDeadlineNanos) throws IOException;
    @Override default void close() {}
}
