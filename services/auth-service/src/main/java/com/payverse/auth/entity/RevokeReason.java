package com.payverse.auth.entity;

/** Why a refresh token stopped being usable. */
public enum RevokeReason {
    ROTATED, LOGOUT, REUSE_DETECTED, DEVICE_REVOKED
}
