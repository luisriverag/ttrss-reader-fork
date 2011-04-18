/*
 * ttrss-reader-fork for Android
 * 
 * Copyright (C) 2010 N. Braden.
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 3 as published by the Free Software Foundation.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package org.ttrssreader.model.cachers;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import org.ttrssreader.controllers.Controller;
import org.ttrssreader.controllers.DBHelper;
import org.ttrssreader.controllers.Data;
import org.ttrssreader.model.pojos.Feed;
import org.ttrssreader.utils.ImageCache;
import org.ttrssreader.utils.StringSupport;
import org.ttrssreader.utils.Utils;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.util.Log;

public class ImageCacher implements ICacheable {
    
    private static final long maxFileSize = 1024 * 1024 * 6; // Max size for one image is 6 MB
    private static final int DOWNLOAD_ARTICLES_THREADS = 8;
    private static final int DOWNLOAD_IMAGES_THREADS = 4;
    
    private Context context;
    private boolean onlyArticles;
    private boolean onlyUnreadImages;
    private boolean onlyUnreadArticles;
    private long cacheSizeMax;
    private ImageCache imageCache;
    private long folderSize;
    private long downloaded = 0;
    
    public ImageCacher(Context context, boolean onlyArticles) {
        this.context = context;
        this.onlyArticles = onlyArticles;
        this.onlyUnreadImages = Controller.getInstance().isImageCacheUnread();
        this.onlyUnreadArticles = Controller.getInstance().isArticleCacheUnread();
        this.cacheSizeMax = Controller.getInstance().getImageCacheSize() * 1048576;
        this.imageCache = Controller.getInstance().getImageCache(context);
        String settings = "Settings: (onlyArticles: %s), (onlyUnreadImages: %s), (onlyUnreadArticles: %s), (cacheSizeMax: %s)";
        Log.i(Utils.TAG, String.format(settings, onlyArticles, onlyUnreadImages, onlyUnreadArticles, cacheSizeMax));
    }
    
    @Override
    public void cache() {
        long start = System.currentTimeMillis();
        
        // Check connectivity
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (!Utils.checkConnected(cm)) {
            Log.w(Utils.TAG, "No connectivity, aborting...");
            return;
        }
        
        // Update all articles
        updateArticles();
        if (onlyArticles) // We are done here..
            return;
        
        if (imageCache == null)
            return;
        
        imageCache.fillMemoryCacheFromDisk();
        downloadImages();
        purgeCache();
        
        int time = (int) (System.currentTimeMillis() - start) / 1000;
        Log.i(Utils.TAG, String.format("Cache: %s MB (Limit: %s MB, took %s seconds)", folderSize / 1048576,
                cacheSizeMax / 1048576, time));
    }
    
    private void updateArticles() {
        Log.i(Utils.TAG, "Updating articles...");
        long time = System.currentTimeMillis();
        UpdateArticlesTask[] tasks = new UpdateArticlesTask[DOWNLOAD_ARTICLES_THREADS];
        
        Data.getInstance().updateCounters(true);
        Data.getInstance().updateCategories(true);
        Data.getInstance().updateFeeds(-4, true);
        
        for (Feed f : DBHelper.getInstance().getFeeds(-4)) {
            if (f.unread == 0)
                continue;
            
            boolean done = false;
            while (!done) {
                
                // Start new Task if task-slot is available
                for (int i = 0; i < DOWNLOAD_ARTICLES_THREADS; i++) {
                    UpdateArticlesTask t = tasks[i];
                    if (t == null || t.getStatus().equals(AsyncTask.Status.FINISHED)) {
                        t = new UpdateArticlesTask(onlyUnreadArticles);
                        t.execute(f.id);
                        tasks[i] = t;
                        done = true;
                        break;
                    }
                }
                
                if (!done) { // No task-slot available, wait.
                    synchronized (this) {
                        try {
                            wait(100);
                        } catch (InterruptedException e) {
                        }
                    }
                }
            }
        }
        
        // Wait for all tasks to finish
        boolean tasksRunning = true;
        while (tasksRunning) {
            tasksRunning = false;
            synchronized (this) {
                try {
                    wait(200);
                } catch (InterruptedException e) {
                }
            }
            for (int i = 0; i < DOWNLOAD_ARTICLES_THREADS; i++) {
                UpdateArticlesTask t = tasks[i];
                if (t != null && t.getStatus().equals(AsyncTask.Status.RUNNING)) {
                    tasksRunning = true;
                }
            }
        }
        
        Log.i(Utils.TAG, "Updating articles took " + (System.currentTimeMillis() - time) + "ms");
    }
    
    private void downloadImages() {
        Log.i(Utils.TAG, "Downloading images...");
        long time = System.currentTimeMillis();
        DownloadImageTask[] tasks = new DownloadImageTask[DOWNLOAD_IMAGES_THREADS];
        
        Cursor c = DBHelper.getInstance().queryArticlesForImageCache(onlyUnreadImages);
        if (c.moveToFirst()) {
            while (!c.isAfterLast()) {
                // Get images included in HTML
                Set<String> set = new HashSet<String>();
                
                for (String url : findAllImageUrls(c.getString(0))) {
                    if (!imageCache.containsKey(url))
                        set.add(url);
                }
                
                // Get images from attachments separately
                for (String url : c.getString(1).split(";")) {
                    for (String ext : Utils.IMAGE_EXTENSIONS) {
                        if (url.toLowerCase().contains("." + ext) && !imageCache.containsKey(url)) {
                            set.add(url);
                            break;
                        }
                    }
                }
                
                assignTask(tasks, StringSupport.setToArray(set));
                
                if (downloaded > cacheSizeMax) {
                    Log.w(Utils.TAG, "Stopping download, downloaded data exceeds cache-size-limit from options.");
                    break;
                }
                c.move(1);
            }
        }
        c.close();
        
        // Wait for all tasks to finish and retrieve results
        boolean tasksRunning = true;
        while (tasksRunning) {
            tasksRunning = false;
            synchronized (this) {
                try {
                    wait(200);
                } catch (InterruptedException e) {
                }
            }
            for (int i = 0; i < DOWNLOAD_IMAGES_THREADS; i++) {
                DownloadImageTask t = tasks[i];
                retrieveResult(t);
                if (t != null && t.getStatus().equals(AsyncTask.Status.RUNNING)) {
                    tasksRunning = true;
                }
            }
        }
        
        DBHelper.getInstance().updateAllArticlesCachedImages(true);
        
        Log.i(Utils.TAG, "Downloading images took " + (System.currentTimeMillis() - time) + "ms");
    }
    
    private void purgeCache() {
        Log.i(Utils.TAG, "Purging cache...");
        long time = System.currentTimeMillis();
        File cacheFolder = new File(imageCache.getDiskCacheDirectory());
        
        folderSize = 0;
        for (File f : cacheFolder.listFiles()) {
            folderSize += f.length();
        }
        
        if (folderSize > cacheSizeMax) {
            Log.i(Utils.TAG, String.format("Before - Cache: %s bytes (Limit: %s bytes)", folderSize, cacheSizeMax));
            
            // Sort list of files by last access date
            List<File> list = Arrays.asList(cacheFolder.listFiles());
            Collections.sort(list, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    long size1 = f1.lastModified();
                    long size2 = f2.lastModified();
                    if (size1 < size2) {
                        return -1;
                    } else if (size1 > size2) {
                        return 1;
                    }
                    return 0; // equal
                }
            });
            
            int i = 0;
            while (folderSize > cacheSizeMax) {
                File f = list.get(i++);
                folderSize -= f.length();
                f.delete();
            }
            Log.i(Utils.TAG, String.format("After - Cache: %s bytes (Limit: %s bytes)", folderSize, cacheSizeMax));
        }
        Log.i(Utils.TAG, "Purging cache took " + (System.currentTimeMillis() - time) + "ms");
    }
    
    private void retrieveResult(DownloadImageTask t) {
        if (t != null && t.getStatus().equals(AsyncTask.Status.FINISHED)) {
            try {
                downloaded += t.get();
                t = null; // check if tasks[i] is null too, should be though.
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    
    private void assignTask(DownloadImageTask[] tasks, String... urls) {
        if (urls.length == 0)
            return;
        
        if (tasks == null)
            tasks = new DownloadImageTask[DOWNLOAD_IMAGES_THREADS];
        
        boolean done = false;
        while (!done) {
            
            // Start new Task if task-slot is available
            for (int i = 0; i < DOWNLOAD_IMAGES_THREADS; i++) {
                DownloadImageTask t = tasks[i];
                
                // Retrieve result (downloaded size) and reset task
                retrieveResult(t);
                
                // Assign new task if possible
                if (t == null || t.getStatus().equals(AsyncTask.Status.FINISHED)) {
                    t = new DownloadImageTask(imageCache, maxFileSize);
                    t.execute(urls);
                    tasks[i] = t;
                    done = true;
                    break;
                }
            }
            
            if (!done) { // No task-slot available, wait.
                synchronized (this) {
                    try {
                        wait(100);
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
    }
    
    /**
     * Searches for cached versions of the given image and returns the local URL to access the file.
     * 
     * @param url
     *            the original URL
     * @return the local URL or null if no image in cache or if the file couldn't be found.
     */
    public static String getCachedImageUrl(String url) {
        ImageCache cache = Controller.getInstance().getImageCache(null);
        if (cache != null && cache.containsKey(url)) {
            
                StringBuffer sb = new StringBuffer();
                sb.append(cache.getDiskCacheDirectory());
                sb.append(File.separator);
                sb.append(cache.getFileNameForKey(url));
                
                File file = new File(sb.toString());
                
                if (file.exists()) {
                    sb.insert(0, "file://"); // Add "file:" at the beginning..
                    return sb.toString();
                } else {
                    Log.w(Utils.TAG, "File " + sb.toString() + " is in cache but does not exist.");
                }
            
        }
        return null;
    }
    
    /**
     * Searches the given html code for img-Tags and filters out all src-attributes, beeing URLs to images.
     * 
     * @param html
     *            the html code which is to be searched
     * @return a set of URLs in their string representation
     */
    public static Set<String> findAllImageUrls(String html) {
        Set<String> ret = new LinkedHashSet<String>();
        if (html == null || html.length() < 10) {
            return ret;
        }
        
        for (int i = 0; i < html.length();) {
            i = html.indexOf("<img", i);
            if (i == -1) {
                break;
            }
            Matcher m = Utils.findImageUrlsPattern.matcher(html.substring(i, html.length()));
            
            // Filter out URLs without leading http, we cannot work with relative URLs yet.
            if (m.find() && m.group(1).startsWith("http://")) {
                ret.add(m.group(1));
                i += m.group(1).length();
            } else {
                break;
            }
        }
        return ret;
    }
    
}
