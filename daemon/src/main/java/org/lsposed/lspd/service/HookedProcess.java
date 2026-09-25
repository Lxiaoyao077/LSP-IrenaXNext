/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.lsposed.lspd.service;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

/**
 * One process currently hooked by a module, in the shape the API 102 {@code IXposedService} wire
 * carries it.
 *
 * <p>The daemon writes these out by hand rather than through an AIDL stub, because the pinned
 * {@code IXposedService} AIDL in this tree predates API 102. The field order below therefore <b>has
 * to</b> match {@code HookedProcess.aidl} upstream, since the module app reads it with the compiled
 * {@code HookedProcess.CREATOR}.
 * </p>
 */
public final class HookedProcess implements Parcelable {

    /** The target is running the currently installed module code. */
    public static final int TARGET_STATE_UP_TO_DATE = 0;
    /** The target is still running old module code and may be hot reloaded. */
    public static final int TARGET_STATE_STALE = 1;
    /** The target is currently being hot reloaded. */
    public static final int TARGET_STATE_RELOADING = 2;
    /** The target's last hot reload attempt failed. */
    public static final int TARGET_STATE_FAILED = 3;

    /** Opaque, process-scoped token. Modules must only hand it back to the service. */
    public final long targetId;
    /** The process uid, for display and diagnostics. */
    public final int uid;
    /** The process id, for display and diagnostics. Never an identity. */
    public final int pid;
    /** The Android process name, for display and diagnostics. */
    public final String processName;
    /** One of {@code TARGET_STATE_*}. */
    public final int state;
    /** Version code of the module code loaded in that process. Diagnostic only. */
    public final long loadedVersionCode;

    public HookedProcess(long targetId, int uid, int pid, @NonNull String processName,
                         int state, long loadedVersionCode) {
        this.targetId = targetId;
        this.uid = uid;
        this.pid = pid;
        this.processName = processName;
        this.state = state;
        this.loadedVersionCode = loadedVersionCode;
    }

    private HookedProcess(@NonNull Parcel in) {
        targetId = in.readLong();
        uid = in.readInt();
        pid = in.readInt();
        processName = in.readString();
        state = in.readInt();
        loadedVersionCode = in.readLong();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(targetId);
        dest.writeInt(uid);
        dest.writeInt(pid);
        dest.writeString(processName);
        dest.writeInt(state);
        dest.writeLong(loadedVersionCode);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<HookedProcess> CREATOR = new Creator<HookedProcess>() {
        @Override
        public HookedProcess createFromParcel(Parcel in) {
            return new HookedProcess(in);
        }

        @Override
        public HookedProcess[] newArray(int size) {
            return new HookedProcess[size];
        }
    };
}
