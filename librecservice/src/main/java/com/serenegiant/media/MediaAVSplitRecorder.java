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

import com.serenegiant.system.StorageInfo;
import com.serenegiant.system.StorageUtils;
import com.serenegiant.utils.FileUtils;

import java.io.File;
import java.io.IOException;

/**
 * MP4自動分割録画用IRecorder実装
 * FIXME これは今のところAPI29/Android10以降の対象範囲別ストレージでは動かない(SAF経由なら動く)
 */
public class MediaAVSplitRecorder extends Recorder {
	private static final boolean DEBUG = false; // FIXME set false on production
	private static final String TAG = MediaAVSplitRecorder.class.getSimpleName();

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
	@SuppressWarnings("deprecation")
	@Deprecated
	public MediaAVSplitRecorder(@NonNull final Context context,
		final RecorderCallback callback,
		@NonNull final String outputDir,
		@NonNull final String name,
		final long splitSize) throws IOException {
		
		this(context, callback, null, null, null, outputDir, name, splitSize);
	}
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param callback
	 * @param config
	 * @param factory
	 * @param queue
	 * @param outputDir 出力先ディレクトリ
	 * @param name
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 * @throws IOException
	 */
	@SuppressWarnings("deprecation")
	@Deprecated
	public MediaAVSplitRecorder(@NonNull final Context context,
		final RecorderCallback callback,
		@Nullable final VideoConfig config,
		@Nullable final IMuxer.IMuxerFactory factory,
		@Nullable final IMediaQueue queue,
		@NonNull final String outputDir,
		@NonNull final String name,
		final long splitSize) throws IOException {

		super(context, callback, config, factory);
		if (DEBUG) Log.v(TAG, "コンストラクタ");
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
		
		this(context, callback, null, null, null, outputDir, name, splitSize);
	}
	
	/**
	 * コンストラクタ
	 * @param context
	 * @param callback
	 * @param config
	 * @param factory
	 * @param queue
	 * @param outputDir 出力先ディレクトリ
	 * @param name 出力名
	 * @param splitSize 出力ファイルサイズの目安, 0以下ならデフォルト値
	 * @throws IOException
	 */
	public MediaAVSplitRecorder(@NonNull final Context context,
		final RecorderCallback callback,
		@Nullable final VideoConfig config,
		@Nullable final IMuxer.IMuxerFactory factory,
		@Nullable final IMediaQueue queue,
		@NonNull final DocumentFile outputDir,
		@NonNull final String name,
		final long splitSize) throws IOException {

		super(context, callback, config, factory);
		if (DEBUG) Log.v(TAG, "コンストラクタ");
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
		StorageInfo info = null;
		try {
			info = StorageUtils.getStorageInfo(context, mOutputDir);
		} catch (final IOException e) {
			Log.w(TAG, e);
		}
		if ((info != null) && (info.totalBytes != 0)) {
			return ((info.freeBytes/ (float)info.totalBytes) < FileUtils.FREE_RATIO)
				|| (info.freeBytes < FileUtils.FREE_SIZE);
		}
		return (context == null)
			|| ((mOutputDir == null)
				&& !FileUtils.checkFreeSpace(context,
					getConfig().maxDuration(), mStartTime, 0));
	}
	
	/**
	 * このクラスでは無効, UnsupportedOperationExceptionを投げる
	 * 代わりに出力ディレクトリ取得用の#getOutputDirを使うこと
	 * @return
	 */
	@Deprecated
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
	
	protected void setupMuxer(
		@NonNull final Context context,
		@Nullable final IMediaQueue queue,
		@NonNull final DocumentFile output,
		@NonNull final String name,
		final long splitSize)  throws IOException {
		
		if (DEBUG) Log.v(TAG, "setupMuxer");
		setMuxer(new MediaSplitMuxer(context, getConfig(), getMuxerFactory(),
			queue, output, name, splitSize));
	}

	@SuppressWarnings("deprecation")
	@Deprecated
	protected void setupMuxer(
		@NonNull final Context context,
		@Nullable final IMediaQueue queue,
		@NonNull final String outputPath,
		@NonNull final String name,
		final long splitSize)  throws IOException {
		
		if (DEBUG) Log.v(TAG, "setupMuxer");
		setMuxer(new MediaSplitMuxer(context, getConfig(), getMuxerFactory(),
			queue, outputPath, name, splitSize));
	}
}
