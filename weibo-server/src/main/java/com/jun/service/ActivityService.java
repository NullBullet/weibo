package com.jun.service;

public interface ActivityService {
    void handleForwardActivity(Long originalStatusId, Long currentId, Long forwardStatusId);
}
