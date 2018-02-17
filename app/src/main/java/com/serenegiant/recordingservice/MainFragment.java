package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2018 saki t_saki@serenegiant.com
 *
 * File name: MainFragment.java
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

import android.app.ListFragment;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * 他のFragmentを起動するためのメニュー表示用Fragment
 */
public class MainFragment extends ListFragment {

	public MainFragment() {
		super();
	}
	
	public static class Item implements Parcelable {
		private int mId;
		private String mName;

		public Item(final int id, final String name) {
			mId = id;
			mName = name;
		}
		
		protected Item(final Parcel in) {
			mId = in.readInt();
			mName = in.readString();
		}
		
		public static final Creator<Item> CREATOR = new Creator<Item>() {
			@Override
			public Item createFromParcel(Parcel in) {
				return new Item(in);
			}
			
			@Override
			public Item[] newArray(int size) {
				return new Item[size];
			}
		};
		
		@Override
		public int describeContents() {
			return 0;
		}
		
		@Override
		public void writeToParcel(final Parcel dest, final int flags) {
			dest.writeInt(mId);
			dest.writeString(mName);
		}
	}
}
