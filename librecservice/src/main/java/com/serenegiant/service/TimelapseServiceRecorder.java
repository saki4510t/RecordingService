package com.serenegiant.service;
/*
 *
 * Copyright (c) 2016-2021 saki t_saki@serenegiant.com
 *
 * File name: PostMuxRecFragment.java
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
import android.os.IBinder;

import androidx.annotation.NonNull;

public class TimelapseServiceRecorder extends AbstractServiceRecorder {
	private static final boolean DEBUG = false;	// FIXME set false on production
	private static final String TAG = TimelapseServiceRecorder.class.getSimpleName();

	public static TimelapseServiceRecorder newInstance(
		@NonNull final Context context,
		@NonNull final Class<? extends TimelapseRecService> serviceClazz,
		@NonNull final Callback callback) {

		return new TimelapseServiceRecorder(context, serviceClazz, callback);
	}

//================================================================================

	protected TimelapseServiceRecorder(
		@NonNull final Context context,
		@NonNull final Class<? extends TimelapseRecService> serviceClazz,
		@NonNull final Callback callback) {

		super(context, serviceClazz, callback);
	}

	@NonNull
	@Override
	protected AbstractRecorderService getService(final IBinder service) {
		return ((TimelapseRecService.LocalBinder)service).getService();
	}
}
