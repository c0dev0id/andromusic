package com.example.andromusic;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MusicScanner {

    public static List<String> scan(String directoryPath) {
        List<String> results = new ArrayList<>();
        File dir = new File(directoryPath);
        if (dir.exists() && dir.isDirectory()) {
            scanRecursive(dir, results);
        }
        Collections.sort(results);
        return results;
    }

    private static void scanRecursive(File dir, List<String> results) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanRecursive(file, results);
            } else if (isSupportedAudio(file.getName())) {
                results.add(file.getAbsolutePath());
            }
        }
    }

    private static boolean isSupportedAudio(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".ogg");
    }
}
