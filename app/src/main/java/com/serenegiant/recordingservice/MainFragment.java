package com.serenegiant.recordingservice;
/*
 *
 * Copyright (c) 2016-2021 saki t_saki@serenegiant.com
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

import android.Manifest;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.serenegiant.dialog.RationalDialogV4;
import com.serenegiant.media.VideoConfig;
import com.serenegiant.system.BuildCheck;
import com.serenegiant.system.PermissionUtils;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Locale;

/**
 * 他のFragmentを起動するためのメニュー表示用Fragment
 */
public class MainFragment extends BaseFragment
	implements RationalDialogV4.DialogResultListener {

	private static final boolean DEBUG = true;	// set false on production
	private static final String TAG = MainFragment.class.getSimpleName();

	private static final Item[] ITEMS = {
		new Item(0, "Simple rec on Service"),
		new Item(1, "PostMux rec on Service"),
		new Item(2, "Timeshift rec on Service"),
		new Item(3, "Split rec"),
		new Item(4, "Split rec2"),
		new Item(5, "Split rec2 on Service"),
		new Item(6, "Timelapse rec on Service"),
	};

	private static final String[] LOCATION_PERMISSIONS = new String[] {
		Manifest.permission.ACCESS_FINE_LOCATION,
		Manifest.permission.ACCESS_COARSE_LOCATION
	};

	private PermissionUtils mPermissions;
	private ItemListAdapter mAdapter;
	
	public MainFragment() {
		super();
	}

	@Override
	public void onAttach(@NonNull @NotNull final Context context) {
		super.onAttach(context);
		// パーミッション要求の準備
		mPermissions = new PermissionUtils(this, mPermissionCallback)
			.prepare(this, LOCATION_PERMISSIONS);
	}

	@Override
	public void onCreate(@Nullable final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		VideoConfig.DEFAULT_CONFIG.setMaxDuration(-1L);	// 録画時間制限なし
	}
	
	@Override
	public View onCreateView(@NonNull final LayoutInflater inflater,
		final ViewGroup container, final Bundle savedInstanceState) {

		return inflater.inflate(R.layout.fragment_main, container, false);
	}

	@Override
	public void onViewCreated(
		@NonNull final View view,
		@Nullable final Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		final ListView listView = view.findViewById(android.R.id.list);
		final View emptyView = view.findViewById(android.R.id.empty);
		listView.setEmptyView(emptyView);
		if (mAdapter == null) {
			mAdapter = new ItemListAdapter(getActivity(),
				R.layout.list_item_item, ITEMS);
		}
		listView.setAdapter(mAdapter);
		listView.setOnItemClickListener(mOnItemClickListener);
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
			final FragmentManager fm = getParentFragmentManager();
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
			case 4:
				fm.beginTransaction()
					.addToBackStack(null)
					.replace(R.id.container,
					new SplitRecFragment2()).commit();
				break;
			case 5:
				fm.beginTransaction()
					.addToBackStack(null)
					.replace(R.id.container,
					new SplitRecFragment3()).commit();
				break;
			case 6:
				fm.beginTransaction()
					.addToBackStack(null)
					.replace(R.id.container,
					new TimelapseRecFragment()).commit();
				break;
			}
		}
	};

	/**
	 * RationalDialogV4からのコールバックリスナー
	 * @param dialog
	 * @param permissions
	 * @param result
	 */
	@Override
	public void onDialogResult(@NonNull final RationalDialogV4 dialog,
		@NonNull final String[] permissions, final boolean result) {

		if (DEBUG) Log.v(TAG, "onDialogResult:" + result + "," + Arrays.toString(permissions));
		if (result) { // メッセージダイアログでOKを押された時はパーミッション要求する
			if (BuildCheck.isMarshmallow()) {
				if (mPermissions != null) {
					mPermissions.requestPermission(permissions, false);
				}
			}
		}
	}

	private final PermissionUtils.PermissionCallback mPermissionCallback
		= new PermissionUtils.PermissionCallback() {
		@Override
		public void onPermissionShowRational(@NonNull @NotNull final String permission) {
			if (DEBUG) Log.v(TAG, "onPermissionShowRational:" + permission);
			final RationalDialogV4 dialog = RationalDialogV4.showDialog(MainFragment.this, permission);
			if (dialog == null) {
				if (DEBUG) Log.v(TAG, "onPermissionShowRational:" +
					"デフォルトのダイアログ表示ができなかったので自前で表示しないといけない," + permission);
				if (Manifest.permission.INTERNET.equals(permission)) {
					RationalDialogV4.showDialog(MainFragment.this,
						R.string.permission_title,
						R.string.permission_network_request,
						new String[] {Manifest.permission.INTERNET});
				} else if ((Manifest.permission.ACCESS_FINE_LOCATION.equals(permission))
					|| (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission))) {
					RationalDialogV4.showDialog(MainFragment.this,
						R.string.permission_title,
						R.string.permission_location_request,
						LOCATION_PERMISSIONS
					);
				}
			}
		}

		@Override
		public void onPermissionShowRational(@NonNull @NotNull final String[] permissions) {
			if (DEBUG) Log.v(TAG, "onPermissionShowRational:" + Arrays.toString(permissions));
			// 複数パーミッションの一括要求時はデフォルトのダイアログ表示がないので自前で実装する
			if (Arrays.equals(LOCATION_PERMISSIONS, permissions)) {
				RationalDialogV4.showDialog(
					MainFragment.this,
					R.string.permission_title,
					R.string.permission_location_request,
					LOCATION_PERMISSIONS);
			}
		}

		@Override
		public void onPermissionDenied(@NonNull @NotNull final String permission) {
			if (DEBUG) Log.v(TAG, "onPermissionDenied:" + permission);
			// ユーザーがパーミッション要求を拒否したときの処理
		}

		@Override
		public void onPermission(@NonNull @NotNull final String permission) {
			if (DEBUG) Log.v(TAG, "onPermission:" + permission);
			// ユーザーがパーミッション要求を承認したときの処理
		}

		@Override
		public void onPermissionNeverAskAgain(@NonNull @NotNull final String permission) {
			if (DEBUG) Log.v(TAG, "onPermissionNeverAskAgain:" + permission);
			// 端末のアプリ設定画面を開くためのボタンを配置した画面へ遷移させる
			getParentFragmentManager()
				.beginTransaction()
				.addToBackStack(null)
				.replace(R.id.container, SettingsLinkFragment.newInstance())
				.commit();
		}

		@Override
		public void onPermissionNeverAskAgain(@NonNull @NotNull final String[] permissions) {
			if (DEBUG) Log.v(TAG, "onPermissionNeverAskAgain" + Arrays.toString(permissions));
			// 端末のアプリ設定画面を開くためのボタンを配置した画面へ遷移させる
			getParentFragmentManager()
				.beginTransaction()
				.addToBackStack(null)
				.replace(R.id.container, SettingsLinkFragment.newInstance())
				.commit();
		}
	};

	/**
	 * 外部ストレージへの書き込みパーミッションが有るかどうかをチェック
	 * なければ説明ダイアログを表示する
	 * @return true 外部ストレージへの書き込みパーミッションが有る
	 */
	private boolean checkPermissionWriteExternalStorage() {
		// 26<=API<29で外部ストレージ書き込みパーミッションがないとき
		return !BuildCheck.isAPI26() || BuildCheck.isAPI29()
			|| ((mPermissions != null)
				&& mPermissions.requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, true));
	}

	/**
	 * 録音のパーミッションが有るかどうかをチェック
	 * なければ説明ダイアログを表示する
	 * @return true 録音のパーミッションが有る
	 */
	private boolean checkPermissionAudio() {
		return ((mPermissions != null)
			&& mPermissions.requestPermission(Manifest.permission.RECORD_AUDIO, true));
	}

	/**
	 * カメラのパーミッションが有るかどうかをチェック
	 * なければ説明ダイアログを表示する
	 * @return true カメラのパーミッションが有る
	 */
	private boolean checkPermissionCamera() {
		return ((mPermissions != null)
			&& mPermissions.requestPermission(Manifest.permission.CAMERA, true));
	}

	/**
	 * ネットワークアクセスのパーミッションが有るかどうかをチェック
	 * なければ説明ダイアログを表示する
	 * @return true ネットワークアクセスのパーミッションが有る
	 */
	private boolean checkPermissionNetwork() {
		return ((mPermissions != null)
			&& mPermissions.requestPermission(Manifest.permission.INTERNET, true));
	}

	/**
	 * 位置情報アクセスのパーミッションが有るかどうかをチェック
	 * なければ説明ダイアログを表示する
	 * @return true 位置情報アクセスのパーミッションが有る
	 */
	private boolean checkPermissionLocation() {
		return ((mPermissions != null)
			&& mPermissions.requestPermission(LOCATION_PERMISSIONS, true));
	}

	private static class Item implements Parcelable {
		private final int mId;
		private final String mName;

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
