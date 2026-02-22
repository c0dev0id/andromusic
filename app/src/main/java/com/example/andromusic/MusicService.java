package com.example.andromusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MusicService extends Service {
    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "MusicServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_PLAY_PAUSE = "com.example.andromusic.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.example.andromusic.NEXT";
    public static final String ACTION_PREV = "com.example.andromusic.PREV";

    private final IBinder binder = new MusicBinder();
    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PreferencesManager prefsManager;

    private List<String> playlist = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;

    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && isPlaying) {
                prefsManager.savePosition(mediaPlayer.getCurrentPosition());
            }
            saveHandler.postDelayed(this, 5000);
        }
    };

    public interface OnTrackChangeListener {
        void onTrackChanged(int index);
        void onPlayStateChanged(boolean playing);
    }

    private OnTrackChangeListener trackChangeListener;

    public class MusicBinder extends Binder {
        MusicService getService() {
            return MusicService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefsManager = new PreferencesManager(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        setupMediaSession();
        playlist = prefsManager.loadPlaylist();
        currentIndex = prefsManager.loadTrackIndex();
        if (currentIndex >= playlist.size()) currentIndex = 0;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE:
                    if (isPlaying) pause(); else play();
                    break;
                case ACTION_NEXT:
                    next();
                    break;
                case ACTION_PREV:
                    previous();
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        saveState();
        saveHandler.removeCallbacks(saveRunnable);
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
        }
        abandonAudioFocus();
        super.onDestroy();
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() { play(); }
            @Override
            public void onPause() { pause(); }
            @Override
            public void onSkipToNext() { next(); }
            @Override
            public void onSkipToPrevious() { previous(); }
            @Override
            public void onSeekTo(long pos) { seekTo((int) pos); }
            @Override
            public void onStop() { stopSelf(); }
        });
        mediaSession.setActive(true);
    }

    public void setPlaylist(List<String> newPlaylist, int startIndex) {
        playlist = new ArrayList<>(newPlaylist);
        currentIndex = startIndex;
        prefsManager.savePlaylist(playlist);
        prefsManager.saveTrackIndex(currentIndex);
        prepareAndPlay(0);
    }

    public void play() {
        if (playlist.isEmpty()) return;
        if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
            if (requestAudioFocus()) {
                if (mediaPlayer != null) {
                    mediaPlayer.start();
                    isPlaying = true;
                } else {
                    prepareAndPlay(prefsManager.loadPosition());
                }
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                updateNotification();
                if (trackChangeListener != null) trackChangeListener.onPlayStateChanged(true);
                saveHandler.postDelayed(saveRunnable, 5000);
            }
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            updateNotification();
            if (trackChangeListener != null) trackChangeListener.onPlayStateChanged(false);
            prefsManager.savePosition(mediaPlayer.getCurrentPosition());
            saveHandler.removeCallbacks(saveRunnable);
        }
    }

    public void next() {
        if (playlist.isEmpty()) return;
        currentIndex = (currentIndex + 1) % playlist.size();
        prefsManager.saveTrackIndex(currentIndex);
        prepareAndPlay(0);
    }

    public void previous() {
        if (playlist.isEmpty()) return;
        if (mediaPlayer != null && mediaPlayer.getCurrentPosition() > 3000) {
            seekTo(0);
        } else {
            currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
            prefsManager.saveTrackIndex(currentIndex);
            prepareAndPlay(0);
        }
    }

    public void playAt(int index) {
        if (index < 0 || index >= playlist.size()) return;
        currentIndex = index;
        prefsManager.saveTrackIndex(currentIndex);
        prepareAndPlay(0);
    }

    public void seekTo(int positionMs) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(positionMs);
        }
    }

    private void prepareAndPlay(int seekPosition) {
        if (playlist.isEmpty()) return;
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mediaPlayer.setDataSource(playlist.get(currentIndex));
            mediaPlayer.setOnPreparedListener(mp -> {
                if (seekPosition > 0) mp.seekTo(seekPosition);
                if (requestAudioFocus()) {
                    mp.start();
                    isPlaying = true;
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    updateMetadata();
                    updateNotification();
                    startForeground(NOTIFICATION_ID, buildNotification());
                    if (trackChangeListener != null) {
                        trackChangeListener.onTrackChanged(currentIndex);
                        trackChangeListener.onPlayStateChanged(true);
                    }
                    saveHandler.removeCallbacks(saveRunnable);
                    saveHandler.postDelayed(saveRunnable, 5000);
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> next());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                next();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "Error preparing media player", e);
            next();
        }
    }

    private boolean requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build())
                    .setOnAudioFocusChangeListener(focusChange -> {
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) pause();
                        else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
                        else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) play();
                    })
                    .build();
            return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            return audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
    }

    private void updatePlaybackState(int state) {
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PAUSE |
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                        PlaybackStateCompat.ACTION_SEEK_TO)
                .setState(state,
                        mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0,
                        1.0f);
        mediaSession.setPlaybackState(builder.build());
    }

    private void updateMetadata() {
        if (playlist.isEmpty()) return;
        String path = playlist.get(currentIndex);
        String title = new File(path).getName();
        // strip extension
        int dot = title.lastIndexOf('.');
        if (dot > 0) title = title.substring(0, dot);
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,
                        mediaPlayer != null ? mediaPlayer.getDuration() : 0)
                .build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("AndroMusic playback controls");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(this, 0,
                contentIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent prevIntent = new Intent(this, MusicService.class).setAction(ACTION_PREV);
        PendingIntent prevPending = PendingIntent.getService(this, 1,
                prevIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent playPauseIntent = new Intent(this, MusicService.class).setAction(ACTION_PLAY_PAUSE);
        PendingIntent playPausePending = PendingIntent.getService(this, 2,
                playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent nextIntent = new Intent(this, MusicService.class).setAction(ACTION_NEXT);
        PendingIntent nextPending = PendingIntent.getService(this, 3,
                nextIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = "AndroMusic";
        if (!playlist.isEmpty() && currentIndex < playlist.size()) {
            String path = playlist.get(currentIndex);
            title = new File(path).getName();
            int dot = title.lastIndexOf('.');
            if (dot > 0) title = title.substring(0, dot);
        }

        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playPauseLabel = isPlaying ? "Pause" : "Play";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText("AndroMusic")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(contentPendingIntent)
                .addAction(android.R.drawable.ic_media_previous, "Previous", prevPending)
                .addAction(playPauseIcon, playPauseLabel, playPausePending)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPending)
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void saveState() {
        prefsManager.saveTrackIndex(currentIndex);
        if (mediaPlayer != null) {
            try {
                prefsManager.savePosition(mediaPlayer.getCurrentPosition());
            } catch (IllegalStateException e) {
                // ignore
            }
        }
    }

    public List<String> getPlaylist() { return playlist; }
    public int getCurrentIndex() { return currentIndex; }
    public boolean isPlaying() { return isPlaying; }
    public int getCurrentPosition() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
    }
    public int getDuration() {
        return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
    }
    public void setOnTrackChangeListener(OnTrackChangeListener listener) {
        this.trackChangeListener = listener;
    }
    public MediaSessionCompat.Token getSessionToken() {
        return mediaSession.getSessionToken();
    }
}
