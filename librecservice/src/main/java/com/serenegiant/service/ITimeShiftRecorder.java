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

import java.io.IOException;

/**
 * タイムシフト録画サービスへアクセスするためのヘルパークラスのインターフェース
 */
public interface ITimeShiftRecorder extends IServiceRecorder {
	/**
	 * タイムシフトバッファリング開始
	 */
	public void startTimeShift() throws IOException;

	/**
	 * タイムシフトバッファリング終了
	 */
	public void stopTimeShift();

	/**
	 * タイムシフトバッファリング中かどうかを取得
	 * @return
	 */
	public boolean isTimeShift();
	
	/**
	 * キャッシュサイズを指定
	 * @param cacheSize
	 * @throws IllegalStateException
	 * @throws IllegalArgumentException
	 */
	public void setCacheSize(final int cacheSize)
		throws IllegalStateException, IllegalArgumentException;

	/**
	 * キャッシュ場所を指定, パーミッションが有ってアプリから書き込めること
	 * @param cacheDir
	 * @throws IllegalStateException prepareよりも後には呼べない
	 * @throws IllegalArgumentException パーミッションが無い
	 * 									あるいは存在しない場所など書き込めない時
	 */
	public void setCacheDir(@NonNull final String cacheDir)
		throws IllegalStateException, IllegalArgumentException;
}
