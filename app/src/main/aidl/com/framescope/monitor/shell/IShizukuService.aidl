package com.framescope.monitor.shell;

import android.os.Bundle;

interface IShizukuService {
    void destroy() = 16777114;
    void exit() = 1;
    int getUid() = 2;
    Bundle execute(String executionId, String command, long timeoutMs) = 3;
    boolean cancel(String executionId) = 4;
    Bundle openSession() = 5;
    Bundle execInSession(String sessionId, String command, long timeoutMs) = 6;
    Bundle closeSession(String sessionId) = 7;
}
