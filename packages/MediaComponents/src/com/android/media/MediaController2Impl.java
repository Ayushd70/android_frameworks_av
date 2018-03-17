/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.media;

import static android.media.MediaSession2.*;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioAttributes;
import android.media.MediaController2;
import android.media.MediaController2.ControllerCallback;
import android.media.MediaController2.PlaybackInfo;
import android.media.MediaItem2;
import android.media.MediaSession2;
import android.media.MediaSession2.Command;
import android.media.MediaSession2.CommandButton;
import android.media.MediaSession2.CommandGroup;
import android.media.MediaSession2.PlaylistParams;
import android.media.MediaSessionService2;
import android.media.PlaybackState2;
import android.media.Rating2;
import android.media.SessionToken2;
import android.media.update.MediaController2Provider;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.support.annotation.GuardedBy;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class MediaController2Impl implements MediaController2Provider {
    private static final String TAG = "MediaController2";
    private static final boolean DEBUG = true; // TODO(jaewan): Change

    private final MediaController2 mInstance;
    private final Context mContext;
    private final Object mLock = new Object();

    private final MediaSession2CallbackStub mSessionCallbackStub;
    private final SessionToken2 mToken;
    private final ControllerCallback mCallback;
    private final Executor mCallbackExecutor;
    private final IBinder.DeathRecipient mDeathRecipient;

    @GuardedBy("mLock")
    private SessionServiceConnection mServiceConnection;
    @GuardedBy("mLock")
    private boolean mIsReleased;
    @GuardedBy("mLock")
    private PlaybackState2 mPlaybackState;
    @GuardedBy("mLock")
    private List<MediaItem2> mPlaylist;
    @GuardedBy("mLock")
    private PlaylistParams mPlaylistParams;
    @GuardedBy("mLock")
    private PlaybackInfo mPlaybackInfo;
    @GuardedBy("mLock")
    private PendingIntent mSessionActivity;
    @GuardedBy("mLock")
    private CommandGroup mAllowedCommands;

    // Assignment should be used with the lock hold, but should be used without a lock to prevent
    // potential deadlock.
    // Postfix -Binder is added to explicitly show that it's potentially remote process call.
    // Technically -Interface is more correct, but it may misread that it's interface (vs class)
    // so let's keep this postfix until we find better postfix.
    @GuardedBy("mLock")
    private volatile IMediaSession2 mSessionBinder;

    // TODO(jaewan): Require session activeness changed listener, because controller can be
    //               available when the session's player is null.
    public MediaController2Impl(Context context, MediaController2 instance, SessionToken2 token,
            Executor executor, ControllerCallback callback) {
        mInstance = instance;
        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback shouldn't be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor shouldn't be null");
        }
        mContext = context;
        mSessionCallbackStub = new MediaSession2CallbackStub(this);
        mToken = token;
        mCallback = callback;
        mCallbackExecutor = executor;
        mDeathRecipient = () -> {
            mInstance.close();
        };

        mSessionBinder = null;
    }

    @Override
    public void initialize() {
        // TODO(jaewan): More sanity checks.
        if (mToken.getType() == SessionToken2.TYPE_SESSION) {
            // Session
            mServiceConnection = null;
            connectToSession(SessionToken2Impl.from(mToken).getSessionBinder());
        } else {
            // Session service
            if (Process.myUid() == Process.SYSTEM_UID) {
                // It's system server (MediaSessionService) that wants to monitor session.
                // Don't bind if able..
                IMediaSession2 binder = SessionToken2Impl.from(mToken).getSessionBinder();
                if (binder != null) {
                    // Use binder in the session token instead of bind by its own.
                    // Otherwise server will holds the binding to the service *forever* and service
                    // will never stop.
                    mServiceConnection = null;
                    connectToSession(SessionToken2Impl.from(mToken).getSessionBinder());
                    return;
                } else if (DEBUG) {
                    // Should happen only when system server wants to dispatch media key events to
                    // a dead service.
                    Log.d(TAG, "System server binds to a session service. Should unbind"
                            + " immediately after the use.");
                }
            }
            mServiceConnection = new SessionServiceConnection();
            connectToService();
        }
    }

    private void connectToService() {
        // Service. Needs to get fresh binder whenever connection is needed.
        SessionToken2Impl impl = SessionToken2Impl.from(mToken);
        final Intent intent = new Intent(MediaSessionService2.SERVICE_INTERFACE);
        intent.setClassName(mToken.getPackageName(), impl.getServiceName());

        // Use bindService() instead of startForegroundService() to start session service for three
        // reasons.
        // 1. Prevent session service owner's stopSelf() from destroying service.
        //    With the startForegroundService(), service's call of stopSelf() will trigger immediate
        //    onDestroy() calls on the main thread even when onConnect() is running in another
        //    thread.
        // 2. Minimize APIs for developers to take care about.
        //    With bindService(), developers only need to take care about Service.onBind()
        //    but Service.onStartCommand() should be also taken care about with the
        //    startForegroundService().
        // 3. Future support for UI-less playback
        //    If a service wants to keep running, it should be either foreground service or
        //    bounded service. But there had been request for the feature for system apps
        //    and using bindService() will be better fit with it.
        boolean result;
        if (Process.myUid() == Process.SYSTEM_UID) {
            // Use bindServiceAsUser() for binding from system service to avoid following warning.
            // ContextImpl: Calling a method in the system process without a qualified user
            result = mContext.bindServiceAsUser(intent, mServiceConnection, Context.BIND_AUTO_CREATE,
                    UserHandle.getUserHandleForUid(mToken.getUid()));
        } else {
            result = mContext.bindService(intent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }
        if (!result) {
            Log.w(TAG, "bind to " + mToken + " failed");
        } else if (DEBUG) {
            Log.d(TAG, "bind to " + mToken + " success");
        }
    }

    private void connectToSession(IMediaSession2 sessionBinder) {
        try {
            sessionBinder.connect(mSessionCallbackStub, mContext.getPackageName());
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to call connection request. Framework will retry"
                    + " automatically");
        }
    }

    @Override
    public void close_impl() {
        if (DEBUG) {
            Log.d(TAG, "release from " + mToken);
        }
        final IMediaSession2 binder;
        synchronized (mLock) {
            if (mIsReleased) {
                // Prevent re-enterance from the ControllerCallback.onDisconnected()
                return;
            }
            mIsReleased = true;
            if (mServiceConnection != null) {
                mContext.unbindService(mServiceConnection);
                mServiceConnection = null;
            }
            binder = mSessionBinder;
            mSessionBinder = null;
            mSessionCallbackStub.destroy();
        }
        if (binder != null) {
            try {
                binder.asBinder().unlinkToDeath(mDeathRecipient, 0);
                binder.release(mSessionCallbackStub);
            } catch (RemoteException e) {
                // No-op.
            }
        }
        mCallbackExecutor.execute(() -> {
            mCallback.onDisconnected(mInstance);
        });
    }

    IMediaSession2 getSessionBinder() {
        return mSessionBinder;
    }

    MediaSession2CallbackStub getControllerStub() {
        return mSessionCallbackStub;
    }

    Executor getCallbackExecutor() {
        return mCallbackExecutor;
    }

    Context getContext() {
        return mContext;
    }

    MediaController2 getInstance() {
        return mInstance;
    }

    // Returns session binder if the controller can send the command.
    IMediaSession2 getSessionBinderIfAble(int commandCode) {
        synchronized (mLock) {
            if (!mAllowedCommands.hasCommand(commandCode)) {
                // Cannot send because isn't allowed to.
                Log.w(TAG, "Controller isn't allowed to call command, commandCode="
                        + commandCode);
                return null;
            }
        }
        // TODO(jaewan): Should we do this with the lock hold?
        final IMediaSession2 binder = mSessionBinder;
        if (binder == null) {
            // Cannot send because disconnected.
            Log.w(TAG, "Session is disconnected");
        }
        return binder;
    }

    // Returns session binder if the controller can send the command.
    IMediaSession2 getSessionBinderIfAble(Command command) {
        synchronized (mLock) {
            if (!mAllowedCommands.hasCommand(command)) {
                Log.w(TAG, "Controller isn't allowed to call command, command=" + command);
                return null;
            }
        }
        // TODO(jaewan): Should we do this with the lock hold?
        final IMediaSession2 binder = mSessionBinder;
        if (binder == null) {
            // Cannot send because disconnected.
            Log.w(TAG, "Session is disconnected");
        }
        return binder;
    }

    @Override
    public SessionToken2 getSessionToken_impl() {
        return mToken;
    }

    @Override
    public boolean isConnected_impl() {
        final IMediaSession2 binder = mSessionBinder;
        return binder != null;
    }

    @Override
    public void play_impl() {
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_PLAY);
    }

    @Override
    public void pause_impl() {
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_PAUSE);
    }

    @Override
    public void stop_impl() {
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_STOP);
    }

    @Override
    public void skipToPreviousItem_impl() {
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_SKIP_PREV_ITEM);
    }

    @Override
    public void skipToNextItem_impl() {
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_SKIP_NEXT_ITEM);
    }

    private void sendTransportControlCommand(int commandCode) {
        sendTransportControlCommand(commandCode, null);
    }

    private void sendTransportControlCommand(int commandCode, Bundle args) {
        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.sendTransportControlCommand(mSessionCallbackStub, commandCode, args);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////
    // TODO(jaewan): Implement follows
    //////////////////////////////////////////////////////////////////////////////////////
    @Override
    public PendingIntent getSessionActivity_impl() {
        return mSessionActivity;
    }

    @Override
    public void setVolumeTo_impl(int value, int flags) {
        // TODO(hdmoon): sanity check
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAYBACK_SET_VOLUME);
        if (binder != null) {
            try {
                binder.setVolumeTo(mSessionCallbackStub, value, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void adjustVolume_impl(int direction, int flags) {
        // TODO(hdmoon): sanity check
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAYBACK_SET_VOLUME);
        if (binder != null) {
            try {
                binder.adjustVolume(mSessionCallbackStub, direction, flags);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public void prepareFromUri_impl(Uri uri, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PREPARE_FROM_URI);
        if (uri == null) {
            throw new IllegalArgumentException("uri shouldn't be null");
        }
        if (binder != null) {
            try {
                binder.prepareFromUri(mSessionCallbackStub, uri, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void prepareFromSearch_impl(String query, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PREPARE_FROM_SEARCH);
        if (TextUtils.isEmpty(query)) {
            throw new IllegalArgumentException("query shouldn't be empty");
        }
        if (binder != null) {
            try {
                binder.prepareFromSearch(mSessionCallbackStub, query, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void prepareFromMediaId_impl(String mediaId, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PREPARE_FROM_MEDIA_ID);
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId shouldn't be null");
        }
        if (binder != null) {
            try {
                binder.prepareFromMediaId(mSessionCallbackStub, mediaId, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void playFromUri_impl(Uri uri, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAY_FROM_URI);
        if (uri == null) {
            throw new IllegalArgumentException("uri shouldn't be null");
        }
        if (binder != null) {
            try {
                binder.playFromUri(mSessionCallbackStub, uri, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void playFromSearch_impl(String query, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAY_FROM_SEARCH);
        if (TextUtils.isEmpty(query)) {
            throw new IllegalArgumentException("query shouldn't be empty");
        }
        if (binder != null) {
            try {
                binder.playFromSearch(mSessionCallbackStub, query, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void playFromMediaId_impl(String mediaId, Bundle extras) {
        final IMediaSession2 binder = getSessionBinderIfAble(COMMAND_CODE_PLAY_FROM_MEDIA_ID);
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId shouldn't be null");
        }
        if (binder != null) {
            try {
                binder.playFromMediaId(mSessionCallbackStub, mediaId, extras);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void setRating_impl(String mediaId, Rating2 rating) {
        if (mediaId == null) {
            throw new IllegalArgumentException("mediaId shouldn't be null");
        }
        if (rating == null) {
            throw new IllegalArgumentException("rating shouldn't be null");
        }

        final IMediaSession2 binder = mSessionBinder;
        if (binder != null) {
            try {
                binder.setRating(mSessionCallbackStub, mediaId, rating.toBundle());
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            // TODO(jaewan): Handle.
        }
    }

    @Override
    public void sendCustomCommand_impl(Command command, Bundle args, ResultReceiver cb) {
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        final IMediaSession2 binder = getSessionBinderIfAble(command);
        if (binder != null) {
            try {
                binder.sendCustomCommand(mSessionCallbackStub, command.toBundle(), args, cb);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot connect to the service or the session is gone", e);
            }
        } else {
            Log.w(TAG, "Session isn't active", new IllegalStateException());
        }
    }

    @Override
    public List<MediaItem2> getPlaylist_impl() {
        synchronized (mLock) {
            return mPlaylist;
        }
    }

    @Override
    public void prepare_impl() {
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_PREPARE);
    }

    @Override
    public void fastForward_impl() {
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_FAST_FORWARD);
    }

    @Override
    public void rewind_impl() {
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_REWIND);
    }

    @Override
    public void seekTo_impl(long pos) {
        if (pos < 0) {
            throw new IllegalArgumentException("position shouldn't be negative");
        }
        Bundle args = new Bundle();
        args.putLong(MediaSession2Stub.ARGUMENT_KEY_POSITION, pos);
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_SEEK_TO, args);
    }

    @Override
    public void skipToPlaylistItem_impl(MediaItem2 item) {
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }

        // TODO(jaewan): Implement this
        /*
        Bundle args = new Bundle();
        args.putInt(MediaSession2Stub.ARGUMENT_KEY_ITEM_INDEX, item);
        sendTransportControlCommand(
                MediaSession2.COMMAND_CODE_PLAYLIST_SKIP_TO_PLAYLIST_ITEM, args);
        */
    }

    @Override
    public PlaybackState2 getPlaybackState_impl() {
        synchronized (mLock) {
            return mPlaybackState;
        }
    }

    @Override
    public void addPlaylistItem_impl(int index, MediaItem2 item) {
        // TODO(jaewan): Implement (b/73149584)
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
    }

    @Override
    public void removePlaylistItem_impl(MediaItem2 item) {
        // TODO(jaewan): Implement (b/73149584)
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
    }

    @Override
    public void replacePlaylistItem_impl(int index, MediaItem2 item) {
        // TODO: Implement this (b/73149407)
        if (index < 0) {
            throw new IllegalArgumentException("index shouldn't be negative");
        }
        if (item == null) {
            throw new IllegalArgumentException("item shouldn't be null");
        }
    }

    @Override
    public PlaylistParams getPlaylistParams_impl() {
        synchronized (mLock) {
            return mPlaylistParams;
        }
    }

    @Override
    public PlaybackInfo getPlaybackInfo_impl() {
        synchronized (mLock) {
            return mPlaybackInfo;
        }
    }

    @Override
    public void setPlaylistParams_impl(PlaylistParams params) {
        if (params == null) {
            throw new IllegalArgumentException("params shouldn't be null");
        }
        Bundle args = new Bundle();
        args.putBundle(MediaSession2Stub.ARGUMENT_KEY_PLAYLIST_PARAMS, params.toBundle());
        sendTransportControlCommand(MediaSession2.COMMAND_CODE_PLAYBACK_SET_PLAYLIST_PARAMS, args);
    }

    @Override
    public int getPlayerState_impl() {
        // TODO(jaewan): Implement
        return 0;
    }

    @Override
    public long getPosition_impl() {
        // TODO(jaewan): Implement
        return 0;
    }

    @Override
    public float getPlaybackSpeed_impl() {
        // TODO(jaewan): Implement
        return 0;
    }

    @Override
    public long getBufferedPosition_impl() {
        // TODO(jaewan): Implement
        return 0;
    }

    @Override
    public MediaItem2 getCurrentMediaItem_impl() {
        // TODO(jaewan): Implement
        return null;
    }

    void pushPlaybackStateChanges(final PlaybackState2 state) {
        synchronized (mLock) {
            mPlaybackState = state;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onPlaybackStateChanged(mInstance, state);
        });
    }

    void pushPlaylistParamsChanges(final PlaylistParams params) {
        synchronized (mLock) {
            mPlaylistParams = params;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onPlaylistParamsChanged(mInstance, params);
        });
    }

    void pushPlaybackInfoChanges(final PlaybackInfo info) {
        synchronized (mLock) {
            mPlaybackInfo = info;
        }
        mCallbackExecutor.execute(() -> {
            if (!mInstance.isConnected()) {
                return;
            }
            mCallback.onPlaybackInfoChanged(mInstance, info);
        });
    }

    void pushPlaylistChanges(final List<MediaItem2> playlist) {
        synchronized (mLock) {
            mPlaylist = playlist;
            mCallbackExecutor.execute(() -> {
                if (!mInstance.isConnected()) {
                    return;
                }
                mCallback.onPlaylistChanged(mInstance, playlist);
            });
        }
    }

    // Should be used without a lock to prevent potential deadlock.
    void onConnectedNotLocked(IMediaSession2 sessionBinder,
            final CommandGroup allowedCommands, final PlaybackState2 state, final PlaybackInfo info,
            final PlaylistParams params, final List<MediaItem2> playlist,
            final PendingIntent sessionActivity) {
        if (DEBUG) {
            Log.d(TAG, "onConnectedNotLocked sessionBinder=" + sessionBinder
                    + ", allowedCommands=" + allowedCommands);
        }
        boolean close = false;
        try {
            if (sessionBinder == null || allowedCommands == null) {
                // Connection rejected.
                close = true;
                return;
            }
            synchronized (mLock) {
                if (mIsReleased) {
                    return;
                }
                if (mSessionBinder != null) {
                    Log.e(TAG, "Cannot be notified about the connection result many times."
                            + " Probably a bug or malicious app.");
                    close = true;
                    return;
                }
                mAllowedCommands = allowedCommands;
                mPlaybackState = state;
                mPlaybackInfo = info;
                mPlaylistParams = params;
                mPlaylist = playlist;
                mSessionActivity = sessionActivity;
                mSessionBinder = sessionBinder;
                try {
                    // Implementation for the local binder is no-op,
                    // so can be used without worrying about deadlock.
                    mSessionBinder.asBinder().linkToDeath(mDeathRecipient, 0);
                } catch (RemoteException e) {
                    if (DEBUG) {
                        Log.d(TAG, "Session died too early.", e);
                    }
                    close = true;
                    return;
                }
            }
            // TODO(jaewan): Keep commands to prevents illegal API calls.
            mCallbackExecutor.execute(() -> {
                // Note: We may trigger ControllerCallbacks with the initial values
                // But it's hard to define the order of the controller callbacks
                // Only notify about the
                mCallback.onConnected(mInstance, allowedCommands);
            });
        } finally {
            if (close) {
                // Trick to call release() without holding the lock, to prevent potential deadlock
                // with the developer's custom lock within the ControllerCallback.onDisconnected().
                mInstance.close();
            }
        }
    }

    void onCustomCommand(final Command command, final Bundle args,
            final ResultReceiver receiver) {
        if (DEBUG) {
            Log.d(TAG, "onCustomCommand cmd=" + command);
        }
        mCallbackExecutor.execute(() -> {
            // TODO(jaewan): Double check if the controller exists.
            mCallback.onCustomCommand(mInstance, command, args, receiver);
        });
    }

    void onAllowedCommandsChanged(final CommandGroup commands) {
        mCallbackExecutor.execute(() -> {
            mCallback.onAllowedCommandsChanged(mInstance, commands);
        });
    }

    void onCustomLayoutChanged(final List<CommandButton> layout) {
        mCallbackExecutor.execute(() -> {
            mCallback.onCustomLayoutChanged(mInstance, layout);
        });
    }

    // This will be called on the main thread.
    private class SessionServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Note that it's always main-thread.
            if (DEBUG) {
                Log.d(TAG, "onServiceConnected " + name + " " + this);
            }
            // Sanity check
            if (!mToken.getPackageName().equals(name.getPackageName())) {
                Log.wtf(TAG, name + " was connected, but expected pkg="
                        + mToken.getPackageName() + " with id=" + mToken.getId());
                return;
            }
            final IMediaSession2 sessionBinder = IMediaSession2.Stub.asInterface(service);
            connectToSession(sessionBinder);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Temporal lose of the binding because of the service crash. System will automatically
            // rebind, so just no-op.
            // TODO(jaewan): Really? Either disconnect cleanly or
            if (DEBUG) {
                Log.w(TAG, "Session service " + name + " is disconnected.");
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Permanent lose of the binding because of the service package update or removed.
            // This SessionServiceRecord will be removed accordingly, but forget session binder here
            // for sure.
            mInstance.close();
        }
    }

    public static final class PlaybackInfoImpl implements PlaybackInfoProvider {

        private static final String KEY_PLAYBACK_TYPE =
                "android.media.playbackinfo_impl.playback_type";
        private static final String KEY_CONTROL_TYPE =
                "android.media.playbackinfo_impl.control_type";
        private static final String KEY_MAX_VOLUME =
                "android.media.playbackinfo_impl.max_volume";
        private static final String KEY_CURRENT_VOLUME =
                "android.media.playbackinfo_impl.current_volume";
        private static final String KEY_AUDIO_ATTRIBUTES =
                "android.media.playbackinfo_impl.audio_attrs";

        private final Context mContext;
        private final PlaybackInfo mInstance;

        private final int mPlaybackType;
        private final int mControlType;
        private final int mMaxVolume;
        private final int mCurrentVolume;
        private final AudioAttributes mAudioAttrs;

        private PlaybackInfoImpl(Context context, int playbackType, AudioAttributes attrs,
                int controlType, int max, int current) {
            mContext = context;
            mPlaybackType = playbackType;
            mAudioAttrs = attrs;
            mControlType = controlType;
            mMaxVolume = max;
            mCurrentVolume = current;
            mInstance = new PlaybackInfo(this);
        }

        @Override
        public int getPlaybackType_impl() {
            return mPlaybackType;
        }

        @Override
        public AudioAttributes getAudioAttributes_impl() {
            return mAudioAttrs;
        }

        @Override
        public int getControlType_impl() {
            return mControlType;
        }

        @Override
        public int getMaxVolume_impl() {
            return mMaxVolume;
        }

        @Override
        public int getCurrentVolume_impl() {
            return mCurrentVolume;
        }

        public PlaybackInfo getInstance() {
            return mInstance;
        }

        public Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_PLAYBACK_TYPE, mPlaybackType);
            bundle.putInt(KEY_CONTROL_TYPE, mControlType);
            bundle.putInt(KEY_MAX_VOLUME, mMaxVolume);
            bundle.putInt(KEY_CURRENT_VOLUME, mCurrentVolume);
            bundle.putParcelable(KEY_AUDIO_ATTRIBUTES, mAudioAttrs);
            return bundle;
        }

        public static PlaybackInfo createPlaybackInfo(Context context, int playbackType,
                AudioAttributes attrs, int controlType, int max, int current) {
            return new PlaybackInfoImpl(context, playbackType, attrs, controlType, max, current)
                    .getInstance();
        }

        public static PlaybackInfo fromBundle(Context context, Bundle bundle) {
            if (bundle == null) {
                return null;
            }
            final int volumeType = bundle.getInt(KEY_PLAYBACK_TYPE);
            final int volumeControl = bundle.getInt(KEY_CONTROL_TYPE);
            final int maxVolume = bundle.getInt(KEY_MAX_VOLUME);
            final int currentVolume = bundle.getInt(KEY_CURRENT_VOLUME);
            final AudioAttributes attrs = bundle.getParcelable(KEY_AUDIO_ATTRIBUTES);

            return createPlaybackInfo(
                    context, volumeType, attrs, volumeControl, maxVolume, currentVolume);
        }
    }
}
