package org.wispyr.messenger;

import android.app.backup.BackupAgentHelper;

/**
 * Cloud backup is disabled: login tokens and all local data must never leave the device.
 */
public class BackupAgent extends BackupAgentHelper {

    public static void requestBackup() {
    }
}
