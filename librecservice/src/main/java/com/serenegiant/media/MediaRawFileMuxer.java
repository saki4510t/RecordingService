/*
 * Copyright (c) 2016-2017.  saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.serenegiant.media;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Environment;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.serenegiant.utils.FileUtils;

import java.io.File;
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
	@NonNull
	private final String mOutputDir;
	private final MediaFormat mConfigFormatVideo;
	private final MediaFormat mConfigFormatAudio;
	/**
	 * 最終出力ファイル名 = 一時ファイルを保存するディレクトリ名
	 * 	= インスタンス生成時の日時文字列
	 */
	@NonNull
	private final String mOutputName;
	private volatile boolean mIsRunning;
	private boolean mReleased;
	private int mLastTrackIndex = -1;
	private MediaRawFileWriter mVideoWriter;
	private MediaRawFileWriter mAudioWriter;
	private MediaRawFileWriter[] mMediaRawFileWriters = new MediaRawFileWriter[2];
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param configFormatVideo
	 * @param configFormatAudio
	 */
	public MediaRawFileMuxer(@NonNull final Context context,
		@NonNull final String outputDir,
		@Nullable final MediaFormat configFormatVideo,
		@Nullable final MediaFormat configFormatAudio) {

		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		mWeakContext = new WeakReference<Context>(context);
		mOutputDir = outputDir;
		mOutputName = FileUtils.getDateTimeString();	// XXX prefix付きも設定できたほうがいいかも
		mConfigFormatVideo = configFormatVideo;
		mConfigFormatAudio = configFormatAudio;
	}
	
	@Override
	public void release() {
		if (DEBUG) Log.v(TAG, "release:");
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
				mMediaRawFileWriters[0] = mMediaRawFileWriters[1] = null;
			}
		}
		if (DEBUG) Log.v(TAG, "release:finished");
	}
	
	@Override
	public void start() {
		if (DEBUG) Log.v(TAG, "start:");
		synchronized (mSync) {
			checkReleased();
			if (mIsRunning) {
				throw new IllegalStateException("already started");
			}
			if (mLastTrackIndex < 0) {
				throw new IllegalStateException("no track added");
			}
			mIsRunning = true;
		}
	}
	
	@Override
	public void stop() {
		if (DEBUG) Log.v(TAG, "stop:");
		synchronized (mSync) {
			mIsRunning = false;
			mLastTrackIndex = 0;
		}
	}
	
	/**
	 * 一時rawファイルからmp4ファイルを生成する
	 */
	public void build() throws IOException {
		if (DEBUG) Log.v(TAG, "build:");
		final String outputPath
			= mOutputDir + (mOutputDir.endsWith("/")
				? mOutputName : "/" + mOutputName) + ".mp4";
		final String tempDir = getTempDir();
		if (DEBUG) Log.v(TAG, "build:tempDir=" + tempDir);
		try {
			final PostMuxBuilder builder
				= new PostMuxBuilder(tempDir, outputPath);
			builder.build();
		} finally {
			delete(new File(tempDir));
		}
		if (DEBUG) Log.v(TAG, "build:finished");
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

		if (DEBUG) Log.v(TAG, "addTrack:" + format);
		checkReleased();
		if (mIsRunning) {
			throw new IllegalStateException("already started");
		}

		final Context context = mWeakContext.get();
		final String tempDir = getTempDir();
		if (DEBUG) Log.v(TAG, "addTrack:tempDir=" + tempDir);
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
								= MediaRawFileWriter.newInstance(context,
									TYPE_VIDEO,
									mConfigFormatVideo != null ? mConfigFormatVideo : format,
									format,
								tempDir);
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
								= MediaRawFileWriter.newInstance(context,
									TYPE_AUDIO,
									mConfigFormatAudio != null ? mConfigFormatAudio : format,
									format,
								tempDir);
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
	
	/**
	 * 一時ファイル用のディレクトリパスを取得
	 * @return
	 */
	private String getTempDir() {
		if (DEBUG) Log.v(TAG, "getTempDir:");
		final Context context = mWeakContext.get();
		try {
			return context.getDir(mOutputName, Context.MODE_PRIVATE).getAbsolutePath();
		} catch (final Exception e) {
			Log.w(TAG, e);
		}
		return new File(
			Environment.getDataDirectory(), mOutputName).getAbsolutePath();
	}

	/**
	 * delete specific file/directory recursively
	 * @param path
	 */
	@SuppressWarnings("ResultOfMethodCallIgnored")
	private static final void delete(@Nullable final File path) {
		if (DEBUG) Log.v(TAG, "delete:" + path);
		if (path != null) {
			try {
				if (path.isDirectory()) {
					final File[] files = path.listFiles();
					final int n = files != null ? files.length : 0;
					for (int i = 0; i < n; i++)
						delete(files[i]);
				}
				path.delete();
			} catch (final Exception e) {
				Log.w(TAG, e);
			}
		}
	}
}
