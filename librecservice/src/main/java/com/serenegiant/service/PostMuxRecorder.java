package com.serenegiant.service;
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

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.NonNull;

import com.serenegiant.service.PostMuxRecService.MuxIntermediateType;

/**
 * PostMux録画サービスアクセス用のヘルパークラス
 * #prepare => #start => #stop => #release
 */
public class PostMuxRecorder extends AbstractServiceRecorder {
	private static final boolean DEBUG = false;	// FIXME set false on production
	private static final String TAG = PostMuxRecorder.class.getSimpleName();
	
	public static PostMuxRecorder newInstance(@NonNull final Context context,
		@NonNull final Class<? extends PostMuxRecService> serviceClazz,
		@NonNull final Callback callback) {

		return new PostMuxFileRecorder(context, serviceClazz, callback);
	}

	public static PostMuxRecorder newInstance(@NonNull final Context context,
		@NonNull final Class<? extends PostMuxRecService> serviceClazz,
		@NonNull final Callback callback,
		@MuxIntermediateType final int muxIntermediateType) {

		switch (muxIntermediateType) {
		case PostMuxRecService.MUX_INTERMEDIATE_TYPE_CHANNEL:
			return new PostMuxChannelRecorder(context, serviceClazz, callback);
		case PostMuxRecService.MUX_INTERMEDIATE_TYPE_FILE:
		default:
			return new PostMuxFileRecorder(context, serviceClazz, callback);
		}
	}
	
	private static class PostMuxFileRecorder extends PostMuxRecorder {
		/**
		 * コンストラクタ
		 * @param context
		 * @param serviceClazz
		 * @param callback
		 */
		public PostMuxFileRecorder(@NonNull final Context context,
			@NonNull final Class<? extends PostMuxRecService> serviceClazz,
			@NonNull final Callback callback) {

			super(context, serviceClazz, callback);
		}

		@Override
		@NonNull
		protected Intent createServiceIntent(@NonNull final Context context,
			@NonNull final Class<? extends AbstractRecorderService> serviceClazz) {
	
			return super.createServiceIntent(context, serviceClazz)
				.putExtra(PostMuxRecService.KEY_MUX_INTERMEDIATE_TYPE,
					PostMuxRecService.MUX_INTERMEDIATE_TYPE_FILE);
		}
	}
	
	private static class PostMuxChannelRecorder extends PostMuxRecorder {
		/**
		 * コンストラクタ
		 * @param context
		 * @param serviceClazz
		 * @param callback
		 */
		public PostMuxChannelRecorder(@NonNull final Context context,
			@NonNull final Class<? extends PostMuxRecService> serviceClazz,
			@NonNull final Callback callback) {

			super(context, serviceClazz, callback);
		}

		@Override
		@NonNull
		protected Intent createServiceIntent(@NonNull final Context context,
			@NonNull final Class<? extends AbstractRecorderService> serviceClazz) {
	
			return super.createServiceIntent(context, serviceClazz)
				.putExtra(PostMuxRecService.KEY_MUX_INTERMEDIATE_TYPE,
					PostMuxRecService.MUX_INTERMEDIATE_TYPE_CHANNEL);
		}
	}

	/**
	 * コンストラクタ
	 * @param context
	 * @param serviceClazz
	 * @param callback
	 */
	private PostMuxRecorder(@NonNull final Context context,
		@NonNull Class<? extends PostMuxRecService> serviceClazz,
		@NonNull final Callback callback) {

		super(context, serviceClazz, callback);
	}
	
	/**
	 * 録画サービスと接続した際にIBinderからAbstractRecorderService
	 * (またはその継承クラス)を取得するためのメソッド
	 * @param service
	 * @return
	 */
	@NonNull
	@Override
	protected AbstractRecorderService getService(final IBinder service) {
		return ((PostMuxRecService.LocalBinder)service).getService();
	}
}
