package com.serenegiant.media;
/*
 *
 * Copyright (c) 2016-2019 saki t_saki@serenegiant.com
 *
 * File name: MediaAVSplitRecorder.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
*/

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import android.util.Log;

import com.serenegiant.utils.FileUtils;
import com.serenegiant.utils.SDUtils;
import com.serenegiant.utils.StorageInfo;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * MP4自動分割録画用IRecorder実装
 */
public class MediaAVSplitRecorder extends Recorder {
	private static final boolean DEBUG = false; // FIXME set false on production
	private static final String TAG = MediaAVSplitRecorder.class.getSimpleName();

	protected final WeakReference<Context> mWeakContext;
	private final int mSaveTreeId;	// SDカードへの出力を試みるかどうか
	@NonNull
	private final DocumentFile mOutputDir;
	
	/**
	 * コンストラクタ
	 * バッファ用のキューはMemMediaQueueのデフォルトを使う
	 * @param context
	 * @param callback
	 * @param outputDir 出力先ディレクトリ
	 * @param name
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 * @throws IOException
	 */
	public MediaAVSplitRecorder(@NonNull final Context context,
		final RecorderCallback callback,
		@NonNull final String outputDir,
		@NonNull final String name,
		final long splitSize) throws IOException {
		
		this(context, null, callback, outputDir, name, splitSize);
	}
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param queue
	 * @param callback
	 * @param outputDir 出力先ディレクトリ
	 * @param name
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 * @throws IOException
	 */
	public MediaAVSplitRecorder(@NonNull final Context context,
		@Nullable final IMediaQueue queue,
		final RecorderCallback callback,
		@NonNull final String outputDir,
		@NonNull final String name,
		final long splitSize) throws IOException {

		super(callback);
		if (DEBUG) Log.v(TAG, "コンストラクタ");
		mWeakContext = new WeakReference<Context>(context);
		mSaveTreeId = 0;
		mOutputDir = DocumentFile.fromFile(new File(outputDir));
		setupMuxer(context, queue, outputDir, name, splitSize);
	}

	/**
	 * コンストラクタ
	 * バッファ用のキューはMemMediaQueueのデフォルトを使う
	 * @param context
	 * @param callback
	 * @param outputDir 出力先ディレクトリ
	 * @param name
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 * @throws IOException
	 */
	public MediaAVSplitRecorder(@NonNull final Context context,
		final RecorderCallback callback,
		@NonNull final DocumentFile outputDir,
		@NonNull final String name,
		final long splitSize) throws IOException {
		
		this(context, null, callback, outputDir, name, splitSize);
	}
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param queue
	 * @param callback
	 * @param outputDir 出力先ディレクトリ
	 * @param name 出力名
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 * @throws IOException
	 */
	public MediaAVSplitRecorder(@NonNull final Context context,
		@Nullable final IMediaQueue queue,
		final RecorderCallback callback,
		@NonNull final DocumentFile outputDir,
		@NonNull final String name,
		final long splitSize) throws IOException {

		super(callback);
		if (DEBUG) Log.v(TAG, "コンストラクタ");
		mWeakContext = new WeakReference<Context>(context);
		mSaveTreeId = 0;
		mOutputDir = outputDir;
		setupMuxer(context, queue, outputDir, name, splitSize);
	}
	
	/**
	 * 出力ディレクトリの空き容量が足りなくなっていないかどうかを
	 * チェックするためのヘルパーメソッド
	 * @return
	 */
	@Override
	public boolean check() {
		final Context context = getContext();
		final StorageInfo info = SDUtils.getStorageInfo(context, mOutputDir);
		if ((info != null) && (info.totalBytes != 0)) {
			return ((info.freeBytes/ (float)info.totalBytes) < FileUtils.FREE_RATIO)
				|| (info.freeBytes < FileUtils.FREE_SIZE);
		}
		return (context == null)
			|| ((mOutputDir == null)
				&& !FileUtils.checkFreeSpace(context,
					VideoConfig.maxDuration, mStartTime, mSaveTreeId));
	}
	
	/**
	 * このクラスでは無効, UnsupportedOperationExceptionを投げる
	 * 代わりに出力ディレクトリ取得用の#getOutputDirを使うこと
	 * @return
	 */
	@Nullable
	@Override
	public String getOutputPath() {
		throw new UnsupportedOperationException();
	}
	
	/**
	 * このクラスでは無効, UnsupportedOperationExceptionを投げる
	 * 代わりに出力ディレクトリ取得用の#getOutputDirを使うこと
	 * @return
	 */
	@Nullable
	@Override
	public DocumentFile getOutputFile() {
		throw new UnsupportedOperationException();
	}
	
	/**
	 * 出力ディレクトリを取得
	 * @return
	 */
	public DocumentFile getOutputDir() {
		return mOutputDir;
	}
	
	@Nullable
	protected Context getContext() {
		return mWeakContext.get();
	}

	protected void setupMuxer(
		@NonNull final Context context,
		@Nullable final IMediaQueue queue,
		@NonNull final DocumentFile output,
		@NonNull final String name,
		final long splitSize)  throws IOException {
		
		if (DEBUG) Log.v(TAG, "setupMuxer");
		if (queue == null) {
			setMuxer(new MediaSplitMuxer(context, output, name, splitSize));
		} else {
			setMuxer(new MediaSplitMuxer(context, queue, output, name, splitSize));
		}
	}
	
	protected void setupMuxer(
		@NonNull final Context context,
		@Nullable final IMediaQueue queue,
		@NonNull final String outputPath,
		@NonNull final String name,
		final long splitSize)  throws IOException {
		
		if (DEBUG) Log.v(TAG, "setupMuxer");
		if (queue == null) {
			setMuxer(new MediaSplitMuxer(context, outputPath, name, splitSize));
		} else {
			setMuxer(new MediaSplitMuxer(context, queue, outputPath, name, splitSize));
		}
	}
}
