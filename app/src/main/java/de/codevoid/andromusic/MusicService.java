package de.codevoid.andromusic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicService extends Service {
    private static final String TAG = "MusicService";
    private static final String CHANNEL_ID = "MusicServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_PLAY_PAUSE = "de.codevoid.andromusic.PLAY_PAUSE";
    public static final String ACTION_NEXT = "de.codevoid.andromusic.NEXT";
    public static final String ACTION_PREV = "de.codevoid.andromusic.PREV";

    private final IBinder binder = new MusicBinder();
    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private PreferencesManager prefsManager;

    private List<String> playlist = new ArrayList<>();
    private List<String> originalPlaylist = new ArrayList<>();
    private int currentIndex = 0;
    private boolean isPlaying = false;
    private boolean pausedForTransientFocusLoss = false;
    private boolean shuffleEnabled = false;
    private Bitmap currentCoverArt;
    private String currentTitle;
    private String currentArtist;
    private String currentAlbum;
    private String pendingAction = null;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = this::onAudioFocusChange;

    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();

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
        void onPlaylistChanged(List<String> playlist, int currentIndex);
        void onActionPerformed(String action, String title, String artist, Bitmap coverArt);
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
        originalPlaylist = new ArrayList<>(playlist);
        currentIndex = prefsManager.loadTrackIndex();
        shuffleEnabled = prefsManager.loadShuffleEnabled();
        if (currentIndex >= playlist.size()) currentIndex = 0;
        // Start foreground immediately to prevent service being killed on Android 8+
        startForeground(NOTIFICATION_ID, buildNotification());
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
        metadataExecutor.shutdownNow();
        if (mediaPlayer != null) {
            mediaPlayer.setOnCompletionListener(null);
            mediaPlayer.setOnErrorListener(null);
            mediaPlayer.setOnPreparedListener(null);
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (mediaSession != null) {
            mediaSession.release();
        }
        abandonAudioFocus();
        if (currentCoverArt != null) {
            currentCoverArt.recycle();
            currentCoverArt = null;
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Ensure the service keeps running when the app is swiped from recents
        if (isPlaying) {
            return;
        }
        super.onTaskRemoved(rootIntent);
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, TAG);
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
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
            public void onStop() { pause(); }
        });
        mediaSession.setActive(true);
        // Set initial playback state so the session can receive external events immediately
        updatePlaybackState(PlaybackStateCompat.STATE_NONE);
    }

    public void setPlaylist(List<String> newPlaylist, int startIndex) {
        originalPlaylist = new ArrayList<>(newPlaylist);
        playlist = new ArrayList<>(newPlaylist);
        currentIndex = startIndex;
        prefsManager.savePlaylist(playlist);
        prefsManager.saveTrackIndex(currentIndex);
        pendingAction = "▶ Play";
        prepareAndPlay(0);
    }

    public void play() {
        if (playlist.isEmpty()) return;
        if (mediaPlayer == null || !mediaPlayer.isPlaying()) {
            if (mediaPlayer != null) {
                if (requestAudioFocus()) {
                    mediaPlayer.start();
                    isPlaying = true;
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    if (trackChangeListener != null) {
                        trackChangeListener.onPlayStateChanged(true);
                        trackChangeListener.onActionPerformed("▶ Play", currentTitle, currentArtist, currentCoverArt);
                    }
                    saveHandler.postDelayed(saveRunnable, 5000);
                }
            } else {
                prepareAndPlay(prefsManager.loadPosition());
            }
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            if (trackChangeListener != null) {
                trackChangeListener.onPlayStateChanged(false);
                trackChangeListener.onActionPerformed("⏸ Pause", currentTitle, currentArtist, currentCoverArt);
            }
            prefsManager.savePosition(mediaPlayer.getCurrentPosition());
            saveHandler.removeCallbacks(saveRunnable);
        }
    }

    public void next() {
        if (playlist.isEmpty()) return;
        currentIndex = (currentIndex + 1) % playlist.size();
        prefsManager.saveTrackIndex(currentIndex);
        pendingAction = "⏭ Next";
        prepareAndPlay(0);
    }

    public void previous() {
        if (playlist.isEmpty()) return;
        if (mediaPlayer != null && mediaPlayer.getCurrentPosition() > 3000) {
            seekTo(0);
            if (trackChangeListener != null) {
                trackChangeListener.onActionPerformed("⏮ Previous", currentTitle, currentArtist, currentCoverArt);
            }
        } else {
            currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
            prefsManager.saveTrackIndex(currentIndex);
            pendingAction = "⏮ Previous";
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

    public void setShuffleEnabled(boolean enabled) {
        shuffleEnabled = enabled;
        prefsManager.saveShuffleEnabled(enabled);
        if (playlist.isEmpty()) return;
        String currentTrack = playlist.get(currentIndex);
        if (enabled) {
            // Move current track to index 0, then shuffle the rest
            playlist.remove(currentIndex);
            Collections.shuffle(playlist);
            playlist.add(0, currentTrack);
            currentIndex = 0;
        } else {
            playlist = new ArrayList<>(originalPlaylist);
            currentIndex = playlist.indexOf(currentTrack);
            if (currentIndex < 0) currentIndex = 0;
        }
        prefsManager.savePlaylist(playlist);
        prefsManager.saveTrackIndex(currentIndex);
        if (trackChangeListener != null) {
            trackChangeListener.onPlaylistChanged(playlist, currentIndex);
        }
    }

    public boolean isShuffleEnabled() {
        return shuffleEnabled;
    }

    private void extractTrackInfo(String filePath) {
        if (currentCoverArt != null) {
            currentCoverArt.recycle();
            currentCoverArt = null;
        }
        // Default title from filename
        String fallbackTitle = new File(filePath).getName();
        int dot = fallbackTitle.lastIndexOf('.');
        if (dot > 0) fallbackTitle = fallbackTitle.substring(0, dot);

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(filePath);
            currentArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            currentAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            currentTitle = (metaTitle != null && !metaTitle.trim().isEmpty()) ? metaTitle : fallbackTitle;
            byte[] art = retriever.getEmbeddedPicture();
            if (art != null) {
                currentCoverArt = BitmapFactory.decodeByteArray(art, 0, art.length);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract track info from: " + filePath, e);
            currentTitle = fallbackTitle;
            currentArtist = null;
            currentAlbum = null;
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
            }
        }
    }

    private void prepareAndPlay(int seekPosition) {
        if (playlist.isEmpty()) return;
        if (mediaPlayer != null) {
            mediaPlayer.setOnCompletionListener(null);
            mediaPlayer.setOnErrorListener(null);
            mediaPlayer.setOnPreparedListener(null);
            mediaPlayer.release();
            mediaPlayer = null;
        }
        try {
            final String filePath = playlist.get(currentIndex);
            final int preparedIndex = currentIndex;

            // Extract cover art and metadata on a background thread
            metadataExecutor.execute(() -> {
                extractTrackInfo(filePath);
                saveHandler.post(() -> {
                    if (currentIndex == preparedIndex) {
                        updateMetadata();
                    }
                });
            });

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.setOnPreparedListener(mp -> {
                if (currentIndex != preparedIndex) return;
                if (seekPosition > 0) mp.seekTo(seekPosition);
                if (requestAudioFocus()) {
                    mp.start();
                    isPlaying = true;
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                    updateMetadata();
                    if (trackChangeListener != null) {
                        trackChangeListener.onTrackChanged(currentIndex);
                        trackChangeListener.onPlayStateChanged(true);
                        String action = pendingAction != null ? pendingAction : "▶ Play";
                        pendingAction = null;
                        trackChangeListener.onActionPerformed(action, currentTitle, currentArtist, currentCoverArt);
                    }
                    saveHandler.removeCallbacks(saveRunnable);
                    saveHandler.postDelayed(saveRunnable, 5000);
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                if (currentIndex == preparedIndex) {
                    next();
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                if (currentIndex == preparedIndex) {
                    next();
                }
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "Error preparing media player", e);
            isPlaying = false;
            if (trackChangeListener != null) {
                trackChangeListener.onPlayStateChanged(false);
            }
        }
    }

    private void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            if (pausedForTransientFocusLoss) {
                play();
                pausedForTransientFocusLoss = false;
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (isPlaying) {
                pausedForTransientFocusLoss = true;
                pause();
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            pausedForTransientFocusLoss = false;
            pause();
        }
    }

    private boolean requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build())
                        .setOnAudioFocusChangeListener(audioFocusChangeListener)
                        .build();
            }
            return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            return audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC,
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
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_STOP |
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
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                        currentTitle != null ? currentTitle : "Unknown")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION,
                        mediaPlayer != null ? mediaPlayer.getDuration() : 0);
        if (currentArtist != null) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist);
        }
        if (currentAlbum != null) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum);
        }
        if (currentCoverArt != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentCoverArt);
        }
        mediaSession.setMetadata(builder.build());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("AndroMusic background playback");
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .build();
    }

    private void saveState() {
        prefsManager.saveTrackIndex(currentIndex);
        if (mediaPlayer != null) {
            try {
                prefsManager.savePosition(mediaPlayer.getCurrentPosition());
            } catch (IllegalStateException e) {
                Log.w(TAG, "MediaPlayer in invalid state when saving position", e);
            }
        }
    }

    public List<String> getPlaylist() { return playlist; }
    public int getCurrentIndex() { return currentIndex; }
    public boolean isPlaying() { return isPlaying; }
    public Bitmap getCurrentCoverArt() { return currentCoverArt; }
    public String getCurrentTitle() { return currentTitle; }
    public String getCurrentArtist() { return currentArtist; }
    public int getCurrentPosition() {
        return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
    }
    public int getDuration() {
        return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
    }
    public void setOnTrackChangeListener(OnTrackChangeListener listener) {
        this.trackChangeListener = listener;
    }
}
