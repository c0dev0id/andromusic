package com.example.andromusic;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;

    private MusicService musicService;
    private boolean serviceBound = false;
    private PreferencesManager prefsManager;

    private TextView tvCurrentTrack;
    private Button btnPlayPause;
    private ListView lvPlaylist;
    private ArrayAdapter<String> playlistAdapter;
    private List<String> displayNames = new ArrayList<>();
    private List<String> filePaths = new ArrayList<>();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            serviceBound = true;
            musicService.setOnTrackChangeListener(new MusicService.OnTrackChangeListener() {
                @Override
                public void onTrackChanged(int index) {
                    runOnUiThread(() -> updateUI(index));
                }
                @Override
                public void onPlayStateChanged(boolean playing) {
                    runOnUiThread(() -> btnPlayPause.setText(playing ? "⏸" : "▶"));
                }
            });
            loadPlaylistIntoUI(musicService.getPlaylist());
            updateUI(musicService.getCurrentIndex());
            btnPlayPause.setText(musicService.isPlaying() ? "⏸" : "▶");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    private final ActivityResultLauncher<Uri> dirPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    String path = getRealPathFromUri(uri);
                    if (path != null) {
                        prefsManager.saveDirectory(path);
                        scanAndLoad(path);
                    } else {
                        Toast.makeText(this, "Could not resolve directory path", Toast.LENGTH_SHORT).show();
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefsManager = new PreferencesManager(this);

        tvCurrentTrack = findViewById(R.id.tv_current_track);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        Button btnPrev = findViewById(R.id.btn_prev);
        Button btnNext = findViewById(R.id.btn_next);
        Button btnPickDir = findViewById(R.id.btn_pick_dir);
        lvPlaylist = findViewById(R.id.lv_playlist);

        playlistAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_activated_1, displayNames);
        lvPlaylist.setAdapter(playlistAdapter);
        lvPlaylist.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        lvPlaylist.setOnItemClickListener((parent, view, position, id) -> {
            if (serviceBound) {
                musicService.playAt(position);
            }
        });

        btnPlayPause.setOnClickListener(v -> {
            if (serviceBound) {
                if (musicService.isPlaying()) musicService.pause();
                else musicService.play();
            }
        });

        btnPrev.setOnClickListener(v -> { if (serviceBound) musicService.previous(); });
        btnNext.setOnClickListener(v -> { if (serviceBound) musicService.next(); });
        btnPickDir.setOnClickListener(v -> {
            if (checkAndRequestPermissions()) {
                openDirectoryPicker();
            }
        });

        checkAndRequestPermissions();
        startAndBindService();
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    private void startAndBindService() {
        Intent intent = new Intent(this, MusicService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private boolean checkAndRequestPermissions() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{permission}, PERMISSION_REQUEST_CODE);
            return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                String savedDir = prefsManager.loadDirectory();
                if (savedDir != null) scanAndLoad(savedDir);
            }
        }
    }

    private void openDirectoryPicker() {
        String savedDir = prefsManager.loadDirectory();
        Uri initialUri = null;
        if (savedDir != null) {
            initialUri = Uri.fromFile(new File(savedDir));
        }
        dirPickerLauncher.launch(initialUri);
    }

    private String getRealPathFromUri(Uri uri) {
        // Try to extract path from document tree URI
        try {
            String docId = DocumentsContract.getTreeDocumentId(uri);
            if (docId != null && docId.startsWith("primary:")) {
                return "/storage/emulated/0/" + docId.substring("primary:".length());
            }
            // For other providers, try authority-based path
            String auth = uri.getAuthority();
            if ("com.android.externalstorage.documents".equals(auth)) {
                String[] split = docId.split(":");
                if (split.length == 2) {
                    String type = split[0];
                    String relativePath = split[1];
                    if ("primary".equalsIgnoreCase(type)) {
                        return "/storage/emulated/0/" + relativePath;
                    }
                    return "/storage/" + type + "/" + relativePath;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void scanAndLoad(String dirPath) {
        List<String> scanned = MusicScanner.scan(dirPath);
        if (scanned.isEmpty()) {
            Toast.makeText(this, "No audio files found in selected directory", Toast.LENGTH_SHORT).show();
            return;
        }
        prefsManager.savePlaylist(scanned);
        prefsManager.saveTrackIndex(0);
        prefsManager.savePosition(0);
        loadPlaylistIntoUI(scanned);
        if (serviceBound) {
            musicService.setPlaylist(scanned, 0);
        }
    }

    private void loadPlaylistIntoUI(List<String> paths) {
        filePaths = new ArrayList<>(paths);
        displayNames.clear();
        for (String path : paths) {
            String name = new File(path).getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            displayNames.add(name);
        }
        playlistAdapter.notifyDataSetChanged();
    }

    private void updateUI(int index) {
        if (index >= 0 && index < displayNames.size()) {
            tvCurrentTrack.setText(displayNames.get(index));
            lvPlaylist.setItemChecked(index, true);
            lvPlaylist.smoothScrollToPosition(index);
        }
        if (serviceBound) {
            btnPlayPause.setText(musicService.isPlaying() ? "⏸" : "▶");
        }
    }
}
