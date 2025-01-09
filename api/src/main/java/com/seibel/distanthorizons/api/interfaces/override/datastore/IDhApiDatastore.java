package com.seibel.distanthorizons.api.interfaces.override.datastore;

import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;

/// IDhApiDatastore provides an interface which Distant Horizons uses to store LOD data.
/// 
/// Traditionally an SQLite database. The motivation behind this API is dedicated database support
/// with the hope that even more data storage methods would also be possible to add in the future.
/// 
/// Implementers should internally handle transient errors, and any quirks of the given storage method.
/// 
/// IDhApiDatastore is a singleton. I've gone back and forth on this a few times,
/// and I've settled on the opinion that session management is out of scope for IDhApiDatastore.
/// My primary justification is not wanting to have a IDhApiDatastoreFactory that inevitably fails to
/// concisely cover all use cases and pollute the API with methods only used by a single concrete implementation.
public interface IDhApiDatastore extends IDhApiOverrideable {
    /**
     * ensureLevel creates the level if it does not already exist.
     *
     * @param uniqueLevelName is a human-readable string that is unique to the level.
     * @return the levelId used by the datastore.
     */
    int ensureLevel(String uniqueLevelName);
}
