/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.wispyr.messenger.secretmedia;

import static java.lang.Math.min;

import android.net.Uri;
import androidx.annotation.Nullable;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;


import org.wispyr.messenger.security.MediaVault;
import org.wispyr.messenger.security.MediaContainer;

import java.io.File;
import java.io.IOException;

@OptIn(markerClass = UnstableApi.class)
public final class EncryptedFileDataSource extends BaseDataSource {

    public static class EncryptedFileDataSourceException extends IOException {

        public EncryptedFileDataSourceException(Throwable cause) {
            super(cause);
        }

    }

    private Uri uri;
    private boolean opened;
    private long bytesRemaining;
    EncryptedFileInputStream fileInputStream;

    public EncryptedFileDataSource() {
        super(/* isNetwork= */ false);
    }

    @Deprecated
    public EncryptedFileDataSource(@Nullable TransferListener listener) {
        this();
        if (listener != null) {
            addTransferListener(listener);
        }
    }


    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        File path = new File(dataSpec.uri.getPath());
        File keyPath = MediaVault.keyFile(path);

        try {
            fileInputStream = new EncryptedFileInputStream(path, keyPath);
            long skipped = 0;
            while (skipped < dataSpec.position) {
                long value = fileInputStream.skip(dataSpec.position - skipped);
                if (value <= 0) {
                    throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
                }
                skipped += value;
            }
            long len = MediaContainer.isContainer(path) ? MediaContainer.plaintextSize(path) : path.length();

            transferInitializing(dataSpec);
            if (dataSpec.position > len) {
                throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            }
            bytesRemaining = len - dataSpec.position;
            if (dataSpec.length != C.LENGTH_UNSET) {
                bytesRemaining = min(bytesRemaining, dataSpec.length);
            }
            opened = true;
            transferStarted(dataSpec);
            return bytesRemaining;
        } catch (Throwable throwable) {
            try {
                if (fileInputStream != null) {
                    fileInputStream.close();
                    fileInputStream = null;
                }
            } catch (IOException ignore) {
            }
            if (throwable instanceof IOException) {
                throw (IOException) throwable;
            }
            throw new EncryptedFileDataSourceException(throwable);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        } else if (bytesRemaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }
        int requested = (int) min((long) length, bytesRemaining);
        int read = fileInputStream.read(buffer, offset, requested);
        if (read == -1) {
            return C.RESULT_END_OF_INPUT;
        }
        bytesRemaining -= read;
        bytesTransferred(read);
        return read;
    }

    @Override
    @Nullable
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        try {
            if (fileInputStream != null) {
                fileInputStream.close();
                fileInputStream = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (opened) {
            opened = false;
            transferEnded();
        }
        fileInputStream = null;
        uri = null;
    }
}
