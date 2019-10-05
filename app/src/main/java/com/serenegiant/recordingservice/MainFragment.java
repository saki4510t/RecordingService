package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2019 saki t_saki@serenegiant.com
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

import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.serenegiant.media.VideoConfig;

import java.util.Locale;

/**
 * 他のFragmentを起動するためのメニュー表示用Fragment
 */
public class MainFragment extends BaseFragment {

	private static final Item[] ITEMS = {
		new Item(0, "SimpleServiceRec"),
		new Item(1, "PostMuxRec"),
		new Item(2, "TimeShiftRec"),
		new Item(3, "SplitRec"),
	};
	
	private ItemListAdapter mAdapter;
	
	public MainFragment() {
		super();
	}
	
	@Override
	public void onCreate(@Nullable final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		VideoConfig.DEFAULT_CONFIG.setMaxDuration(-1L);	// 録画時間制限なし
	}
	
	@Override
	public View onCreateView(@NonNull final LayoutInflater inflater,
		final ViewGroup container, final Bundle savedInstanceState) {

		final View rootView = inflater.inflate(R.layout.fragment_main, container, false);
		final ListView listView = rootView.findViewById(android.R.id.list);
		final View emptyView = rootView.findViewById(android.R.id.empty);
		listView.setEmptyView(emptyView);
		if (mAdapter == null) {
			mAdapter = new ItemListAdapter(getActivity(),
				R.layout.list_item_item, ITEMS);
		}
		listView.setAdapter(mAdapter);
		listView.setOnItemClickListener(mOnItemClickListener);
		return rootView;
	}
	
	protected void internalOnResume() {
		super.internalOnResume();
		getActivity().setTitle(R.string.app_name);
	}

	private final AdapterView.OnItemClickListener mOnItemClickListener
		= new AdapterView.OnItemClickListener() {
		@Override
		public void onItemClick(final AdapterView<?> parent,
			final View view, final int position, final long id) {

			if (!checkPermissionCamera()
				|| !checkPermissionWriteExternalStorage()
				|| !checkPermissionAudio()) {
				
				return;
			}
			final FragmentManager fm = getFragmentManager();
			switch (position) {
			case 0:
				fm.beginTransaction()
					.addToBackStack(null)
					.replace(R.id.container,
					new SimpleRecFragment()).commit();
				break;
			case 1:
				fm.beginTransaction()
					.addToBackStack(null)
					.replace(R.id.container,
					new PostMuxRecFragment()).commit();
				break;
			case 2:
				fm.beginTransaction()
					.addToBackStack(null)
					.replace(R.id.container,
					new TimeShiftRecFragment()).commit();
				break;
			case 3:
				fm.beginTransaction()
					.addToBackStack(null)
					.replace(R.id.container,
					new SplitRecFragment()).commit();
				break;
			}
		}
	};
	
	private static class Item implements Parcelable {
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

	private static class ItemListAdapter extends ArrayAdapter<Item> {
		private final LayoutInflater mInflater;
		
		public ItemListAdapter(@NonNull final Context context,
			@LayoutRes final int resource,
			@NonNull final Item[] objects) {

			super(context, resource, objects);
			mInflater = LayoutInflater.from(getContext());
		}
		
		@NonNull
		@Override
		public View getView(final int position,
			@Nullable final View convertView,
			@NonNull final ViewGroup parent) {

			View view = convertView;
			if (view == null) {
				view = mInflater.inflate(R.layout.list_item_item, parent, false);
			}
			ViewHolder holder = (ViewHolder)view.getTag();
			if (holder == null) {
				holder = new ViewHolder();
				holder.idTv = view.findViewById(R.id.id);
				holder.nameTv = view.findViewById(R.id.name);
				view.setTag(holder);
			}
			final Item item = getItem(position);
			holder.idTv.setText(String.format(Locale.US, "%d", item.mId));
			holder.nameTv.setText(item.mName);
			return view;
		}
	}
	
	private static class ViewHolder {
		private TextView idTv;
		private TextView nameTv;
	}
}
