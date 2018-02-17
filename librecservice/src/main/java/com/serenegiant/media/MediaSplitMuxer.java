package com.serenegiant.media;
/*
 * Copyright (c) 2016-2018.  saki t_saki@serenegiant.com
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

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.provider.DocumentFile;
import android.util.Log;

import com.serenegiant.utils.BuildCheck;
import com.serenegiant.utils.UriHelper;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 指定したファイルサイズ以下になるように自動分割してMP4へ出力するためのIMuxer実装
 *
 */
public class MediaSplitMuxer implements IMuxer {
	private static final boolean DEBUG = true; // FIXME set false on production
	private static final String TAG = MediaSplitMuxer.class.getSimpleName();

	public static boolean sUseMediaMuxer = true;
	
	private static final int INI_POOL_NUM = 4;
	private static final int MAX_POOL_NUM = 1000;
	private static final long DEFAULT_SPLIT_SIZE = 4000000000L;
	
	private final Object mSync = new Object();
	private final WeakReference<Context> mWeakContext;
	/**
	 * MediaCodecの動画エンコーダーの設定
	 * 最終のmp4ファイル出力時に必要なため保持しておく
	 */
	private MediaFormat mConfigFormatVideo;
	/**
	 * MediaCodecの音声エンコーダーの設定
	 * 最終のmp4ファイル出力時に必要なため保持しておく
	 */
	private MediaFormat mConfigFormatAudio;
	/**
	 * mp4ファイルの出力ディレクトリ(絶対パス文字列)
	 */
	@Nullable
	private final String mOutputDir;
	/**
	 * mp4ファイルの出力ディレクトリ(DocumentFile)
	 */
	@Nullable
	private final DocumentFile mOutputDoc;
	/**
	 * 最終出力ファイル名 = 一時ファイルを保存するディレクトリ名
	 * 	= インスタンス生成時の日時文字列
	 */
	@NonNull
	private final String mOutputName;
	private final IMediaQueue mQueue;
	private final long mSplitSize;
	private DocumentFile mCurrent;
	/** 実行中フラグ */
	private volatile boolean mIsRunning;
	private volatile boolean mRequestStop;
	private boolean mReleased;
	private int mLastTrackIndex = -1;
	private MuxTask mMuxTask;
	
	/**
	 * コンストラクタ
	 * キューはMemMediaQueueを使う
	 * @param context
	 * @param outputDir 最終出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param limitSize 出力ファイルサイズ
	 */
	public MediaSplitMuxer(@NonNull final Context context,
		@NonNull final String outputDir, @NonNull final String name,
		final long limitSize) {
		
		this(context, new MemMediaQueue(INI_POOL_NUM, MAX_POOL_NUM),
			outputDir, name, limitSize);
	}
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param queue バッファリング用IMediaQueue
	 * @param outputDir 最終出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param splitSize 出力ファイルサイズ, 0以下ならデフォルト値
	 */
	public MediaSplitMuxer(@NonNull final Context context,
		@NonNull final IMediaQueue queue,
		@NonNull final String outputDir, @NonNull final String name,
		final long splitSize) {

		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		mWeakContext = new WeakReference<Context>(context);
		mQueue = queue;
		mOutputDir = outputDir;
		mOutputDoc = null;
		mOutputName = name;
		mSplitSize = splitSize <= 0 ? DEFAULT_SPLIT_SIZE : splitSize;
	}
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param outputDir 最終出力ディレクトリ
	 * @param name 出つ力ファイル名(拡張子なし)
	 * @param splitSize 出力ファイルサイズ, 0以下ならデフォルト値
	 */
	public MediaSplitMuxer(@NonNull final Context context,
		@NonNull final DocumentFile outputDir, @NonNull final String name,
		final long splitSize) {

		this(context, new MemMediaQueue(INI_POOL_NUM, MAX_POOL_NUM),
			outputDir, name, splitSize);
	}

	/**
	 * コンストラクタ
	 * @param context
	 * @param queue バッファリング用IMediaQueue
	 * @param outputDir 最終出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param splitSize 出力ファイルサイズ, 0以下ならデフォルト値
	 */
	public MediaSplitMuxer(@NonNull final Context context,
		@NonNull final IMediaQueue queue,
		@NonNull final DocumentFile outputDir, @NonNull final String name,
		final long splitSize) {

		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		mWeakContext = new WeakReference<Context>(context);
		mQueue = queue;
		mOutputDir = null;
		mOutputDoc = outputDir;
		mOutputName = name;
		mSplitSize = splitSize <= 0 ? DEFAULT_SPLIT_SIZE : splitSize;
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			release();
		} finally {
			super.finalize();
		}
	}
	
	/**
	 * 関連するリソースを破棄する
	 */
	@Override
	public void release() {
		synchronized (mSync) {
			if (!mReleased) {
				mIsRunning = false;
				mReleased = true;
				if (DEBUG) Log.v(TAG, "release:");
				stop();
				if (DEBUG) Log.v(TAG, "release:finished");
			}
		}
	}

	/**
	 * 実行中かどうかを取得
	 * @return
	 */
	@Override
	public boolean isStarted() {
		synchronized (mSync) {
			return !mReleased && mIsRunning;
		}
	}

	@Override
	public synchronized void start() throws IllegalStateException {
		if (DEBUG) Log.v(TAG, "start:");
		if (!mReleased && !mIsRunning) {
			if ((mConfigFormatVideo != null)
				|| (mConfigFormatAudio != null)) {

				mIsRunning = true;
				mRequestStop = false;
				mMuxTask = new MuxTask();
				new Thread(mMuxTask, "MuxTask").start();
			} else {
				throw new IllegalStateException("no added track");
			}
		} else {
			throw new IllegalStateException("already released or started");
		}
		if (DEBUG) Log.v(TAG, "start:finished");
	}
	
	/**
	 * 終了指示を送る
	 */
	@Override
	public synchronized void stop() {
		if (DEBUG) Log.v(TAG, "stop:");
		synchronized (mSync) {
			mRequestStop = true;
			mMuxTask = null;
			mLastTrackIndex = 0;
		}
		if (DEBUG) Log.v(TAG, "stop:finished");
	}
	
	@Override
	public int addTrack(@NonNull final MediaFormat format)
		throws IllegalArgumentException, IllegalStateException {

		if (DEBUG) Log.v(TAG, "addTrack:" + format);
		int result = mLastTrackIndex;
		switch (mLastTrackIndex) {
		case 0:
		case 1:
			if (format.containsKey(MediaFormat.KEY_MIME)) {
				final String mime = format.getString(MediaFormat.KEY_MIME);
				if (mime.startsWith("video/")) {
					if (mConfigFormatVideo == null) {
						mConfigFormatVideo = format;
					} else {
						throw new IllegalArgumentException("video format is already set!");
					}
				} else if (mime.startsWith("audio/")) {
					if (mConfigFormatAudio == null) {
						mConfigFormatAudio = format;
					} else {
						throw new IllegalArgumentException("audio format is already set!");
					}
				}
			} else {
				throw new IllegalArgumentException("has no mime type");
			}
			mLastTrackIndex++;
			break;
		default:
			throw new IllegalArgumentException();
		}
		if (DEBUG) Log.v(TAG, "addTrack:finished,result=" + result);
		return result;
	}
	
	@Override
	public void writeSampleData(final int trackIx,
		@NonNull final ByteBuffer buffer,
		@NonNull final MediaCodec.BufferInfo info) {
	
		if (DEBUG) Log.v(TAG, "writeSampleData:");
		if (mIsRunning && !mRequestStop) {
			final IRecycleBuffer buf = mQueue.obtain();
			if (buf instanceof RecycleMediaData) {
				((RecycleMediaData) buf).trackIx = trackIx;
				((RecycleMediaData) buf).set(buffer, info);
			}
		}
	}

	private final class MuxTask implements Runnable {
		@Override
		public void run() {
			if (DEBUG) Log.v(TAG, "MuxTask#run:");
			final Context context = getContext();
			if (context != null) {
				IMuxer muxer = null;
				final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
				int ix = 0, cnt = 0;
				for ( ; mIsRunning ; ) {
					if (muxer == null) {
						if (DEBUG) Log.v(TAG, "MuxTask#run:create muxer");
						// muxerが無ければ生成する
						try {
							if (mOutputDoc != null) {
								mCurrent = createOutputDoc(mOutputDoc, mOutputName, ix);
							} else if (mOutputDir != null) {
								mCurrent = createOutputDoc(mOutputDir, mOutputName, ix);
							} else {
								Log.w(TAG, "output dir not set");
								break;
							}
							muxer = createMuxer(context, mCurrent);
							if (mConfigFormatVideo != null) {
								muxer.addTrack(mConfigFormatVideo);
							}
							if (mConfigFormatAudio != null) {
								muxer.addTrack(mConfigFormatAudio);
							}
							muxer.start();
							ix++;
							cnt = 0;
						} catch (final IOException e) {
							Log.w(TAG, e);
							break;
						}
					}
					if (muxer != null) {
						if (DEBUG) Log.v(TAG, "MuxTask#run:muxing");
						for ( ; mIsRunning ; ) {
							try {
								final IRecycleBuffer buf = mQueue.poll(10, TimeUnit.MILLISECONDS);
								if (buf instanceof RecycleMediaData) {
									((RecycleMediaData) buf).get(info);
									muxer.writeSampleData(((RecycleMediaData) buf).trackIx,
										((RecycleMediaData) buf).mBuffer, info);
								} else if (mRequestStop) {
									mIsRunning = false;
									break;
								}
								if ((((++cnt) % 1000) == 0)
									&& (mCurrent.length() >= mSplitSize)) {
									// ファイルサイズが指定値を超えた
									break;
								}
							} catch (final InterruptedException e) {
								mIsRunning = false;
								break;
							}
						}
						muxer.stop();
						muxer.release();
						muxer = null;
						if (DEBUG) Log.v(TAG, "MuxTask#run:muxing finished");
					}
				}
			}
			mIsRunning = false;
			if (DEBUG) Log.v(TAG, "MuxTask#run:finished");
		}
		
	}
	
	protected Context getContext() {
		return mWeakContext.get();
	}

	private static DocumentFile createOutputDoc(
		@NonNull final String path,
		@NonNull final String name, final int segment) {
		
		if (DEBUG) Log.v(TAG, "createOutputDoc:path=" + path);
		final File _dir = new File(path);
		final File dir = _dir.isDirectory() ? _dir : _dir.getParentFile();
		return DocumentFile.fromFile(
			new File(dir,
			String.format(Locale.US, "%s ps%d.mp4", name, segment + 1)));
	}

	private static DocumentFile createOutputDoc(
		@NonNull final DocumentFile path,
		@NonNull final String name, final int segment) {
		
		if (DEBUG) Log.v(TAG, "createOutputDoc:path=" + path.getUri());
		final DocumentFile dir = path.isDirectory() ? path : path.getParentFile();
		return dir.createFile(null,
			String.format(Locale.US, "%s ps%d.mp4", name, segment + 1));
	}
	
	@SuppressLint("NewApi")
	private static IMuxer createMuxer(@NonNull final Context context,
		@NonNull final DocumentFile file) throws IOException {

		if (DEBUG) Log.v(TAG, "createMuxer:file=" + file.getUri());
		IMuxer result = null;
		if (sUseMediaMuxer) {
			if (BuildCheck.isOreo()) {
				result = new MediaMuxerWrapper(context.getContentResolver()
					.openFileDescriptor(file.getUri(), "rw").getFileDescriptor(),
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
			} else {
				final String path = UriHelper.getPath(context, file.getUri());
				final File f = new File(UriHelper.getPath(context, file.getUri()));
				if (/*!f.exists() &&*/ f.canWrite()) {
					// 書き込めるファイルパスを取得できればそれを使う
					result = new MediaMuxerWrapper(path,
						MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
				} else {
					Log.w(TAG, "cant't write to the file, try to use VideoMuxer instead");
				}
			}
		}
		if (result == null) {
			result = new VideoMuxer(context.getContentResolver()
				.openFileDescriptor(file.getUri(), "rw").getFd());
		}
		if (DEBUG) Log.v(TAG, "createMuxer:finished," + result);
		return result;
	}
}
