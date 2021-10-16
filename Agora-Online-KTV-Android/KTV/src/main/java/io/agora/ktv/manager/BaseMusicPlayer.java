package io.agora.ktv.manager;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import com.elvishew.xlog.Logger;
import com.elvishew.xlog.XLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import io.agora.baselibrary.util.KTVUtil;
import io.agora.ktv.bean.MemberMusicModel;
import io.agora.mediaplayer.IMediaPlayer;
import io.agora.mediaplayer.IMediaPlayerObserver;
import io.agora.rtc2.Constants;
import io.agora.rtc2.IRtcEngineEventHandler;

public abstract class BaseMusicPlayer extends IRtcEngineEventHandler implements IMediaPlayerObserver {
    protected final Logger.Builder mLogger = XLog.tag("MusicPlayer");

    protected final Context mContext;
    protected int mRole = Constants.CLIENT_ROLE_BROADCASTER;

    //主唱同步歌词给其他人
    private boolean mStopSyncLrc = true;
    private Thread mSyncLrcThread;

    //歌词实时刷新
    protected boolean mStopDisplayLrc = true;
    private Thread mDisplayThread;

    protected IMediaPlayer mPlayer;

    private static volatile long mRecvedPlayPosition = 0;//播放器播放position，ms
    private static volatile Long mLastRecvPlayPosTime = null;

    private Callback mCallback;

    protected static final int ACTION_UPDATE_TIME = 100;
    protected static final int ACTION_ONMUSIC_OPENING = ACTION_UPDATE_TIME + 1;
    protected static final int ACTION_ON_MUSIC_OPENCOMPLETED = ACTION_ONMUSIC_OPENING + 1;
    protected static final int ACTION_ON_MUSIC_OPENERROR = ACTION_ON_MUSIC_OPENCOMPLETED + 1;
    protected static final int ACTION_ON_MUSIC_PLAING = ACTION_ON_MUSIC_OPENERROR + 1;
    protected static final int ACTION_ON_MUSIC_PAUSE = ACTION_ON_MUSIC_PLAING + 1;
    protected static final int ACTION_ON_MUSIC_STOP = ACTION_ON_MUSIC_PAUSE + 1;
    protected static final int ACTION_ON_MUSIC_COMPLETED = ACTION_ON_MUSIC_STOP + 1;
    protected static final int ACTION_ON_RECEIVED_COUNT_DOWN = ACTION_ON_MUSIC_COMPLETED + 1;
    protected static final int ACTION_ON_RECEIVED_PLAY = ACTION_ON_RECEIVED_COUNT_DOWN + 1;
    protected static final int ACTION_ON_RECEIVED_PAUSE = ACTION_ON_RECEIVED_PLAY + 1;
    protected static final int ACTION_ON_RECEIVED_SYNC_TIME = ACTION_ON_RECEIVED_PAUSE + 1;
    protected static final int ACTION_ON_RECEIVED_TEST_DELAY = ACTION_ON_RECEIVED_SYNC_TIME + 1;
    protected static final int ACTION_ON_RECEIVED_REPLAY_TEST_DELAY = ACTION_ON_RECEIVED_TEST_DELAY + 1;
    protected static final int ACTION_ON_RECEIVED_CHANGED_ORIGLE = ACTION_ON_RECEIVED_REPLAY_TEST_DELAY + 1;

    public volatile Status mStatus = Status.IDLE;

    enum Status {
        IDLE(0), Opened(1), Started(2), Paused(3), Stopped(4);

        int value;

        Status(int value) {
            this.value = value;
        }

        public boolean isAtLeast(@NonNull Status state) {
            return compareTo(state) >= 0;
        }
    }

    protected final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == ACTION_UPDATE_TIME) {
                if (mCallback != null) {
                    mCallback.onMusicPositionChanged((long) msg.obj);
                }
            } else if (msg.what == ACTION_ONMUSIC_OPENING) {
                if (mCallback != null) {
                    mCallback.onMusicOpening();
                }
            } else if (msg.what == ACTION_ON_MUSIC_OPENCOMPLETED) {
                if (mCallback != null) {
                    mCallback.onMusicOpenCompleted((long) msg.obj);
                }
            } else if (msg.what == ACTION_ON_MUSIC_OPENERROR) {
                if (mCallback != null) {
                    mCallback.onMusicOpenError((int) msg.obj);
                }
            } else if (msg.what == ACTION_ON_MUSIC_PLAING) {
                if (mCallback != null) {
                    mCallback.onMusicPlaying();
                }
            } else if (msg.what == ACTION_ON_MUSIC_PAUSE) {
                if (mCallback != null) {
                    mCallback.onMusicPause();
                }
            } else if (msg.what == ACTION_ON_MUSIC_STOP) {
                if (mCallback != null) {
                    mCallback.onMusicStop();
                }
            } else if (msg.what == ACTION_ON_MUSIC_COMPLETED) {
                if (mCallback != null) {
                    mCallback.onMusicCompleted();
                }
            } else if (msg.what == ACTION_ON_RECEIVED_COUNT_DOWN) {
                Bundle data = msg.getData();
                int uid = data.getInt("uid");
                int time = data.getInt("time");
                String musicId = data.getString("musicId");
                onReceivedCountdown(uid, time, musicId);
            } else if (msg.what == ACTION_ON_RECEIVED_PLAY) {
                onReceivedStatusPlay((Integer) msg.obj);
            } else if (msg.what == ACTION_ON_RECEIVED_PAUSE) {
                onReceivedStatusPause((Integer) msg.obj);
            } else if (msg.what == ACTION_ON_RECEIVED_SYNC_TIME) {
                Bundle data = msg.getData();
                int uid = data.getInt("uid");
                long time = data.getLong("time");
                onReceivedSetLrcTime(uid, time);
            } else if (msg.what == ACTION_ON_RECEIVED_TEST_DELAY) {
                Bundle data = msg.getData();
                int uid = data.getInt("uid");
                long time = data.getLong("time");
                onReceivedTestDelay(uid, time);
            } else if (msg.what == ACTION_ON_RECEIVED_REPLAY_TEST_DELAY) {
                Bundle data = msg.getData();
                int uid = data.getInt("uid");
                long testDelayTime = data.getLong("testDelayTime");
                long time = data.getLong("time");
                onReceivedReplyTestDelay(uid, testDelayTime, time);
            } else if (msg.what == ACTION_ON_RECEIVED_CHANGED_ORIGLE) {
                Bundle data = msg.getData();
                int uid = data.getInt("uid");
                int mode = data.getInt("mode");
                onReceivedOriginalChanged(uid, mode);
            }
        }
    };

    public BaseMusicPlayer(Context mContext, int role, IMediaPlayer mPlayer) {
        this.mContext = mContext;
        this.mPlayer = mPlayer;
        reset();

        this.mPlayer.registerPlayerObserver(this);

        RoomManager.getInstance().getRtcEngine().addHandler(this);
        switchRole(role);
    }

    @Override
    public void onStreamMessage(int uid, int streamId, byte[] data) {
        JSONObject jsonMsg;
        try {
            String strMsg = new String(data);
//        KTVUtil.logD("onStreamMessage);"+strMsg);
            jsonMsg = new JSONObject(strMsg);

            String cmd = null;
            try {
                cmd = jsonMsg.getString("cmd");
            } catch (JSONException e) {
                e.printStackTrace();
            }

            if (cmd == null) return;

            Bundle bundle = null;
            int what = -1;

            switch (cmd) {
                case "setLrcTime":
                    long position = jsonMsg.getLong("time");
                    if (position == 0) {
                        mHandler.obtainMessage(ACTION_ON_RECEIVED_PLAY, uid).sendToTarget();
                    } else if (position == -1) {
                        mHandler.obtainMessage(ACTION_ON_RECEIVED_PAUSE, uid).sendToTarget();
                    } else {
                        bundle = new Bundle();
                        bundle.putInt("uid", uid);
                        bundle.putLong("time", position);
                        what = ACTION_ON_RECEIVED_SYNC_TIME;
                    }
                    break;
                case "countdown": {
                    // Only this situation we can play with a chorus
                    int time = jsonMsg.getInt("time");

                    bundle = new Bundle();
                    bundle.putInt("uid", uid);
                    bundle.putInt("time", time);
                    bundle.putString("musicId", jsonMsg.getString("musicId"));
                    what = ACTION_ON_RECEIVED_COUNT_DOWN;
                    break;
                }
                case "testDelay": {
                    long time = jsonMsg.getLong("time");
                    bundle = new Bundle();
                    bundle.putInt("uid", uid);
                    bundle.putLong("time", time);
                    what = ACTION_ON_RECEIVED_TEST_DELAY;
                }
                case "replyTestDelay": {
                    long testDelayTime = jsonMsg.getLong("testDelayTime");
                    long time = jsonMsg.getLong("time");
                    bundle = new Bundle();
                    bundle.putInt("uid", uid);
                    bundle.putLong("time", time);
                    bundle.putLong("testDelayTime", testDelayTime);
                    what = ACTION_ON_RECEIVED_REPLAY_TEST_DELAY;
                    break;
                }
                case "TrackMode": {
                    int mode = jsonMsg.getInt("mode");
                    bundle = new Bundle();
                    bundle.putInt("uid", uid);
                    bundle.putInt("mode", mode);
                    what = ACTION_ON_RECEIVED_CHANGED_ORIGLE;
                    break;
                }
            }
            if (what != -1) {
                Message message = Message.obtain(mHandler, what);
                message.setData(bundle);
                message.sendToTarget();
            }
        } catch (JSONException exp) {
            mLogger.e("onStreamMessage: failed parse json, error: " + exp.toString());
        }
    }

    public void sendCountdown(int time) {
        if (RoomManager.getInstance().mCurrentMemberMusic == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("cmd", "countdown");
        msg.put("time", time);
        msg.put("musicId", RoomManager.getInstance().mCurrentMemberMusic.getMusicId());
        JSONObject jsonMsg = new JSONObject(msg);
        int streamId = RoomManager.getInstance().getStreamId();
        RoomManager.getInstance().getRtcEngine().sendStreamMessage(streamId, jsonMsg.toString().getBytes());
    }

    private void reset() {
        mRecvedPlayPosition = 0;
        mLastRecvPlayPosTime = null;
        mAudioTrackIndex = 1;
        mStatus = Status.IDLE;
    }

    public void registerPlayerObserver(Callback mCallback) {
        this.mCallback = mCallback;
    }

    public void unregisterPlayerObserver() {
        this.mCallback = null;
    }

    public abstract void switchRole(int role);

    public abstract void prepare(@NonNull MemberMusicModel music);

    public void playByListener() {
        startDisplayLrc();
    }

    protected int open() {
        MemberMusicModel currentMusic = RoomManager.getInstance().mCurrentMemberMusic;
//        if (mRole != Constants.CLIENT_ROLE_BROADCASTER) {
//            mLogger.e("open error: current role is not broadcaster, abort playing");
//            return -1;
//        }

        if (mStatus.isAtLeast(Status.Opened)) {
            mLogger.e("open error: current player is in playing state already, abort playing");
            return -2;
        }

        if (!mStopDisplayLrc) {
            mLogger.e("open error: current player is recving remote streams, abort playing");
            return -3;
        }

        File fileMusic = currentMusic.getFileMusic();
        if (!fileMusic.exists()) {
            mLogger.e("open error: fileMusic is not exists");
            return -4;
        }

        File fileLrc = currentMusic.getFileLrc();
        if (!fileLrc.exists()) {
            mLogger.e("open error: fileLrc is not exists");
            return -5;
        }

        if (mPlayer == null) {
            return -6;
        }

        stopDisplayLrc();

        mAudioTrackIndex = 1;
        mLogger.i("open() called with: currentMusic = [%s]", currentMusic);
        mPlayer.open(fileMusic.getAbsolutePath(), 0);
        return 0;
    }

    protected void play() {
        mLogger.i("play() called");
        if (!mStatus.isAtLeast(Status.Opened)) {
            return;
        }

        if (mStatus == Status.Started)
            return;

        mStatus = Status.Started;
        mPlayer.play();
    }

    public void stop() {
        mLogger.i("stop() called");
        if (!mStatus.isAtLeast(Status.Started)) {
            return;
        }

        mPlayer.stop();
    }

    protected void pause() {
        mLogger.i("pause() called");
        if (!mStatus.isAtLeast(Status.Opened)) {
            return;
        }

        if (mStatus == Status.Paused)
            return;

        mPlayer.pause();
    }

    protected void resume() {
        mLogger.i("resume() called");
        if (!mStatus.isAtLeast(Status.Opened)) {
            return;
        }

        if (mStatus == Status.Started)
            return;

        mPlayer.resume();
    }

    public void togglePlay() {
        if (!mStatus.isAtLeast(Status.Started)) {
            return;
        }

        if (mStatus == Status.Started) {
            pause();
        } else if (mStatus == Status.Paused) {
            resume();
        }
    }

    protected int mAudioTrackIndex = 1;

    public void selectAudioTrack(int i) {
        //因为咪咕音乐没有音轨，只有左右声道，所以暂定如此
        mAudioTrackIndex = i;

        if (mAudioTrackIndex == 0) {
            mPlayer.setAudioDualMonoMode(1);
        } else {
            mPlayer.setAudioDualMonoMode(2);
        }
    }

    public boolean hasAccompaniment() {
        //因为咪咕音乐没有音轨，只有左右声道，所以暂定如此
        return true;
    }

    public void toggleOriginal() {
        if (mAudioTrackIndex == 0) {
            selectAudioTrack(1);
        } else {
            selectAudioTrack(0);
        }
    }

    public void setMusicVolume(int v) {
        mPlayer.adjustPlayoutVolume(v);
    }

    public void setMicVolume(int v) {
        RoomManager.getInstance().getRtcEngine().adjustRecordingSignalVolume(v);
    }

    public void seek(long d) {
        mPlayer.seek(d);
    }

    protected void startDisplayLrc() {
        mStopDisplayLrc = false;
        mDisplayThread = new Thread(() -> {
            long curTs = 0;
            long curTime;
            long offset;
            while (!mStopDisplayLrc && !Thread.currentThread().isInterrupted()) {
                if (mLastRecvPlayPosTime != null) {
                    curTime = System.currentTimeMillis();
                    offset = curTime - mLastRecvPlayPosTime;
                    if (offset <= 1000) {
                        curTs = mRecvedPlayPosition + offset;
                        mHandler.obtainMessage(ACTION_UPDATE_TIME, curTs).sendToTarget();
                    }
                }

                try {
                    Thread.sleep(50);
                } catch (InterruptedException exp) {
                    break;
                }
            }
        });
        mDisplayThread.setName("Thread-Display");
        mDisplayThread.start();
    }

    protected void stopDisplayLrc() {
        mStopDisplayLrc = true;
        if (mDisplayThread != null) {
            mDisplayThread.interrupt();
        }
    }

    private void startSyncLrc(String musicId, long duration) {
        mSyncLrcThread = new Thread(new Runnable() {

            @Override
            public void run() {
                mLogger.i("startSyncLrc: " + musicId);
                mStopSyncLrc = false;
                while (!mStopSyncLrc && mStatus.isAtLeast(Status.Started) && !Thread.currentThread().isInterrupted()) {
                    if (mPlayer == null) {
                        break;
                    }

                    if (mLastRecvPlayPosTime != null && mStatus == Status.Started) {
                        sendSyncLrc(musicId, duration, mRecvedPlayPosition);
                    }

                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException exp) {
                        break;
                    }
                }
            }

            private void sendSyncLrc(String musicId, long duration, long time) {
                int state = 0;
                if (mStatus == Status.Paused)
                    state = 2;
                else if (mStatus == Status.Started)
                    state = 1;

                Map<String, Object> msg = new HashMap<>();
                msg.put("cmd", "setLrcTime");
                msg.put("lrcId", musicId);
                msg.put("duration", duration);
                msg.put("time", time);//ms
                msg.put("state", state);
                RoomManager.getInstance().sendStreamMsg(msg);
            }
        });
        mSyncLrcThread.setName("Thread-SyncLrc");
        mSyncLrcThread.start();
    }

    private void stopSyncLrc() {
        mStopSyncLrc = true;
        if (mSyncLrcThread != null) {
            mSyncLrcThread.interrupt();
        }
    }

    protected void startPublish() {
        startSyncLrc(RoomManager.getInstance().mCurrentMemberMusic.getMusicId(), mPlayer.getDuration());
    }

    private void stopPublish() {
        stopSyncLrc();
    }

    protected void onReceivedStatusPlay(int uid) {
    }

    protected void onReceivedStatusPause(int uid) {
    }

    protected void onReceivedSetLrcTime(int uid, long position) {
        mRecvedPlayPosition = position;
        mLastRecvPlayPosTime = System.currentTimeMillis();
    }

    @SuppressLint("CheckResult")
    protected void onReceivedCountdown(int uid, int time, String musicId) {
        if (mCallback != null) {
            mCallback.onReceivedCountdown(uid, time, musicId);
        }
    }

    protected void onReceivedTestDelay(int uid, long time) {
    }

    protected void onReceivedReplyTestDelay(int uid, long testDelayTime, long time) {
    }

    protected void onReceivedOriginalChanged(int uid, int mode) {
        mLogger.d("onReceivedOriginalChanged() called with: uid = [%s], mode = [%s]", uid, mode);
    }

    @Override
    public void onPlayerStateChanged(io.agora.mediaplayer.Constants.MediaPlayerState state, io.agora.mediaplayer.Constants.MediaPlayerError error) {
        mLogger.i("onPlayerStateChanged: " + state + ", error: " + error);
        switch (state) {
            case PLAYER_STATE_OPENING:
                onMusicOpening();
                break;
            case PLAYER_STATE_OPEN_COMPLETED:
                onMusicOpenCompleted();
                break;
            case PLAYER_STATE_PLAYING:
                onMusicPlaying();
                break;
            case PLAYER_STATE_PAUSED:
                onMusicPause();
                break;
            case PLAYER_STATE_STOPPED:
                onMusicStop();
                break;
            case PLAYER_STATE_FAILED:
                onMusicOpenError(io.agora.mediaplayer.Constants.MediaPlayerError.getValue(error));
                mLogger.e("onPlayerStateChanged: failed to play, error " + error);
                break;
            default:
        }
    }

    @Override
    public void onPositionChanged(long position) {
        mRecvedPlayPosition = position;
        mLastRecvPlayPosTime = System.currentTimeMillis();
    }

    @Override
    public void onPlayerEvent(io.agora.mediaplayer.Constants.MediaPlayerEvent eventCode) {

    }

    @Override
    public void onMetaData(io.agora.mediaplayer.Constants.MediaPlayerMetadataType type, byte[] data) {

    }

    @Override
    public void onPlayBufferUpdated(long l) {

    }

    @Override
    public void onCompleted() {
        onMusicCompleted();
    }

    private void onMusicOpening() {
        mLogger.i("onMusicOpening() called");
        mHandler.obtainMessage(ACTION_ONMUSIC_OPENING).sendToTarget();
    }

    public void onMusicOpenCompleted() {
        mLogger.i("onMusicOpenCompleted() called");
        mStatus = Status.Opened;

        play();
        startDisplayLrc();
        mHandler.obtainMessage(ACTION_ON_MUSIC_OPENCOMPLETED, mPlayer.getDuration()).sendToTarget();
    }

    private void onMusicOpenError(int error) {
        mLogger.i("onMusicOpenError() called with: error = [%s]", error);
        reset();

        mHandler.obtainMessage(ACTION_ON_MUSIC_OPENERROR, error).sendToTarget();
    }

    protected void onMusicPlayingByListener() {
        mLogger.i("onMusicPlayingByListener() called");
        mStatus = Status.Started;

        mHandler.obtainMessage(ACTION_ON_MUSIC_PLAING).sendToTarget();
    }

    protected void onMusicPlaying() {
        mLogger.i("onMusicPlaying() called");
        mStatus = Status.Started;

        if (mStopSyncLrc && RoomManager.getInstance().isSinger())
            startPublish();

        mHandler.obtainMessage(ACTION_ON_MUSIC_PLAING).sendToTarget();
    }

    private void onMusicPause() {
        mLogger.i("onMusicPause() called");
        mStatus = Status.Paused;

        mHandler.obtainMessage(ACTION_ON_MUSIC_PAUSE).sendToTarget();
    }

    private void onMusicStop() {
        mLogger.i("onMusicStop() called");
        mStatus = Status.Stopped;

        stopDisplayLrc();
        stopPublish();
        reset();

        mHandler.obtainMessage(ACTION_ON_MUSIC_STOP).sendToTarget();
    }

    private void onMusicCompleted() {
        mLogger.i("onMusicCompleted() called");
        mPlayer.stop();
        stopDisplayLrc();
        stopPublish();
        reset();

        mHandler.obtainMessage(ACTION_ON_MUSIC_COMPLETED).sendToTarget();
    }

    public void destroy() {
        mLogger.i("destroy() called");
        stopPublish();
        mPlayer.unRegisterPlayerObserver(this);
        RoomManager.getInstance().getRtcEngine().removeHandler(this);
        mCallback = null;
    }

    protected void onPrepareResource() {
        if (mCallback != null) {
            mCallback.onPrepareResource();
        }
    }

    protected void onResourceReady(@NonNull MemberMusicModel music) {
        if (mCallback != null) {
            mCallback.onResourceReady(music);
        }
    }

    @MainThread
    public interface Callback {
        /**
         * 从云端下载资源
         */
        void onPrepareResource();

        /**
         * 资源下载结束
         *
         * @param music
         */
        void onResourceReady(@NonNull MemberMusicModel music);

        /**
         * 歌曲文件打开
         */
        void onMusicOpening();

        /**
         * 歌曲打开成功
         *
         * @param duration 总共时间，毫秒
         */
        void onMusicOpenCompleted(long duration);

        /**
         * 歌曲打开失败
         *
         * @param error 错误码
         */
        void onMusicOpenError(int error);

        /**
         * 正在播放
         */
        void onMusicPlaying();

        /**
         * 暂停
         */
        void onMusicPause();

        /**
         * 结束
         */
        void onMusicStop();

        /**
         * 播放完成
         */
        void onMusicCompleted();

        /**
         * 进度更新
         *
         * @param position
         */
        void onMusicPositionChanged(long position);

        /**
         * 合唱模式下，等待加入合唱倒计时
         *
         * @param uid
         * @param time    秒
         * @param musicId
         */
        void onReceivedCountdown(int uid, int time, String musicId);
    }
}
