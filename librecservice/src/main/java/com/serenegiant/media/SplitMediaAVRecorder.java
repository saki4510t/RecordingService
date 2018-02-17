package com.serenegiant.media;
/*
 *
 * Copyright (c) 2016-2018 saki t_saki@serenegiant.com
 *
 * File name: SplitMediaAVRecorder.java
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
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.provider.DocumentFile;
import android.util.Log;

import com.serenegiant.utils.FileUtils;
import com.serenegiant.utils.SDUtils;
import com.serenegiant.utils.StorageInfo;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * MP4自動分割録画用IRecorder実装
 */
public class SplitMediaAVRecorder extends Recorder {
	private static final boolean DEBUG = true; // FIXME set false on production
	private static final String TAG = SplitMediaAVRecorder.class.getSimpleName();

	protected final WeakReference<Context> mWeakContext;
	private final int mSaveTreeId;	// SDカードへの出力を試みるかどうか
	@Nullable
	private final String mOutputDirPath;
	@Nullable
	private final DocumentFile mOutputDir;
	@NonNull
	private final String mName;
	private final long mSplitSize;
	
	public SplitMediaAVRecorder(@NonNull final Context context,
		final RecorderCallback callback,
		@NonNull final DocumentFile outputDir,
		@NonNull final String name,
		final long splitSize) throws IOException {

		super(callback);
		if (DEBUG) Log.v(TAG, "コンストラクタ");
		mWeakContext = new WeakReference<Context>(context);
		mSaveTreeId = 0;
		mOutputDirPath = null;
		mOutputDir = outputDir;
		mName = name;
		mSplitSize = splitSize;
		setupMuxer(context, outputDir, name, splitSize);
	}
	
	@Override
	public boolean check() {
		final Context context = mWeakContext.get();
		final StorageInfo info = mOutputDir != null
			? SDUtils.getStorageInfo(context, mOutputDir) : null;
		if ((info != null) && (info.totalBytes != 0)) {
			return ((info.freeBytes/ (float)info.totalBytes) < FileUtils.FREE_RATIO)
				|| (info.freeBytes < FileUtils.FREE_SIZE);
		}
		return (context == null)
			|| ((mOutputDir == null)
				&& !FileUtils.checkFreeSpace(context,
					VideoConfig.maxDuration, mStartTime, mSaveTreeId));
	}
	
	@Nullable
	@Override
	public String getOutputPath() {
		throw new UnsupportedOperationException();
	}
	
	@Nullable
	@Override
	public DocumentFile getOutputFile() {
		throw new UnsupportedOperationException();
	}
	
	protected void setupMuxer(
		@NonNull final Context context,
		@NonNull final DocumentFile output,
		@NonNull final String name,
		final long splitSize)  throws IOException {
		
		if (DEBUG) Log.v(TAG, "setupMuxer");
		setMuxer(new MediaSplitMuxer(context, output, name, splitSize));
	}
	
}
