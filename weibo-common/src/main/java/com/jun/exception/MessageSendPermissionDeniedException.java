package com.jun.exception;

public class MessageSendPermissionDeniedException extends BaseException {
    public MessageSendPermissionDeniedException() {
    }

    public MessageSendPermissionDeniedException(String message) {
        super(message);
    }
}
