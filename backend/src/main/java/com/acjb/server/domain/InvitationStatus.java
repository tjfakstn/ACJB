package com.acjb.server.domain;

/** 초대 상태(ontology Invitation.status). 만료는 expires_at으로 판정하고, 조회 때 expired로 보여준다 */
public enum InvitationStatus {
    PENDING, ACCEPTED, CANCELED, EXPIRED
}
