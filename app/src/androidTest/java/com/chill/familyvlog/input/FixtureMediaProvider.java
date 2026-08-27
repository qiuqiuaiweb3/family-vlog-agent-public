package com.chill.familyvlog.input;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

public final class FixtureMediaProvider extends ContentProvider {
    public static final String AUTHORITY = "com.chill.familyvlog.fixturemedia";
    public static final String PORTRAIT_AUDIO = "probe_portrait_audio";
    public static final String VP9_WEBM = "probe_vp9_webm";
    public static final String LANDSCAPE_SILENT = "probe_landscape_silent";
    public static final String CORRUPT_FIRST_SYNC = "probe_corrupt_first_sync";
    public static final String RENDER_PORTRAIT_AUDIO = "render_portrait_audio";
    public static final String RENDER_LANDSCAPE_SILENT = "render_landscape_silent";
    public static final String RENDER_LANDSCAPE_VFR = "render_landscape_vfr";
    public static final String RENDER_HEVC_HLG = "render_hevc_hlg";
    public static final String RENDER_ROTATED_SILENT = "render_rotated_silent";
    public static final String SOURCE_METADATA_UNKNOWN_TRACK = "source_metadata_unknown_track";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        try {
            resourceId(uri);
        } catch (FileNotFoundException exception) {
            throw new IllegalArgumentException("Unknown fixture");
        }
        return VP9_WEBM.equals(uri.getLastPathSegment()) ? "video/webm" : "video/mp4";
    }

    @Override
    public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Fixture is read-only");
        }
        Context providerContext = getContext();
        if (providerContext == null) {
            throw new FileNotFoundException("Provider unavailable");
        }
        final int resourceId = resourceId(uri);
        Set<String> queryNames = uri.getQueryParameterNames();
        if (queryNames.isEmpty()) {
            AssetFileDescriptor descriptor = providerContext.getResources().openRawResourceFd(resourceId);
            if (descriptor == null) {
                throw new FileNotFoundException("Fixture cannot provide a descriptor");
            }
            return descriptor;
        }
        List<String> views = uri.getQueryParameters("view");
        if (queryNames.size() != 1
                || !queryNames.contains("view")
                || views.size() != 1
                || !"whole-file".equals(views.get(0))) {
            throw new FileNotFoundException("Unknown fixture view");
        }
        ParcelFileDescriptor pipe = openPipeHelper(uri, "video/mp4", null, resourceId,
                (output, ignoredUri, ignoredMimeType, ignoredOptions, id) -> {
                    try (InputStream input = providerContext.getResources().openRawResource(id);
                         FileOutputStream sink = new FileOutputStream(output.getFileDescriptor())) {
                        byte[] buffer = new byte[8 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            sink.write(buffer, 0, count);
                        }
                    } catch (IOException ignored) {
                        // The reader observes a short or failed pipe without exposing fixture data.
                    }
                });
        if (pipe == null) {
            throw new FileNotFoundException("Fixture cannot provide a pipe");
        }
        return new AssetFileDescriptor(pipe, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        throw new UnsupportedOperationException("Fixture provider does not query");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Fixture provider is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Fixture provider is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Fixture provider is read-only");
    }

    public static Uri uriFor(String name) {
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    private int resourceId(Uri uri) throws FileNotFoundException {
        List<String> segments = uri == null ? null : uri.getPathSegments();
        if (segments == null || segments.size() != 1) {
            throw new FileNotFoundException("Unknown fixture");
        }
        String name = segments.get(0);
        if (!PORTRAIT_AUDIO.equals(name)
                && !VP9_WEBM.equals(name)
                && !LANDSCAPE_SILENT.equals(name)
                && !CORRUPT_FIRST_SYNC.equals(name)
                && !RENDER_PORTRAIT_AUDIO.equals(name)
                && !RENDER_LANDSCAPE_SILENT.equals(name)
                && !RENDER_LANDSCAPE_VFR.equals(name)
                && !RENDER_HEVC_HLG.equals(name)
                && !RENDER_ROTATED_SILENT.equals(name)
                && !SOURCE_METADATA_UNKNOWN_TRACK.equals(name)) {
            throw new FileNotFoundException("Unknown fixture");
        }
        Context providerContext = getContext();
        if (providerContext == null) {
            throw new FileNotFoundException("Provider unavailable");
        }
        int resourceId = providerContext.getResources().getIdentifier(
                name,
                "raw",
                providerContext.getPackageName()
        );
        if (resourceId == 0) {
            throw new FileNotFoundException("Fixture resource unavailable");
        }
        return resourceId;
    }
}
