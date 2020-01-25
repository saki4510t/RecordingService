package com.serenegiant.service;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Binder;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import com.serenegiant.media.IMuxer;
import com.serenegiant.media.MediaMuxerWrapper;
import com.serenegiant.media.MediaReaper;
import com.serenegiant.system.BuildCheck;
import com.serenegiant.utils.UriHelper;

import java.io.File;
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
	private String mOutputPath;
	private IMuxer mMuxer;
	private int mVideoTrackIx = -1;
	private int mAudioTrackIx = -1;

	@Override
	protected IBinder getBinder() {
		return mBinder;
	}

	/**
	 * #startの実態, mSyncをロックして呼ばれる
	 * @param outputDir 出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param videoFormat
	 * @param audioFormat
	 * @throws IOException
	 */
	@SuppressLint("InlinedApi")
	@Override
	protected void internalStart(
		@NonNull final String outputDir,
		@NonNull final String name,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {

		if (DEBUG) Log.v(TAG, "internalStart:");
		if (!TextUtils.isEmpty(outputDir) && !TextUtils.isEmpty(name)) {
			final String outputPath
				= outputDir + (outputDir.endsWith("/")
					? name : "/" + name) + ".mp4";

			mMuxer = new MediaMuxerWrapper(
				outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			mVideoTrackIx = videoFormat != null ? mMuxer.addTrack(videoFormat) : -1;
			mAudioTrackIx = audioFormat != null ? mMuxer.addTrack(audioFormat) : -1;
			mMuxer.start();
		} else {
			throw new IOException("invalid output dir or name");
		}
	}

	/**
	 * #startの実態, mSyncをロックして呼ばれる
	 * @param outputDir 出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param videoFormat
	 * @param audioFormat
	 * @throws IOException
	 */
	@SuppressLint("NewApi")
	@Override
	protected void internalStart(
		@NonNull final DocumentFile outputDir,
		@NonNull final String name,
		@Nullable final MediaFormat videoFormat,
		@Nullable final MediaFormat audioFormat) throws IOException {

		if (DEBUG) Log.v(TAG, "internalStart:");
		final DocumentFile output = outputDir.createFile("*/*", name + ".mp4");
		IMuxer muxer = null;
		if (BuildCheck.isOreo()) {
			muxer = new MediaMuxerWrapper(getContentResolver()
				.openFileDescriptor(output.getUri(), "rw").getFileDescriptor(),
				MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
		} else {
			final String path = UriHelper.getPath(this, output.getUri());
			final File f = new File(UriHelper.getPath(this, output.getUri()));
			if (/*!f.exists() &&*/ f.canWrite()) {
				// 書き込めるファイルパスを取得できればそれを使う
				muxer = new MediaMuxerWrapper(path,
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			} else {
				Log.w(TAG, "cant't write to the file, try to use VideoMuxer instead");
			}
		}
		if (muxer == null) {
			throw new IllegalArgumentException();
		}
		mMuxer = muxer;
		mVideoTrackIx = videoFormat != null ? muxer.addTrack(videoFormat) : -1;
		mAudioTrackIx = audioFormat != null ? muxer.addTrack(audioFormat) : -1;
		mMuxer.start();
		synchronized (mSync) {
			mSync.notifyAll();
		}
	}

	@Override
	protected void internalStop() {
		if (DEBUG) Log.v(TAG, "internalStop:");
		if (mMuxer != null) {
			final IMuxer muxer = mMuxer;
			mMuxer = null;
			try {
				muxer.stop();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
			try {
				muxer.release();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
		}
		if (getState() == STATE_RECORDING) {
			setState(STATE_INITIALIZED);
			if (!TextUtils.isEmpty(mOutputPath)) {
				final String path = mOutputPath;
				mOutputPath = null;
				scanFile(path);
			}
		}
	}

	@Override
	protected void onWriteSampleData(
		@NonNull final MediaReaper reaper,
		@NonNull final ByteBuffer buffer,
		@NonNull final MediaCodec.BufferInfo info,
		final long ptsUs) {

//		if (DEBUG) Log.v(TAG, "onWriteSampleData:");
		IMuxer muxer;
		synchronized (mSync) {
			if (mMuxer == null) {
				for (int i = 0; isRecording() && (i < 100); i++) {
					if (mMuxer == null) {
						try {
							mSync.wait(10);
						} catch (final InterruptedException e) {
							break;
						}
					} else {
						break;
					}
				}
			}
			muxer = mMuxer;
		}
		if (muxer != null) {
			switch (reaper.reaperType()) {
			case MediaReaper.REAPER_VIDEO:
				muxer.writeSampleData(mVideoTrackIx, buffer, info);
				break;
			case MediaReaper.REAPER_AUDIO:
				muxer.writeSampleData(mAudioTrackIx, buffer, info);
				break;
			default:
				if (DEBUG) Log.v(TAG, "onWriteSampleData:unexpected reaper type");
				break;
			}
		} else {
			if (DEBUG) Log.v(TAG, "onWriteSampleData:muxer is not set yet, " +
				"state=" + getState() + ",reaperType=" + reaper.reaperType());
		}
	}

}
