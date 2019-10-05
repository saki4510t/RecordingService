package com.serenegiant.service;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.serenegiant.media.MediaReaper;

import java.io.IOException;
import java.nio.ByteBuffer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

/**
 * 録画機能をサービス側で実行するだけのサービス
 */
public class SimpleRecorderService extends AbstractRecorderService {
	private static final boolean DEBUG = false;	// FIXME set false on production
	private static final String TAG = SimpleRecorderService.class.getSimpleName();

	/** Binder class to access this local service */
	public class LocalBinder extends Binder {
		public SimpleRecorderService getService() {
			return SimpleRecorderService.this;
		}
	}

	/** binder instance to access this local service */
	private final IBinder mBinder = new LocalBinder();

	@Override
	protected IBinder getBinder() {
		return mBinder;
	}

	@Override
	protected void internalStart(
		@NonNull final String outputDir,
		@NonNull final String name,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {

		if (DEBUG) Log.v(TAG, "internalStart:");
	}

	@Override
	protected void internalStart(
		@NonNull final DocumentFile outputDir,
		@NonNull final String name,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {

		if (DEBUG) Log.v(TAG, "internalStart:");
	}

	@Override
	protected void onWriteSampleData(
		@NonNull final MediaReaper reaper,
		@NonNull final ByteBuffer byteBuf,
		@NonNull final MediaCodec.BufferInfo bufferInfo,
		final long ptsUs) throws IOException {

		if (DEBUG) Log.v(TAG, "onWriteSampleData:");
	}

	@Override
	protected void internalStop() {
		if (DEBUG) Log.v(TAG, "internalStop:");
	}
}
