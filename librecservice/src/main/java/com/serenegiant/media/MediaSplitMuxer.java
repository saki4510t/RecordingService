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
 * 指定したファイルサイズになるように自動分割してMP4へ出力するためのIMuxer実装
 * Iフレームが来たときにしか出力ファイルを切り替えることができないため
 * 確実に指定ファイルサイズ以下になるわけではないので、多少の余裕をもって
 * 出力ファイルサイズをセットすること
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
	private IMuxer mMuxer;
	private MuxTask mMuxTask;
	
	/**
	 * コンストラクタ
	 * キューはMemMediaQueueを使う
	 * @param context
	 * @param outputDir 最終出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 */
	public MediaSplitMuxer(@NonNull final Context context,
		@NonNull final String outputDir, @NonNull final String name,
		final long splitSize) throws IOException {
		
		this(context, new MemMediaQueue(INI_POOL_NUM, MAX_POOL_NUM),
			outputDir, name, splitSize);
	}
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param queue バッファリング用IMediaQueue
	 * @param outputDir 最終出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 */
	public MediaSplitMuxer(@NonNull final Context context,
		@NonNull final IMediaQueue queue,
		@NonNull final String outputDir, @NonNull final String name,
		final long splitSize) throws IOException {

		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		mWeakContext = new WeakReference<Context>(context);
		mQueue = queue;
		mOutputDir = outputDir;
		mOutputDoc = null;
		mOutputName = name;
		mSplitSize = splitSize <= 0 ? DEFAULT_SPLIT_SIZE : splitSize;
		mMuxer = createMuxer(0);
	}
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param outputDir 最終出力ディレクトリ
	 * @param name 出つ力ファイル名(拡張子なし)
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 */
	public MediaSplitMuxer(@NonNull final Context context,
		@NonNull final DocumentFile outputDir, @NonNull final String name,
		final long splitSize) throws IOException {

		this(context, new MemMediaQueue(INI_POOL_NUM, MAX_POOL_NUM),
			outputDir, name, splitSize);
	}

	/**
	 * コンストラクタ
	 * @param context
	 * @param queue バッファリング用IMediaQueue
	 * @param outputDir 最終出力ディレクトリ
	 * @param name 出力ファイル名(拡張子なし)
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 */
	public MediaSplitMuxer(@NonNull final Context context,
		@NonNull final IMediaQueue queue,
		@NonNull final DocumentFile outputDir, @NonNull final String name,
		final long splitSize) throws IOException {

		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		mWeakContext = new WeakReference<Context>(context);
		mQueue = queue;
		mOutputDir = null;
		mOutputDoc = outputDir;
		mOutputName = name;
		mSplitSize = splitSize <= 0 ? DEFAULT_SPLIT_SIZE : splitSize;
		mMuxer = createMuxer(0);
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
			mLastTrackIndex = -1;
		}
		if (DEBUG) Log.v(TAG, "stop:finished");
	}
	
	/**
	 * 映像/音声トラックを追加
	 * それぞれ最大で１つずつしか追加できない
 	 * @param format
	 * @return
	 * @throws IllegalArgumentException
	 * @throws IllegalStateException
	 */
	@Override
	public int addTrack(@NonNull final MediaFormat format)
		throws IllegalArgumentException, IllegalStateException {

		if (DEBUG) Log.v(TAG, "addTrack:" + format);
		int result = mLastTrackIndex + 1;
		switch (result) {
		case 0:
		case 1:
			if (format.containsKey(MediaFormat.KEY_MIME)) {
				final String mime = format.getString(MediaFormat.KEY_MIME);
				if (mime.startsWith("video/")) {
					if (mConfigFormatVideo == null) {
						result = mMuxer.addTrack(format);
						mConfigFormatVideo = format;
					} else {
						throw new IllegalArgumentException("video format is already set!");
					}
				} else if (mime.startsWith("audio/")) {
					if (mConfigFormatAudio == null) {
						result = mMuxer.addTrack(format);
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
	
	private int cnt;
	@Override
	public void writeSampleData(final int trackIx,
		@NonNull final ByteBuffer buffer,
		@NonNull final MediaCodec.BufferInfo info) {
	
		if (!mRequestStop && (trackIx <= mLastTrackIndex)) {
			final IRecycleBuffer buf = mQueue.obtain();
			if (buf instanceof RecycleMediaData) {
				((RecycleMediaData) buf).trackIx = trackIx;
				((RecycleMediaData) buf).set(buffer, info);
				mQueue.queueFrame(buf);
				if (DEBUG && (((++cnt) % 100) == 0)) {
					Log.v(TAG, String.format("writeSampleData:size=%d,offset=%d",
						info.size, info.offset));
				}
			} else if (DEBUG && (buf != null)) {
				Log.w(TAG, "unexpected buffer class");
			}
		} else {
			if (DEBUG) Log.w(TAG, "not ready!");
		}
	}

	protected Context getContext() {
		return mWeakContext.get();
	}
	
	private final class MuxTask implements Runnable {

		@Override
		public void run() {
			if (DEBUG) Log.v(TAG, "MuxTask#run:");
			final Context context = getContext();
			if (context != null) {
				IMuxer muxer = mMuxer;
				mMuxer = null;
				try {
					if (muxer == null) {
						try {
							muxer = setupMuxer(0);
						} catch (final IOException e) {
							Log.w(TAG, e);
							return;
						}
					} else {
						muxer.start();
					}
					final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
					boolean mRequestChangeFile = false;
					int segment = 1, cnt = 0;
					if (DEBUG) Log.v(TAG, "MuxTask#run:muxing");
					for ( ; mIsRunning ; ) {
						// バッファキューからエンコード済みデータを取得する
						final IRecycleBuffer buf;
						try {
							buf = mQueue.poll(10, TimeUnit.MILLISECONDS);
						} catch (final InterruptedException e) {
							if (DEBUG) Log.v(TAG, "interrupted");
							mIsRunning = false;
							break;
						}
						if (buf instanceof RecycleMediaData) {
							((RecycleMediaData) buf).get(info);
							if (mRequestChangeFile
								&& (info.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME)) {
	
								// ファイルサイズが超えていてIフレームが来たときにファイルを変更する
								mRequestChangeFile = false;
								try {
									muxer = restartMuxer(muxer, segment++);
								} catch (final IOException e) {
									break;
								}
							}
							muxer.writeSampleData(((RecycleMediaData) buf).trackIx,
								((RecycleMediaData) buf).mBuffer, info);
							// 再利用のためにバッファを返す
							mQueue.recycle(buf);
						} else if (mRequestStop) {
							mIsRunning = false;
							break;
						}
						if (!mRequestChangeFile
							&& (((++cnt) % 1000) == 0)
							&& (mCurrent.length() >= mSplitSize)) {
							// ファイルサイズが指定値を超えた
							// ファイルサイズのチェック時はフラグを立てるだけにして
							// 次のIフレームが来たときに切り替えないと次のファイルの先頭が
							// 正しく再生できなくなる
							if (DEBUG) Log.v(TAG, "exceeds file size limit");
							mRequestChangeFile = true;
						}
					} // end of for
				} catch (final Exception e) {
					Log.w(TAG, e);
				}
				if (muxer != null) {
					try {
						muxer.stop();
						muxer.release();
					} catch (final Exception e) {
						Log.w(TAG, e);
					}
				}
			}
			mIsRunning = false;
			if (DEBUG) Log.v(TAG, "MuxTask#run:finished");
		}
		
	}
	
	/**
	 * IMuxerの切り替え処理
	 * 内部で#setupMuxerを呼び出す
	 * @param muxer 今まで使っていたIMuxer
	 * @param segment 次のセグメント番号
	 * @return 次のファイル出力用のIMuxer
	 * @throws IOException
	 */
	protected IMuxer restartMuxer(@NonNull final IMuxer muxer, final int segment)
		throws IOException {

		if (DEBUG) Log.v(TAG, "restartMuxer:");
		try {
			muxer.stop();
			muxer.release();
		} catch (final Exception e) {
			throw new IOException(e);
		}
		// 次のIMuxerに切り替える
		return setupMuxer(segment);
	}
	
	/**
	 * createMuxerを呼び出してIMuxerを生成してから
	 * addTrack, startを呼び出す
	 * @param segment
	 * @return
	 * @throws IOException
	 */
	protected IMuxer setupMuxer(final int segment) throws IOException {
		final IMuxer result = createMuxer(segment);
		if (mConfigFormatVideo != null) {
			final int trackIx = result.addTrack(mConfigFormatVideo);
			if (DEBUG) Log.v(TAG, "add video track," + trackIx);
		}
		if (mConfigFormatAudio != null) {
			final int trackIx = result.addTrack(mConfigFormatAudio);
			if (DEBUG) Log.v(TAG, "add audio track," + trackIx);
		}
		result.start();
		return result;
	}
	
	/**
	 * IMuxerを生成する, addTrack, startは呼ばない
	 * @param segment 次のセグメント番号
	 * @return
	 * @throws IOException
	 */
	protected IMuxer createMuxer(final int segment) throws IOException {
		if (DEBUG) Log.v(TAG, "MuxTask#run:create muxer");
		IMuxer result;
		if (mOutputDoc != null) {
			mCurrent = createOutputDoc(mOutputDoc, mOutputName, segment);
		} else if (mOutputDir != null) {
			mCurrent = createOutputDoc(mOutputDir, mOutputName, segment);
		} else {
			throw new IOException("output dir not set");
		}
		result = MediaSplitMuxer.createMuxer(getContext(), mCurrent);
		return result;
	}

//--------------------------------------------------------------------------------
	/**
	 * 出力ファイルを示すDocumentFileを生成
	 * @param path
	 * @param name
	 * @param segment
	 * @return
	 */
	protected static DocumentFile createOutputDoc(
		@NonNull final String path,
		@NonNull final String name, final int segment) {
		
		if (DEBUG) Log.v(TAG, "createOutputDoc:path=" + path);
		final File _dir = new File(path);
		final File dir = _dir.isDirectory() ? _dir : _dir.getParentFile();
		return DocumentFile.fromFile(
			new File(dir,
			String.format(Locale.US, "%s ps%d.mp4", name, segment + 1)));
	}
	
	/**
	 * 出力ファイルを示すDocumentFileを生成
	 * @param path
	 * @param name
	 * @param segment
	 * @return
	 */
	protected static DocumentFile createOutputDoc(
		@NonNull final DocumentFile path,
		@NonNull final String name, final int segment) {
		
		if (DEBUG) Log.v(TAG, "createOutputDoc:path=" + path.getUri());
		final DocumentFile dir = path.isDirectory() ? path : path.getParentFile();
		return dir.createFile(null,
			String.format(Locale.US, "%s ps%d.mp4", name, segment + 1));
	}
	
	/**
	 * IMUxer生成処理
	 * @param context
	 * @param file
	 * @return
	 * @throws IOException
	 */
	@SuppressLint("NewApi")
	protected static IMuxer createMuxer(@NonNull final Context context,
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
