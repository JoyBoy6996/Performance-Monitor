package com.framescope.monitor.shell;

import android.os.Bundle;

interface IShizukuService {
    void destroy() = 16777114;
    void exit() = 1;
    int getUid() = 2;
    Bundle execute(String command, long timeoutMs) = 3;
}
