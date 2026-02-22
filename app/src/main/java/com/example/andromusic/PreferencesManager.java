package com.example.andromusic;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

public class PreferencesManager {
    private static final String PREFS_NAME = "AndroMusicPrefs";
    private static final String KEY_DIRECTORY = "music_directory";
    private static final String KEY_PLAYLIST = "playlist";
    private static final String KEY_TRACK_INDEX = "track_index";
    private static final String KEY_POSITION = "position_ms";

    private final SharedPreferences prefs;

    public PreferencesManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveDirectory(String path) {
        prefs.edit().putString(KEY_DIRECTORY, path).apply();
    }

    public String loadDirectory() {
        return prefs.getString(KEY_DIRECTORY, null);
    }

    public void savePlaylist(List<String> playlist) {
        JSONArray array = new JSONArray();
        for (String path : playlist) {
            array.put(path);
        }
        prefs.edit().putString(KEY_PLAYLIST, array.toString()).apply();
    }

    public List<String> loadPlaylist() {
        List<String> playlist = new ArrayList<>();
        String json = prefs.getString(KEY_PLAYLIST, null);
        if (json == null) return playlist;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                playlist.add(array.getString(i));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return playlist;
    }

    public void saveTrackIndex(int index) {
        prefs.edit().putInt(KEY_TRACK_INDEX, index).apply();
    }

    public int loadTrackIndex() {
        return prefs.getInt(KEY_TRACK_INDEX, 0);
    }

    public void savePosition(int positionMs) {
        prefs.edit().putInt(KEY_POSITION, positionMs).apply();
    }

    public int loadPosition() {
        return prefs.getInt(KEY_POSITION, 0);
    }
}
