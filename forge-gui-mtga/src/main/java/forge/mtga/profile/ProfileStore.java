package forge.mtga.profile;

import java.util.function.Consumer;

/**
 * Where the player's profile lives. The file implementation ({@link FileProfileStore}) is the offline one; a web service
 * implements the same interface against the account's remote record, so nothing above this interface changes.
 */
public interface ProfileStore {
    /** The current profile (a copy: change it through {@link #update}). Created on first use. */
    Profile load();

    /** Apply {@code change} atomically, persist the result and return it. */
    Profile update(Consumer<Profile> change);
}
