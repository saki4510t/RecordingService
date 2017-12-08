package com.serenegiant.service;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.serenegiant.media.MediaRawFileMuxer;
import com.serenegiant.media.MediaReaper;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by saki on 2017/12/08.
 *
 */
public class PostMuxRecService extends AbstractRecorderService {
	private static final boolean DEBUG = true;	// FIXME set false on production
	private static final String TAG = PostMuxRecService.class.getSimpleName();

	public static final int STATE_MUXING = 100;
	
	/** Binder class to access this local service */
	public class LocalBinder extends Binder {
		public PostMuxRecService getService() {
			return PostMuxRecService.this;
		}
	}

	/** binder instance to access this local service */
	private final IBinder mBinder = new LocalBinder();
	private MediaRawFileMuxer mMuxer;

	@Override
	protected IBinder getBinder() {
		return mBinder;
	}
	
	@Override
	protected void internalStart(@NonNull final String outputPath,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {
		
		// FIXME 未実装
	}
	
	@Override
	protected void internalStart(final int accessId,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {
		
		// FIXME 未実装
	}
	
	@Override
	protected void internalStop() {
		// FIXME ここで実際のmuxを開始する
	}
		
	/**
	 * エンコード済みのフレームデータを書き出す
	 * @param reaper
	 * @param byteBuf
	 * @param bufferInfo
	 * @param ptsUs
	 * @throws IOException
	 */
	@Override
	protected void onWriteSampleData(@NonNull final MediaReaper reaper,
		@NonNull final ByteBuffer byteBuf,
		@NonNull final MediaCodec.BufferInfo bufferInfo, final long ptsUs)
			throws IOException {

		// FIXME 未実装
	}
	
}
