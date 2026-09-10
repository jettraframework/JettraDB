package com.jettra.store.engine.exception;

import java.util.UUID;

/**
 * Thrown when any operation attempts to physically delete a user identity in JettraDB.
 * User identities in system_db are permanent, historical entities.
 * Physical deletion (DROP USER / DELETE) is strictly prohibited across all ingestion channels (Web UI, Drivers, Shell).
 */
public class UnsupportedUserDeletionException extends UnsupportedOperationException {

    private static final long serialVersionUID = 1L;

    private final String username;
    private final UUID userId;
    private final String channelSource;
    private final String errorCode;

    public UnsupportedUserDeletionException(String username, UUID userId, String channelSource) {
        super(String.format(
            "Identity preservation policy violation: Physical deletion of user identity '%s'%s is strictly prohibited in JettraDB [%s]. " +
            "User identities are historical and permanent records in system_db. Access should be revoked by de-associating databases or setting account status to inactive.",
            username != null && !username.isBlank() ? username : "unknown",
            userId != null ? " (ID: " + userId + ")" : "",
            channelSource != null && !channelSource.isBlank() ? channelSource : "ENGINE"
        ));
        this.username = username != null ? username : "";
        this.userId = userId;
        this.channelSource = channelSource != null ? channelSource : "ENGINE";
        this.errorCode = "USER_DELETION_PROHIBITED";
    }

    public UnsupportedUserDeletionException(String username, String channelSource) {
        this(username, null, channelSource);
    }

    public UnsupportedUserDeletionException(String username) {
        this(username, null, "ENGINE");
    }

    public String getUsername() {
        return username;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getChannelSource() {
        return channelSource;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
