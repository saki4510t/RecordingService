package com.serenegiant.service;
/*
 * Copyright (c) 2016-2019.  saki t_saki@serenegiant.com
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.documentfile.provider.DocumentFile;

import android.os.Build;
import android.view.Surface;

import com.serenegiant.media.IAudioSampler;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 録画サービスへアクセスするためのインターフェース
 */
public interface IServiceRecorder {

	public static final int STATE_UNINITIALIZED = 0;
	public static final int STATE_BINDING = 1;
	public static final int STATE_BIND = 2;
	public static final int STATE_UNBINDING = 3;

	public interface Callback {
		/**
		 * 録画サービスと接続した
		 */
		public void onConnected();

		/**
		 * エンコーダーの準備が整った
		 * このタイミングでRecorderへ動画・音声データ供給開始する
		 */
		public void onPrepared();

		/**
		 * 録画可能
		 * このタイミングでIServiceRecorder#startを呼び出す
		 */
		public void onReady();

		/**
		 * 録画サービスから切断された
		 */
		public void onDisconnected();
	}
	
	/**
	 * 関係するリソースを破棄する
	 */
	public void release();

	/**
	 * サービスとバインドして使用可能になっているかどうかを取得
	 * @return
	 */
	public boolean isReady();

	/**
	 * 録画中かどうかを取得
	 * @return
	 */
	public boolean isRecording();

	/**
	 * 録画設定をセット
	 * @param width
	 * @param height
	 * @param frameRate
	 * @param bpp
	 * @throws IllegalStateException
	 */
	public void setVideoSettings(final int width, final int height,
		final int frameRate, final float bpp) throws IllegalStateException;

	/**
	 * 録音用のIAudioSamplerをセット
	 * #writeAudioFrameと排他使用
	 * @param sampler
	 */
	public void setAudioSampler(@NonNull final IAudioSampler sampler)
		throws IllegalStateException;

	/**
	 * 録音設定
	 * #setAudioSamplerで設置したIAudioSamplerの設定が優先される
	 * @param sampleRate
	 * @param channelCount
	 * @throws IllegalStateException
	 */
	public void setAudioSettings(final int sampleRate, final int channelCount)
		throws IllegalStateException;
	/**
	 * 録画録音の準備
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public void prepare() throws IllegalStateException, IOException;

	/**
	 * 録画開始
	 * @param outputPath 出力先ファイルパス
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	@Deprecated
	public void start(@NonNull final String outputPath)
		throws IllegalStateException, IOException;

	/**
	 * 録画開始
	 * @param output 出力ファイル
	 * @throws IllegalStateException
	 * @throws IOException
	 */
	public void start(@NonNull final DocumentFile output)
		throws IllegalStateException, IOException;

	/**
	 * 録画終了
	 */
	public void stop();

	/**
	 * 録画用の映像を入力するためのSurfaceを取得
	 * @return
	 */
	@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
	@Nullable
	public Surface getInputSurface();

	/**
	 * 録画用の映像フレームが準備できた時に録画サービスへ通知するためのメソッド
	 */
	public void frameAvailableSoon();
	
	/**
	 * 録音用の音声データを書き込む
	 * #setAudioSamplerと排他使用
	 * @param buffer
	 * @param presentationTimeUs
	 * @throws IllegalStateException
	 */
	public void writeAudioFrame(@NonNull final ByteBuffer buffer,
		final long presentationTimeUs);
}
