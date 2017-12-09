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
package com.serenegiant.media;

import android.support.annotation.NonNull;
import android.util.Log;

import java.io.IOException;

/**
 * Created by saki on 2017/12/08.
 *
 */
class PostMuxBuilder {
	private static final boolean DEBUG = true; // FIXME set false on production
	private static final String TAG = PostMuxBuilder.class.getSimpleName();
	
	public PostMuxBuilder(@NonNull final String tempDir,
		@NonNull final String outputPath) {
		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		// FIXME 未実装
	}

	public PostMuxBuilder(@NonNull final String tempDir,
		final int accessId) {
		if (DEBUG) Log.v(TAG, "コンストラクタ:");
		// FIXME 未実装
	}
	
	public void build() throws IOException {
		if (DEBUG) Log.v(TAG, "build:");
		// FIXME 未実装
		if (DEBUG) Log.v(TAG, "build:finished");
	}
}
