package com.serenegiant.media;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

/**
 * MP4自動分割録画用IRecorder実装
 * MediaAVSplitRecorderはAPI29/Android10以降の対象範囲別ストレージだと使えないので新たに作成
 */
public class MediaAVSplitRecorderV2 extends Recorder {
	private static final boolean DEBUG = false; // FIXME set false on production
	private static final String TAG = MediaAVSplitRecorderV2.class.getSimpleName();

	/**
	 * コンストラクタ
	 * @param context
	 * @param callback
	 * @param config
	 * @param factory
	 * @param outputDir
	 * @param queue
	 * @param splitSize
	 * @throws IOException
	 */
	public MediaAVSplitRecorderV2(
		@NonNull final Context context,
		final RecorderCallback callback,
		@Nullable final VideoConfig config,
		@Nullable final IMuxer.IMuxerFactory factory,
		@Nullable final IMediaQueue queue,
		@Nullable final DocumentFile outputDir,
		final long splitSize) throws IOException {

		super(context, callback, config, factory);
		setupMuxer(context, queue, outputDir, splitSize);
	}

	@Override
	public boolean check() {
		return false;
	}

	@Deprecated
	@Nullable
	@Override
	public String getOutputPath() {
		throw new UnsupportedOperationException("");
	}

	@Nullable
	@Override
	public DocumentFile getOutputFile() {
		return null;
	}

	private void setupMuxer(
		@NonNull final Context context,
		@Nullable final IMediaQueue queue,
		@Nullable final DocumentFile output,
		final long splitSize)  throws IOException {

		if (DEBUG) Log.v(TAG, "setupMuxer");
		setMuxer(new MediaSplitMuxerV2(context,
			output, getConfig(), getMuxerFactory(),
			queue, splitSize));
	}
}