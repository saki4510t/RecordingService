package com.serenegiant.media;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * Created by saki on 2017/12/08.
 * Rawファイル形式でエンコードデータをファイルに書き出すためのIMuxer実装
 * 実際のファイルへの出力はMediaRawFilerWriterで行う。
 * 実際のmp4ファイルへの出力は別途PostMuxBuilderで行う。
 */
public class MediaRawFileMuxer implements IMuxer {
	private static final boolean DEBUG = true; // FIXME set false on production
	private static final String TAG = MediaRawFileMuxer.class.getSimpleName();

	static final int TYPE_VIDEO = 0;
	static final int TYPE_AUDIO = 1;

	@IntDef({TYPE_VIDEO,
		TYPE_AUDIO,
	})
	@Retention(RetentionPolicy.SOURCE)
	@interface MediaType {}

	private final Object mSync = new Object();
	private final WeakReference<Context> mWeakContext;
	private final String mBasePath;
	private final MediaFormat mConfigFormatVideo;
	private final MediaFormat mConfigFormatAudio;
	private volatile boolean mIsRunning;
	private boolean mReleased;
	private int mLastTrackIndex = -1;
	private MediaRawFileWriter mVideoWriter;
	private MediaRawFileWriter mAudioWriter;
	private MediaRawFileWriter[] mMediaRawFileWriters = new MediaRawFileWriter[2];

	public MediaRawFileMuxer(@NonNull final Context context,
		@NonNull final String basePath,
		@Nullable final MediaFormat configFormatVideo,
		@Nullable final MediaFormat configFormatAudio) {

		mWeakContext = new WeakReference<Context>(context);
		mBasePath = basePath;
		mConfigFormatVideo = configFormatVideo;
		mConfigFormatAudio = configFormatAudio;
	}
	
	@Override
	public void release() {
		synchronized (mSync) {
			if (!mReleased) {
				mReleased = true;
				if (mVideoWriter != null) {
					mVideoWriter.release();
					mVideoWriter = null;
				}
				if (mAudioWriter != null) {
					mAudioWriter.release();
					mAudioWriter = null;
				}
			}
		}
	}
	
	@Override
	public void start() {
		synchronized (mSync) {
			checkReleased();
			if (mIsRunning) {
				throw new IllegalStateException("already started");
			}
			mIsRunning = true;
		}
	}
	
	@Override
	public void stop() {
		synchronized (mSync) {
			mIsRunning = false;
		}
	}
	
	/**
	 * 一時rawファイルからmp4ファイルを生成する
	 */
	public void build() {
		/// FIXME 未実装
	}
	
	@Override
	public boolean isStarted() {
		synchronized (mSync) {
			return !mReleased && mIsRunning;
		}
	}

	@Override
	public int addTrack(@NonNull final MediaFormat format)
		throws IllegalArgumentException, IllegalStateException {

		checkReleased();
		if (mIsRunning) {
			throw new IllegalStateException("already started");
		}

		final String mime = format.containsKey(MediaFormat.KEY_MIME)
			? format.getString(MediaFormat.KEY_MIME) : null;
		if (!TextUtils.isEmpty(mime)) {
			synchronized (mSync) {
				final int trackIndex = mLastTrackIndex + 1;
				if (mime.startsWith("video/")) {
					// 映像エンコーダーの時
					if (mVideoWriter == null) {
						try {
							mMediaRawFileWriters[trackIndex] = mVideoWriter
								= MediaRawFileWriter.newInstance(mWeakContext.get(),
									TYPE_VIDEO,
									mConfigFormatVideo != null ? mConfigFormatVideo : format,
									format,
									mBasePath);
							mLastTrackIndex = trackIndex;
							return trackIndex;
						} catch (final IOException e) {
							throw new IllegalArgumentException(e);
						}
					} else {
						throw new IllegalArgumentException("Video track is already added");
					}
				} else if (mime.startsWith("audio/")) {
					// 音声エンコーダーの時
					if (mAudioWriter == null) {
						try {
							mMediaRawFileWriters[trackIndex] = mAudioWriter
								= MediaRawFileWriter.newInstance(mWeakContext.get(),
									TYPE_AUDIO,
									mConfigFormatAudio != null ? mConfigFormatAudio : format,
									format,
									mBasePath);
							mLastTrackIndex = trackIndex;
							return trackIndex;
						} catch (final IOException e) {
							throw new IllegalArgumentException(e);
						}
					} else {
						throw new IllegalArgumentException("Audio track is already added");
					}
				} else {
					throw new IllegalArgumentException("Unexpected mime type=" + mime);
				}
			}
		} else {
			throw new IllegalArgumentException("Mime is null");
		}
	}
	
	@Override
	public void writeSampleData(final int trackIndex,
		@NonNull final ByteBuffer buffer,
		@NonNull final MediaCodec.BufferInfo info) {
	
		checkReleased();
		if (!mIsRunning) {
			throw new IllegalStateException("Can't write, muxer is not started");
		}
		if (trackIndex < 0 || trackIndex > mLastTrackIndex) {
			throw new IllegalArgumentException("trackIndex is invalid");
		}
		if ((info.size < 0) || (info.offset < 0)
			|| ((info.offset + info.size) > buffer.capacity())
			|| (info.presentationTimeUs < 0) )  {
			
			throw new IllegalArgumentException("bufferInfo must specify a" +
				" valid buffer offset, size and presentation time");
		}

		final MediaRawFileWriter writer;
		synchronized (mSync) {
			writer = mMediaRawFileWriters[trackIndex];
		}
		if (writer != null) {
			try {
				writer.writeSampleData(buffer, info);
			} catch (final IOException e) {
				Log.w(TAG, e);
			}
		}
	}
	
	private void checkReleased() throws IllegalStateException {
		synchronized (mSync) {
			if (mReleased) {
				throw new IllegalStateException("already released");
			}
		}
	}
}
