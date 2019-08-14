/*
 * Copyright (c) 2015, Nordic Semiconductor
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package no.nordicsemi.android.nrftoolbox.uart;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.widget.Button;
import android.widget.CursorAdapter;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;

import java.util.List;

import no.nordicsemi.android.log.ILogSession;
import no.nordicsemi.android.log.LogContract;
import no.nordicsemi.android.nrftoolbox.R;
import no.nordicsemi.android.nrftoolbox.profile.BleProfileService;
import no.nordicsemi.android.nrftoolbox.uart.domain.Command;
import no.nordicsemi.android.nrftoolbox.uart.domain.UartConfiguration;

public class UARTControlFragment extends Fragment implements GridView.OnItemClickListener, UARTActivity.ConfigurationListener, LoaderManager.LoaderCallbacks<Cursor> {
	private final static String TAG = "UARTControlFragment";
	private final static String SIS_EDIT_MODE = "sis_edit_mode";

	private UartConfiguration mConfiguration;
	private UARTButtonAdapter mAdapter;
	private boolean mEditMode;

	private static final String SIS_LOG_SCROLL_POSITION = "sis_scroll_position";
	private static final int LOG_SCROLL_NULL = -1;
	private static final int LOG_SCROLLED_TO_BOTTOM = -2;

	private static final int LOG_REQUEST_ID = 1;
	private static final String[] LOG_PROJECTION = {LogContract.Log._ID, LogContract.Log.TIME, LogContract.Log.LEVEL, LogContract.Log.DATA};

	/**
	 * The service UART interface that may be used to send data to the target.
	 */
	private UARTInterface mUARTInterface;
	/**
	 * The adapter used to populate the list with log entries.
	 */
	private CursorAdapter mLogAdapter;
	/**
	 * The log session created to log events related with the target device.
	 */
	private ILogSession mLogSession;

	private EditText mField;
	private Button mSendButton;

	/**
	 * The last list view position.
	 */
	private int mLogScrollPosition;

	// The adapter that binds our data to the ListView
	private SimpleCursorAdapter mCursorAdapter;


	int count = 0;
	Thread thread;

	/**
	 * The receiver that listens for {@link BleProfileService#BROADCAST_CONNECTION_STATE} action.
	 */
	private final BroadcastReceiver mCommonBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(final Context context, final Intent intent) {
			// This receiver listens only for the BleProfileService.BROADCAST_CONNECTION_STATE action, no need to check it.
			final int state = intent.getIntExtra(BleProfileService.EXTRA_CONNECTION_STATE, BleProfileService.STATE_DISCONNECTED);

			switch (state) {
				case BleProfileService.STATE_CONNECTED: {
//					onDeviceConnected();
					break;
				}
				case BleProfileService.STATE_DISCONNECTED: {
//					onDeviceDisconnected();
					break;
				}
				case BleProfileService.STATE_CONNECTING:
				case BleProfileService.STATE_DISCONNECTING:
					// current implementation does nothing in this states
				default:
					// there should be no other actions
					break;
			}
		}
	};

	@Override
	public void onAttach(final Context context) {
		super.onAttach(context);
		Log.d(TAG, "Attached FRAMGENT_CONTROL!!!!!!!!!!!!!!");

		try {
			((UARTActivity)context).setConfigurationListener(this);
		} catch (final ClassCastException e) {
			Log.e(TAG, "The parent activity must implement EditModeListener");
		}
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		LocalBroadcastManager.getInstance(requireContext()).registerReceiver(mCommonBroadcastReceiver, makeIntentFilter());

		if (savedInstanceState != null) {
			mEditMode = savedInstanceState.getBoolean(SIS_EDIT_MODE);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		((UARTActivity)getActivity()).setConfigurationListener(null);
	}

	@Override
	public void onSaveInstanceState(final Bundle outState) {
		outState.putBoolean(SIS_EDIT_MODE, mEditMode);
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
		final View view = inflater.inflate(R.layout.fragment_feature_uart_control, container, false);

		final TextView display_battery = view.findViewById(R.id.display_battery);
		String[] message = {R.id.display_battery};

		// Initialize the adapter. Note that we pass a 'null' Cursor as the
		// third argument. We will pass the adapter a Cursor only when the
		// data has finished loading for the first time (i.e. when the
		// LoaderManager delivers the data to onLoadFinished). Also note
		// that we have passed the '0' flag as the last argument. This
		// prevents the adapter from registering a ContentObserver for the
		// Cursor (the CursorLoader will do this for us!).
		mCursorAdapter = new SimpleCursorAdapter(this, R.layout.fragment_feature_uart_control,
				null, columns, display_battery, 0);

		thread = new Thread(){
			@Override
			public void run(){
				while (!isInterrupted()){
					try {
						Thread.sleep(1000);
						getActivity().runOnUiThread (new Runnable(){
							@Override
							public void run(){
								count++;
								display_battery.setText(String.valueOf(count));
							}
						});
					} catch (InterruptedException e){
						e.printStackTrace();
					}

				}
			}

		};

		final Button clickButton = (Button) view.findViewById(R.id.click_button);

		clickButton.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View v)
			{
				thread.start();
			}
		});

//		thread.start();

		final GridView grid = view.findViewById(R.id.grid);
		grid.setAdapter(mAdapter = new UARTButtonAdapter(mConfiguration));
		grid.setOnItemClickListener(this);
		mAdapter.setEditMode(mEditMode);

		return view;
	}


//	@Override
//	public void onViewCreated(@NonNull final View view, final Bundle savedInstanceState) {
//		super.onViewCreated(view, savedInstanceState);
//
//		// Create the log adapter, initially with null cursor
//		mLogAdapter = new UARTLogAdapter(requireContext());
//
//		//		An activity that displays a list of items by binding to a data source such as an array or Cursor,
////		and exposes event handlers when the user selects an item.
//		setListAdapter(mLogAdapter); //
//
////		An activity that displays a list of items by binding to a data source such as an array or Cursor,
////		and exposes event handlers when the user selects an item.
////		ListView listv = (ListView) view.findViewById(R.id.list);
////		listv.setAdapter(mLogAdapter); //
//	}

	public void bindView(final View view, final Context context, final Cursor cursor) {
//		Long, int, string
		final UARTControlFragment.ViewHolder holder = (UARTControlFragment.ViewHolder) view.getTag();
		final int level = cursor.getInt(2 /* LEVEL */);
		if(level != LogContract.Log.Level.INFO){
			holder.data.setText(cursor.getString(3 /* DATA */));
		}
	}

	private class ViewHolder {
		private TextView time;
		private TextView data;
	}

	@Override
	public void onItemClick(final AdapterView<?> parent, final View view, final int position, final long id) {
		if (mEditMode) {
			Command command = mConfiguration.getCommands()[position];
			if (command == null)
				mConfiguration.getCommands()[position] = command = new Command();
			final UARTEditDialog dialog = UARTEditDialog.getInstance(position, command);
			dialog.show(getChildFragmentManager(), null);
		} else {
			final Command command = (Command)mAdapter.getItem(position);
			final Command.Eol eol = command.getEol();
			String text = command.getCommand();
			if (text == null)
				text = "";
			switch (eol) {
				case CR_LF:
					text = text.replaceAll("\n", "\r\n");
					break;
				case CR:
					text = text.replaceAll("\n", "\r");
					break;
			}
			final UARTInterface uart = (UARTInterface) getActivity();
			uart.send(text);
		}
	}

	@Override
	public void onConfigurationModified() {
		mAdapter.notifyDataSetChanged();
	}

	@Override
	public void onConfigurationChanged(final UartConfiguration configuration) {
		mConfiguration = configuration;
		mAdapter.setConfiguration(configuration);
	}

	@Override
	public void setEditMode(final boolean editMode) {
		mEditMode = editMode;
		mAdapter.setEditMode(mEditMode);
	}

	@NonNull
	@Override
//	Instantiate and return a new Loader for the given ID.
	public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
		switch (id) {
			case LOG_REQUEST_ID: {
				return new CursorLoader(requireContext(), mLogSession.getSessionEntriesUri(), LOG_PROJECTION, null, null, LogContract.Log.TIME);
			}
		}
		throw new UnsupportedOperationException("Could not create loader with ID " + id);
	}

	@Override
	public void onLoadFinished(@NonNull final Loader<Cursor> loader, final Cursor data) {
		// Here we have to restore the old saved scroll position, or scroll to the bottom if before adding new events it was scrolled to the bottom
		mLogAdapter.swapCursor(data);
	}

	@Override
	public void onLoaderReset(@NonNull final Loader<Cursor> loader) {
		mLogAdapter.swapCursor(null);
	}




	private static IntentFilter makeIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(BleProfileService.BROADCAST_CONNECTION_STATE);
		return intentFilter;
	}

}
