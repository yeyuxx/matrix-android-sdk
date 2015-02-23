package org.matrix.matrixandroidsdk.fragments;

import android.app.AlertDialog;
import android.content.DialogInterface;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.ImageMessage;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.Message;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.matrixandroidsdk.Matrix;
import org.matrix.matrixandroidsdk.R;
import org.matrix.matrixandroidsdk.ToastErrorHandler;
import org.matrix.matrixandroidsdk.activity.CommonActivityUtils;
import org.matrix.matrixandroidsdk.activity.RoomActivity;
import org.matrix.matrixandroidsdk.adapters.MessageRow;
import org.matrix.matrixandroidsdk.adapters.MessagesAdapter;

import java.util.ArrayList;
import java.util.List;

import retrofit.RetrofitError;

/**
 * UI Fragment containing matrix messages for a given room.
 * Contains {@link MatrixMessagesFragment} as a nested fragment to do the work.
 */
public class MatrixMessageListFragment extends Fragment implements MatrixMessagesFragment.MatrixMessagesListener, MessagesAdapter.MessagesAdapterClickListener {

    public static interface MatrixMessageListFragmentListener {
        /**
         * Called when the first batch of messages is loaded.
         */
        public void onInitialMessagesLoaded();
    }

    private static final String TAG_FRAGMENT_MESSAGE_DETAILS = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_MESSAGE_DETALS";

    public static final String ARG_ROOM_ID = "org.matrix.matrixandroidsdk.fragments.MatrixMessageListFragment.ARG_ROOM_ID";
    public static final String ARG_LAYOUT_ID = "org.matrix.matrixandroidsdk.fragments.MatrixMessageListFragment.ARG_LAYOUT_ID";

    private static final String TAG_FRAGMENT_MATRIX_MESSAGES = "org.matrix.androidsdk.RoomActivity.TAG_FRAGMENT_MATRIX_MESSAGES";
    private static final String LOG_TAG = "ErrorListener";

    // listener to warn activity that the initial sync is done
    private MatrixMessageListFragmentListener mMatrixMessageListFragmentListener = null;

    public static MatrixMessageListFragment newInstance(String roomId) {
        return newInstance(roomId, R.layout.fragment_matrix_message_list_fragment);
    }

    public static MatrixMessageListFragment newInstance(String roomId, int layoutResId) {
        MatrixMessageListFragment f = new MatrixMessageListFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ROOM_ID, roomId);
        args.putInt(ARG_LAYOUT_ID, layoutResId);
        f.setArguments(args);
        return f;
    }

    private MatrixMessagesFragment mMatrixMessagesFragment;
    private MessagesAdapter mAdapter;
    private ListView mMessageListView;
    private Handler mUiHandler;
    private MXSession mSession;
    private Room mRoom;

    private AlertDialog mRedactResendAlert = null;

    // avoid to catch up old content if the initial sync is in progress
    private boolean mIsInitialSyncing = true;
    private boolean mIsCatchingUp = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        // for dispatching data to add to the adapter we need to be on the main thread
        mUiHandler = new Handler(Looper.getMainLooper());

        mSession = Matrix.getInstance(getActivity()).getDefaultSession();

        Bundle args = getArguments();
        String roomId = args.getString(ARG_ROOM_ID);
        mRoom = mSession.getDataHandler().getRoom(roomId);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        Bundle args = getArguments();
        View v = inflater.inflate(args.getInt(ARG_LAYOUT_ID), container, false);
        mMessageListView = ((ListView)v.findViewById(R.id.listView_messages));
        if (mAdapter == null) {
            // only init the adapter if it wasn't before, so we can preserve messages/position.
            mAdapter = new MessagesAdapter(getActivity(),
                    R.layout.adapter_item_messages,
                    R.layout.adapter_item_images,
                    R.layout.adapter_item_message_notice,
                    R.layout.adapter_item_message_emote
            );
        }
        mAdapter.setTypingUsers(mRoom.getTypingUsers());
        mMessageListView.setAdapter(mAdapter);
        mMessageListView.setSelection(0);
        mMessageListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                MatrixMessageListFragment.this.onItemClick(position);
            }
        });

        mAdapter.setMessagesAdapterClickListener(new MessagesAdapter.MessagesAdapterClickListener() {
            @Override
            public void onItemClick(int position) {
                MatrixMessageListFragment.this.onItemClick(position);
            }
        });

        return v;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Bundle args = getArguments();
        FragmentManager fm = getActivity().getSupportFragmentManager();
        mMatrixMessagesFragment = (MatrixMessagesFragment) fm.findFragmentByTag(TAG_FRAGMENT_MATRIX_MESSAGES);

        if (mMatrixMessagesFragment == null) {
            // this fragment controls all the logic for handling messages / API calls
            mMatrixMessagesFragment = MatrixMessagesFragment.newInstance(args.getString(ARG_ROOM_ID), this);
            fm.beginTransaction().add(mMatrixMessagesFragment, TAG_FRAGMENT_MATRIX_MESSAGES).commit();
        }
        else {
            // Reset the listener because this is not done when the system restores the fragment (newInstance is not called)
            mMatrixMessagesFragment.setMatrixMessagesListener(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mMessageListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                //check only when the user scrolls the content
                if  (scrollState == SCROLL_STATE_TOUCH_SCROLL) {
                    int firstVisibleRow = mMessageListView.getFirstVisiblePosition();
                    int lastVisibleRow = mMessageListView.getLastVisiblePosition();
                    int count = mMessageListView.getCount();

                    // All the messages are displayed within the same page
                    if ((count > 0) && (firstVisibleRow == 0) && (lastVisibleRow == (count - 1)) && (!mIsInitialSyncing)) {
                        requestHistory();
                    }
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                // If we scroll to the top, load more history
                // so not load history if there is an initial sync progress
                // or the whole room content fits in a single page
                if ((firstVisibleItem == 0) && (!mIsInitialSyncing) && (visibleItemCount != totalItemCount)) {
                    requestHistory();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void sendTextMessage(String body) {
        sendMessage(Message.MSGTYPE_TEXT, body);
    }

    private void sendMessage(String msgType, String body) {
        Message message = new Message();
        message.msgtype = msgType;
        message.body = body;
        send(message);
    }

    public void sendImage(ImageMessage imageMessage) {
        send(imageMessage);
    }

    public void sendEmote(String emote) {
        sendMessage(Message.MSGTYPE_EMOTE, emote);
    }

    private void resend(Event event) {
        // remove the event
        mSession.getDataHandler().deleteRoomEvent(event);
        mAdapter.removeEventById(event.eventId);

        // send it again
        final Message message = JsonUtils.toMessage(event.content);

        // resend an image ?
        if (message instanceof ImageMessage) {
            ImageMessage imageMessage = (ImageMessage)message;

            // media has not been uploaded
            if (imageMessage.isLocalContent()) {
                if (getActivity() instanceof RoomActivity) {
                    ((RoomActivity)getActivity()).uploadImageContent(imageMessage.url, imageMessage.info.mimetype, imageMessage);

                } else {
                    // don't know how to resend the event
                    return;
                }
            }
        }

        send(message);
    }

    private void send(Message message) {
        Event dummyEvent = new Event();
        dummyEvent.type = Event.EVENT_TYPE_MESSAGE;
        dummyEvent.content = JsonUtils.toJson(message);
        dummyEvent.originServerTs = System.currentTimeMillis();
        dummyEvent.userId = mSession.getCredentials().userId;

        final MessageRow tmpRow = new MessageRow(dummyEvent, mRoom.getLiveState());
        tmpRow.setSentState(MessageRow.SentState.SENDING);
        mAdapter.add(tmpRow);
        // NotifyOnChange has been disabled to avoid useless refreshes
        mAdapter.notifyDataSetChanged();

        mMatrixMessagesFragment.send(message, new ApiCallback<Event>() {
            @Override
            public void onSuccess(Event event) {
                mAdapter.remove(tmpRow);
                mAdapter.add(event, mRoom.getLiveState());

                if (event.isUnsent) {
                    if (null != event.unsentException) {
                        if ((event.unsentException instanceof RetrofitError) && ((RetrofitError)event.unsentException).isNetworkError())  {
                            Toast.makeText(getActivity(), getActivity().getString(R.string.unable_to_send_message) + " : " + getActivity().getString(R.string.network_error), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(getActivity(), getActivity().getString(R.string.unable_to_send_message) + " : " + event.unsentException.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                        }
                    } else if (null != event.unsentMatrixError) {
                        Toast.makeText(getActivity(), getActivity().getString(R.string.unable_to_send_message) + " : " + event.unsentMatrixError.error + ".", Toast.LENGTH_LONG).show();
                    }
                }

                mAdapter.notifyDataSetChanged();
            }

            // theses 3 methods will never be called
            @Override
            public void onNetworkError(Exception e) {
            }

            @Override
            public void onMatrixError(MatrixError e) {
            }

            @Override
            public void onUnexpectedError(Exception e) {
            }
        });
    }

    private void displayLoadingProgress() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final View progressView = getActivity().findViewById(R.id.loading_room_content_progress);

                if (null != progressView) {
                    progressView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void dismissLoadingProgress() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final View progressView = getActivity().findViewById(R.id.loading_room_content_progress);

                if (null != progressView) {
                    progressView.setVisibility(View.GONE);
                }
            }
        });
    }

    public void requestHistory() {
        // avoid launching catchup if there is already one in progress
        if (!mIsCatchingUp) {
            mIsCatchingUp = true;
            final int firstPos = mMessageListView.getFirstVisiblePosition();

            boolean isStarted = mMatrixMessagesFragment.requestHistory(new SimpleApiCallback<Integer>() {
                @Override
                public void onSuccess(final Integer count) {
                    dismissLoadingProgress();

                    // Scroll the list down to where it was before adding rows to the top
                    mUiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            // refresh the list only at the end of the sync
                            // else the one by one message refresh gives a weird UX
                            // The application is almost frozen during the
                            mAdapter.notifyDataSetChanged();
                            mMessageListView.setSelection(firstPos + count);
                            mIsCatchingUp = false;
                        }
                    });
                }

                // TODO manage auto restart
                @Override
                public void onNetworkError(Exception e) {
                    Log.e(LOG_TAG, "Network error: " + e.getMessage());
                    dismissLoadingProgress();

                    MatrixMessageListFragment.this.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MatrixMessageListFragment.this.getActivity(), getActivity().getString(R.string.network_error), Toast.LENGTH_SHORT).show();
                            MatrixMessageListFragment.this.dismissLoadingProgress();
                            mIsCatchingUp = false;
                        }
                    });
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    dismissLoadingProgress();

                    Log.e(LOG_TAG, "Matrix error" + " : " + e.errcode + " - " + e.error);
                    // The access token was not recognized: log out
                    if (MatrixError.UNKNOWN_TOKEN.equals(e.errcode)) {
                        CommonActivityUtils.logout(MatrixMessageListFragment.this.getActivity());
                    }

                    final MatrixError matrixError = e;

                    MatrixMessageListFragment.this.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MatrixMessageListFragment.this.getActivity(), getActivity().getString(R.string.matrix_error) + " : " + matrixError.error, Toast.LENGTH_SHORT).show();
                            MatrixMessageListFragment.this.dismissLoadingProgress();
                            mIsCatchingUp = false;
                        }
                    });
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    dismissLoadingProgress();

                    Log.e(LOG_TAG, getActivity().getString(R.string.unexpected_error) + " : " + e.getMessage());
                    MatrixMessageListFragment.this.dismissLoadingProgress();
                    mIsCatchingUp = false;
                }
            });

            if (isStarted) {
                displayLoadingProgress();
            }
        }
    }

    private void redactEvent(String eventId) {
        // Do nothing on success, the event will be hidden when the redaction event comes down the event stream
        mMatrixMessagesFragment.redact(eventId,
                new SimpleApiCallback<Event>(new ToastErrorHandler(getActivity(), getActivity().getString(R.string.could_not_redact))));
    }

    @Override
    public void onLiveEvent(final Event event, final RoomState roomState) {
        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                if (Event.EVENT_TYPE_REDACTION.equals(event.type)) {
                    mAdapter.removeEventById(event.redacts);
                    mAdapter.notifyDataSetChanged();
                }
                else if (Event.EVENT_TYPE_TYPING.equals(event.type)) {
                    mAdapter.setTypingUsers(mRoom.getTypingUsers());
                }
                else  {
                    mAdapter.add(event, roomState);
                    mAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    @Override
    public void onBackEvent(final Event event, final RoomState roomState) {
        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                mAdapter.addToFront(event, roomState);
            }
        });
    }

    @Override
    public void onDeletedEvent(Event event) {
        mAdapter.removeEventById(event.eventId);
    }

    @Override
    public void onInitialMessagesLoaded() {
        // Jump to the bottom of the list
        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                // refresh the list only at the end of the sync
                // else the one by one message refresh gives a weird UX
                // The application is almost frozen during the
                mAdapter.notifyDataSetChanged();
                mMessageListView.setSelection(mAdapter.getCount() - 1);

                if (null != mMatrixMessageListFragmentListener) {
                    mMatrixMessageListFragmentListener.onInitialMessagesLoaded();
                }

                mIsInitialSyncing = false;
            }
        });
    }

    /**
     * Set the listener which will be informed of matrix messages. This setter is provided so either
     * a Fragment or an Activity can directly receive callbacks.
     * @param listener the listener for this fragment
     */
    public void setMatrixMessageListFragmentListener(MatrixMessageListFragmentListener listener) {
        mMatrixMessageListFragmentListener = listener;
    }

    /**
     * Item selection management
     */
    private static final int OPTION_CANCEL = 0;
    private static final int OPTION_RESEND = 1;
    private static final int OPTION_REDACT = 2;
    private static final int OPTION_MESSAGE_DETAILS = 3;

    private String[] buildOptionLabels(List<Integer> options) {
        String[] labels = new String[options.size()];
        for (int i = 0; i < options.size(); i++) {
            String label = "";
            switch (options.get(i)) {
                case OPTION_CANCEL:
                    label = getString(R.string.cancel);
                    break;
                case OPTION_RESEND:
                    label = getString(R.string.resend);
                    break;
                case OPTION_REDACT:
                    label = getString(R.string.redact);
                    break;
                case OPTION_MESSAGE_DETAILS:
                    label = getString(R.string.message_details);
                    break;
            }
            labels[i] = label;
        }

        return labels;
    }

    public void onItemClick(int position) {
        final MessageRow messageRow = mAdapter.getItem(position);
        final List<Integer> options = new ArrayList<Integer>();
        if (messageRow.getSentState() == MessageRow.SentState.NOT_SENT) {
            options.add(OPTION_RESEND);
        } else if (messageRow.getSentState() == MessageRow.SentState.SENT) {
            options.add(OPTION_REDACT);
        }

        // display the JSON
        options.add(OPTION_MESSAGE_DETAILS);

        // do not launch an other alert if the user did not manage this one.
        if ((options.size() != 0) && (null == mRedactResendAlert)) {
            options.add(OPTION_CANCEL);
            mRedactResendAlert = new AlertDialog.Builder(getActivity())
                    .setItems(buildOptionLabels(options), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            switch (options.get(which)) {
                                case OPTION_CANCEL:
                                    dialog.cancel();
                                    mRedactResendAlert = null;
                                    break;
                                case OPTION_RESEND:
                                    getActivity().runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            resend(messageRow.getEvent());
                                        }
                                    });
                                    mRedactResendAlert = null;
                                    break;
                                case OPTION_REDACT:
                                    getActivity().runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            redactEvent(messageRow.getEvent().eventId);
                                        }
                                    });
                                    mRedactResendAlert = null;
                                    break;
                                case OPTION_MESSAGE_DETAILS:
                                    getActivity().runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            FragmentManager fm =  getActivity().getSupportFragmentManager();

                                            MessageDetailsFragment fragment = (MessageDetailsFragment) fm.findFragmentByTag(TAG_FRAGMENT_MESSAGE_DETAILS);
                                            if (fragment != null) {
                                                fragment.dismissAllowingStateLoss();
                                            }
                                            fragment = MessageDetailsFragment.newInstance(messageRow.getEvent().toString());
                                            fragment.show(fm, TAG_FRAGMENT_MESSAGE_DETAILS);
                                        }
                                    });
                                    mRedactResendAlert = null;
                                    break;
                            }
                        }
                    })
                    .create();

            mRedactResendAlert.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    mRedactResendAlert = null;
                }
            });

            mRedactResendAlert.show();
        }
    }
}
